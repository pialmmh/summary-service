package com.telcobright.summary.registry.internal;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryEntity;
import com.telcobright.summary.outbox.api.OutboxReader;

import org.jboss.logging.Logger;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * One bean's worker thread: drain the outbox, then wait until woken by a ping or the fallback poll timer, then
 * drain again. Each {@link OutboxReader#drain} step is its own exactly-once transaction; a drain failure is
 * logged and retried on the next tick (the offset never advanced, so no data is lost or double-counted).
 *
 * @param <T> the summary entity this worker's bean builds
 */
public final class OutboxWorker<T extends SummaryEntity<T>> implements Runnable {

    private static final Logger LOG = Logger.getLogger(OutboxWorker.class);

    private final SummaryBean<T> bean;
    private final OutboxReader reader;
    private final int pollIntervalSeconds;
    private final Semaphore wakeSignal = new Semaphore(0);
    private volatile boolean running = true;

    public OutboxWorker(SummaryBean<T> bean, OutboxReader reader, int pollIntervalSeconds) {
        this.bean = bean;
        this.reader = reader;
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    @Override
    public void run() {
        LOG.infof("worker started: bean=%s entity=%s table=%s window=%s", bean.name(), bean.entityType(),
                bean.table(), bean.window());
        while (running) {
            drainSafely();
            awaitWakeOrTimeout();
        }
        LOG.infof("worker stopped: bean=%s", bean.name());
    }

    private void drainSafely() {
        try {
            reader.drain(bean);
        } catch (RuntimeException e) {
            LOG.errorf(e, "bean=%s drain failed; offset unchanged, will retry", bean.name());
        }
    }

    private void awaitWakeOrTimeout() {
        try {
            wakeSignal.tryAcquire(pollIntervalSeconds, TimeUnit.SECONDS);
            wakeSignal.drainPermits();   // coalesce multiple pings into one drain
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    /** A ping arrived (or a manual nudge) — drain now instead of waiting for the timer. */
    public void wake() {
        wakeSignal.release();
    }

    public void stop() {
        running = false;
        wakeSignal.release();
    }
}
