package com.telcobright.summary.registry.internal;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.runtime.api.BatchRunner;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.errors.WakeupException;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * One bean's poll loop on its own thread: poll a batch -> decode -> run it as ONE DB transaction -> commit the
 * Kafka offset ONLY AFTER the DB commit. That ordering is the v1 idempotency strategy: a crash between the DB
 * commit and the offset commit redelivers the batch and double-counts (increment is not idempotent); the
 * correction/overwrite path is the repair. On a DB failure the offset is NOT committed, so the batch redelivers.
 *
 * <p>RULE ONE logging: start/stop and failures only; never per-record.
 */
public final class SummaryWorker<E> implements Runnable {

    private static final Logger LOG = Logger.getLogger(SummaryWorker.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);
    private static final long FAILURE_BACKOFF_MS = 1000;

    private final SummaryBean<E> bean;
    private final Consumer<String, byte[]> consumer;
    private final BatchRunner batchRunner;
    private volatile boolean running = true;

    public SummaryWorker(SummaryBean<E> bean, Consumer<String, byte[]> consumer, BatchRunner batchRunner) {
        this.bean = bean;
        this.consumer = consumer;
        this.batchRunner = batchRunner;
    }

    @Override
    public void run() {
        LOG.infof("worker started: bean=%s topic=%s", bean.name(), bean.topic());
        try {
            consumer.subscribe(List.of(bean.topic()));
            while (running) {
                pollAndProcess();
            }
        } catch (WakeupException expectedOnStop) {
            // stop() called — fall through to close
        } finally {
            consumer.close();
            LOG.infof("worker stopped: bean=%s", bean.name());
        }
    }

    private void pollAndProcess() {
        ConsumerRecords<String, byte[]> records = consumer.poll(POLL_TIMEOUT);
        if (records.isEmpty()) {
            return;
        }
        List<E> events = decode(records);
        try {
            batchRunner.run(bean, events);   // ONE DB transaction
            consumer.commitSync();           // offsets AFTER the DB commit
        } catch (RuntimeException dbFailure) {
            LOG.errorf(dbFailure, "bean=%s batch of %d failed; offsets NOT committed (will redeliver)",
                    bean.name(), events.size());
            backoff();
        }
    }

    private List<E> decode(ConsumerRecords<String, byte[]> records) {
        List<E> events = new ArrayList<>(records.count());
        for (ConsumerRecord<String, byte[]> record : records) {
            events.add(bean.deserialize(record.value()));
        }
        return events;
    }

    public void stop() {
        running = false;
        consumer.wakeup();
    }

    private void backoff() {
        try {
            Thread.sleep(FAILURE_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
