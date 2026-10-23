package com.telcobright.summary.tenancy.internal;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Listens to the ROOT's doorbell — Kafka {@code config_event_loader_<root>}, rung by prime-context AFTER it rebuilt
 * the tree (a change in a configuration table of any tier, a reseller provisioned) — and asks for a read of the
 * tree. What the message says is not read: any ring means "the tree may have changed", and the tree itself is the
 * truth. A unique consumer group per process: every instance hears every ring. A ring that is lost costs nothing
 * for ever — the tree is also read on a timer. A new group reads from the END of the topic, so a ring before the
 * broker gave this listener its partitions is never heard: at that moment one read of the tree is asked for.
 */
public final class DoorbellListener {

    private static final Logger LOG = Logger.getLogger(DoorbellListener.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);

    private final String topic;
    private final String bootstrapServers;
    private final Runnable onRing;
    private volatile boolean running = false;
    private volatile boolean hearing = false;
    private Thread thread;
    private KafkaConsumer<String, byte[]> consumer;

    public DoorbellListener(String topic, String bootstrapServers, Runnable onRing) {
        this.topic = topic;
        this.bootstrapServers = bootstrapServers;
        this.onRing = onRing;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::consume, "summary-doorbell-listener");
        thread.setDaemon(true);
        thread.start();
    }

    /** True once the broker gave this listener its partitions: from then on a ring is heard. */
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
        LOG.infof("doorbell listener started: topic=%s brokers=%s", topic, bootstrapServers);
        try {
            consumer = new KafkaConsumer<>(props());
            consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    // nothing to give back: a ring is a hint, the tree is the truth
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    if (partitions.isEmpty()) {
                        return;
                    }
                    // this consumer reads from "latest": a ring before this moment was never heard. Ask for one read
                    // of the tree now, so a reseller provisioned while the listener was still deaf does not wait for the timer
                    // FIRST fix where this consumer reads from: until its position is known, "latest" is still being
                    // looked up, and a message sent in that instant would fall before it and never be read
                    for (TopicPartition partition : partitions) {
                        consumer.position(partition);
                    }
                    hearing = true;
                    LOG.infof("doorbell listener is hearing %s: the tree is read once for what rang before", topic);
                    onRing.run();
                }
            });
            while (running) {
                ConsumerRecords<String, byte[]> records = consumer.poll(POLL_TIMEOUT);
                if (!records.isEmpty()) {
                    onRing.run();                        // the tree may have changed: read it (debounced by the watcher)
                }
            }
        } catch (WakeupException expectedOnStop) {
            // stop() called
        } catch (RuntimeException e) {
            LOG.warn("doorbell listener stopped on error; the tree is still read on its timer", e);
        } finally {
            if (consumer != null) {
                consumer.close();
            }
            LOG.info("doorbell listener stopped");
        }
    }

    private Properties props() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "summary-doorbell-" + UUID.randomUUID());   // unique -> every instance hears every ring
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        return p;
    }
}
