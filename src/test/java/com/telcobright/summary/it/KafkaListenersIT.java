package com.telcobright.summary.it;

import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.outbox.api.OutboxReader;
import com.telcobright.summary.outbox.internal.OutboxCodec;
import com.telcobright.summary.ping.internal.PingListener;
import com.telcobright.summary.registry.api.SummaryBeanRegistry;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.tenancy.internal.DoorbellListener;
import com.telcobright.summary.testkit.Await;
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory;
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory.Tier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The two Kafka ears on a real broker (the lab's own: {@code tools/lab/pg-lab.sh kafka}, 127.0.0.1:7692): the PING
 * — billing-core's {@code {tenant, entity, rows}} — wakes the workers of the tenant it names (brief S8), and the
 * root's DOORBELL asks for a read of the tree (brief S6). The topics are this run's own; the database behind the
 * workers is the fakes (the databases are tested elsewhere). SKIPS when the broker does not answer.
 */
class KafkaListenersIT {

    private static final String BROKER = System.getProperty("summary.it.kafka", "127.0.0.1:7692");
    private static final String DAILY = "dailyAdSummary";
    /** After a listener starts hearing: the broker may hand its partitions over more than once (each time it looks once). */
    private static final long SETTLE_MILLIS = 4000;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private KafkaProducer<String, String> producer;
    private SummaryBeanRegistry registry;

    @BeforeEach
    void theLabsBroker() {
        assumeTrue(brokerAnswers(), "the Kafka lab is not reachable at " + BROKER + " (tools/lab/pg-lab.sh kafka) — skipping integration test");
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producer = new KafkaProducer<>(p);
    }

    @AfterEach
    void stop() {
        if (registry != null) registry.stopAll();
        if (producer != null) producer.close();
    }

    /** A registry over the fakes with two served schemas, every worker past its first drain; the poll would take an hour. */
    private FakeUnitOfWorkFactory twoSchemasAsleep() {
        FakeUnitOfWorkFactory database = new FakeUnitOfWorkFactory();
        registry = new SummaryBeanRegistry(new OutboxReader(database, new SummaryEngine(), 1000, 50, 8), 3600);
        registry.register(AdTestSupport.dailyBean());
        Tier btcl = database.tier("btcl"), res44 = database.tier("res_44");
        registry.serve("btcl");
        registry.serve("res_44");
        assertTrue(Await.until(() -> btcl.outbox().hasBookmark("cdr", DAILY) && res44.outbox().hasBookmark("cdr", DAILY), 10_000));
        Await.pause(300);
        return database;
    }

    private static String view(boolean root) {
        java.time.LocalDateTime t = AdTestSupport.at(2026, 10, 2, 21, 14);
        return OutboxCodec.encode(AdTestSupport.batchOf(root ? AdTestSupport.rootView(t) : AdTestSupport.leafView(t)));
    }

    @Test
    void a_ping_on_the_topic_wakes_the_workers_of_the_tenant_it_names() {
        FakeUnitOfWorkFactory database = twoSchemasAsleep();
        Tier btcl = database.tier("btcl"), res44 = database.tier("res_44");
        String topic = "cdr_summary_ping_" + run;
        PingListener listener = new PingListener(registry, topic, BROKER);
        listener.start();
        try {
            assertTrue(Await.until(listener::hearing, 60_000), "the broker gave the listener its partitions");
            Await.pause(SETTLE_MILLIS);                             // the look-once of a start is over: both workers sleep again
            btcl.outbox().seed(1, view(true));
            res44.outbox().seed(1, view(false));

            // billing-core's ping after res_44's batch committed
            producer.send(new ProducerRecord<>(topic, null, "{\"tenant\":\"res_44\",\"entity\":\"cdr\",\"rows\":1}"));
            producer.flush();

            assertTrue(Await.until(() -> res44.outbox().readOffset("cdr", DAILY) == 1, 30_000), "res_44's view is summed after its ping");
            assertTrue(Await.never(() -> btcl.outbox().readOffset("cdr", DAILY) == 1, 1500), "btcl was not pinged: its row waits for its own");
        } finally {
            listener.stop();
        }
    }

    @Test
    void what_landed_before_the_ping_listener_could_hear_is_drained_the_moment_it_hears() {
        // a new consumer group reads from the END of the topic: a ping sent before the broker assigned its partitions is
        // never heard (found on the lab: a view written seconds after a start waited for the poll). No ping is sent here.
        FakeUnitOfWorkFactory database = twoSchemasAsleep();
        Tier btcl = database.tier("btcl"), res44 = database.tier("res_44");
        btcl.outbox().seed(1, view(true));
        res44.outbox().seed(1, view(false));
        PingListener listener = new PingListener(registry, "cdr_summary_ping_" + run, BROKER);

        listener.start();
        try {
            assertTrue(Await.until(() -> btcl.outbox().readOffset("cdr", DAILY) == 1 && res44.outbox().readOffset("cdr", DAILY) == 1, 60_000),
                    "every worker looked once when the listener started hearing");
            assertTrue(listener.hearing());
        } finally {
            listener.stop();
        }
    }

    @Test
    void a_ring_of_the_roots_doorbell_asks_for_a_read_of_the_tree() {
        String topic = "config_event_loader_btcl_" + run;
        AtomicInteger rings = new AtomicInteger();
        DoorbellListener doorbell = new DoorbellListener(topic, BROKER, rings::incrementAndGet);
        doorbell.start();
        try {
            assertTrue(Await.until(doorbell::hearing, 60_000), "the broker gave the listener its partitions");
            // the listener says it hears, THEN asks for the read — on its own thread: wait for it (asserting at once raced
            // with that thread on a loaded box: seen red once in a dozen suite runs)
            assertTrue(Await.until(() -> rings.get() >= 1, 10_000), "a read of the tree was asked the moment it could hear: a ring before that would have been lost");
            Await.pause(SETTLE_MILLIS);                             // the broker may hand the partitions over more than once at a start
            int before = rings.get();

            // prime-context's message after a rebuild (config-manager's config_reload shape); what it says is not read
            producer.send(new ProducerRecord<>(topic, "btcl", "{\"type\":\"config_reload\",\"source\":\"prime-context\",\"changes\":[\"res_45.partner:INSERT\"]}"));
            producer.flush();

            assertTrue(Await.until(() -> rings.get() > before, 30_000), "the ring was heard");
        } finally {
            doorbell.stop();
        }
    }

    private static boolean brokerAnswers() {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER);
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3000);
        try (AdminClient admin = AdminClient.create(p)) {
            admin.describeCluster().nodes().get(4, TimeUnit.SECONDS);
            return true;
        } catch (Exception unreachable) {
            return false;
        }
    }
}
