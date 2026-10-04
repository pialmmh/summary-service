package com.telcobright.summary.ping.internal;

import com.telcobright.summary.registry.api.SummaryBeanRegistry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Listens to the {@code cdr_summary_ping} topic and nudges the workers of the tenant a ping names to drain
 * (brief S8: billing-core's ping is {@code {tenant, entity, rows}}, sent after a tenant batch committed — the
 * tenant is the schema with a new outbox row). A ping that names no tenant wakes every worker, as before. The
 * payload carries no data (the bookmark in the database is the truth), so this is a wakeup, NOT progress: a
 * unique consumer group per process makes every instance receive every ping (broadcast), and a lost or duplicate
 * ping is harmless — the workers also poll on a timer. A new group reads from the END of the topic, so a ping sent
 * before the broker gave this listener its partitions is never heard: at that moment every worker is woken once. Started only when {@code summary.autostart=true}; an absent
 * broker just means polling.
 */
@ApplicationScoped
public class PingListener {

    private static final Logger LOG = Logger.getLogger(PingListener.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);

    private final SummaryBeanRegistry registry;
    private final String topic;
    private final String bootstrapServers;
    private volatile boolean running = false;
    private volatile boolean hearing = false;
    private Thread thread;
    private KafkaConsumer<String, byte[]> consumer;

    @Inject
    public PingListener(SummaryBeanRegistry registry,
                        @ConfigProperty(name = "summary.outbox.ping-topic", defaultValue = "cdr_summary_ping") String topic,
                        @ConfigProperty(name = "summary.outbox.ping-bootstrap-servers", defaultValue = "127.0.0.1:9092") String bootstrapServers) {
        this.registry = registry;
        this.topic = topic;
        this.bootstrapServers = bootstrapServers;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::consume, "summary-ping-listener");
        thread.setDaemon(true);
        thread.start();
    }

    /** True once the broker gave this listener its partitions: from then on a ping is heard. */
    public boolean hearing() {
        return hearing;
    }

    public synchronized void stop() {
        running = false;
        hearing = false;
        if (consumer != null) {
            consumer.wakeup();
        }
        if (thread != null) {
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void consume() {
        LOG.infof("ping listener started: topic=%s", topic);
        try {
            consumer = new KafkaConsumer<>(props());
            consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    // nothing to give back: a ping is a wakeup, not progress
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    if (partitions.isEmpty()) {
                        return;
                    }
                    // this consumer reads from "latest": a ping sent before this moment was never heard. Look once
                    // now, so what landed while the listener was still deaf does not wait for the poll timer
                    // FIRST fix where this consumer reads from: until its position is known, "latest" is still being
                    // looked up, and a message sent in that instant would fall before it and never be read
                    for (TopicPartition partition : partitions) {
                        consumer.position(partition);
                    }
                    hearing = true;
                    LOG.infof("ping listener is hearing %s: every worker looks once for what landed before", topic);
                    registry.wakeAll();
                }
            });
            while (running) {
                ConsumerRecords<String, byte[]> records = consumer.poll(POLL_TIMEOUT);
                for (ConsumerRecord<String, byte[]> record : records) {
                    wakeFor(registry, record.value());   // a new batch landed in that tenant's schema — drain it now
                }
            }
        } catch (WakeupException expectedOnStop) {
            // stop() called
        } catch (RuntimeException e) {
            LOG.warn("ping listener stopped on error; workers still drain on the poll timer", e);
        } finally {
            if (consumer != null) {
                consumer.close();
            }
            LOG.info("ping listener stopped");
        }
    }

    /**
     * Wake what a ping asks for: the workers of the tenant schema it names, of the entity it names. A ping that
     * cannot be read, or that names no tenant, wakes every worker (an older producer; a wakeup too many is free).
     * Returns the number of workers woken.
     */
    public static int wakeFor(SummaryBeanRegistry registry, byte[] ping) {
        PingPayload said = PingPayload.parse(ping);
        if (said == null || said.tenant() == null) {
            registry.wakeAll();
            return -1;
        }
        int woken = registry.wake(said.tenant(), said.entity());
        if (woken == 0 && LOG.isDebugEnabled()) {
            LOG.debugf("ping for tenant %s (entity %s): no worker of it here — not a schema this process serves", said.tenant(), said.entity());
        }
        return woken;
    }

    private Properties props() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "summary-ping-" + UUID.randomUUID());   // unique -> broadcast
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        return p;
    }
}
