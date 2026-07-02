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
 * logged and retried (the offset never advanced, so no data is lost or double-counted). Repeated failures — a
 * poison outbox row, a broken summary table — back off up to {@link #MAX_BACKOFF_SECONDS} with an escalating
 * count in the log, so a wedged bean is LOUD without hammering the database every tick.
 *
 * @param <T> the summary entity this worker's bean builds
 */
public final class OutboxWorker<T extends SummaryEntity<T>> implements Runnable {

    private static final Logger LOG = Logger.getLogger(OutboxWorker.class);
    private static final int MAX_BACKOFF_SECONDS = 60;

    private final SummaryBean<T> bean;
    private final OutboxReader reader;
    private final int pollIntervalSeconds;
    private final Semaphore wakeSignal = new Semaphore(0);
    private volatile boolean running = true;
    private int consecutiveFailures = 0;   // touched only by this worker's own thread

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
            // the until-caught-up loop lives HERE, not in the reader, so stop() takes effect between the
            // bounded per-tx steps even mid-backlog — a second worker must never start while one still drains
            while (running && reader.drainOnce(bean) > 0) {
                // each step is its own exactly-once transaction
            }
            consecutiveFailures = 0;
        } catch (RuntimeException e) {
            consecutiveFailures++;
            LOG.errorf(e, "bean=%s drain failed (%d consecutive) — offset STUCK, summaries lag until fixed; "
                    + "retrying in %ds", bean.name(), consecutiveFailures, waitSeconds());
        }
    }

    private void awaitWakeOrTimeout() {
        try {
            wakeSignal.tryAcquire(waitSeconds(), TimeUnit.SECONDS);
            wakeSignal.drainPermits();   // coalesce multiple pings into one drain
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    /** Normal tick = the poll interval; while failing, back off linearly up to the cap. */
    private int waitSeconds() {
        if (consecutiveFailures == 0) {
            return pollIntervalSeconds;
        }
        return (int) Math.min((long) pollIntervalSeconds * consecutiveFailures, MAX_BACKOFF_SECONDS);
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
