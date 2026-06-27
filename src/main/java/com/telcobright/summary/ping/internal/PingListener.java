package com.telcobright.summary.ping.internal;

import com.telcobright.summary.registry.api.SummaryBeanRegistry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Listens to the {@code cdr_summary_ping} topic and nudges all workers to drain. The payload is informational
 * only (the MySQL offset is the truth), so this is a wakeup, NOT progress: a unique consumer group per process
 * makes every instance receive every ping (broadcast), and a lost/duplicate ping is harmless — the workers
 * also poll on a timer. Started only when {@code summary.autostart=true}; absent broker just means polling.
 */
@ApplicationScoped
public class PingListener {

    private static final Logger LOG = Logger.getLogger(PingListener.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);

    private final SummaryBeanRegistry registry;
    private final String topic;
    private final String bootstrapServers;
    private volatile boolean running = false;
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

    public synchronized void stop() {
        running = false;
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
            consumer.subscribe(List.of(topic));
            while (running) {
                ConsumerRecords<String, byte[]> records = consumer.poll(POLL_TIMEOUT);
                if (!records.isEmpty()) {
                    registry.wakeAll();   // a new batch landed — drain now
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
