package com.telcobright.summary.registry.internal;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryEntity;
import com.telcobright.summary.outbox.api.OutboxReader;

import org.jboss.logging.Logger;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The worker thread of ONE bean in ONE tenant schema: drain that schema's outbox, then wait until woken by a
 * ping or the fallback poll timer, then drain again. Each {@link OutboxReader#drain} step is its own exactly-once
 * transaction; a drain failure is retried (the offset never advanced, so no data is lost or double-counted).
 *
 * <p><b>Trouble is tried again for ever, and said twice</b> (S16): ONE ERROR when it starts — with its cause — and
 * ONE INFO when the worker writes again, with how long it lasted and how many tries it took. The tries in between
 * are DEBUG lines: a database that is away for an hour does not write a line per try for every worker. While
 * failing the worker tries again at EVERY poll, never later (a ping wakes it at once): so a store that comes back
 * is written again by the next poll at the latest, by itself — no restart.
 *
 * @param <T> the summary entity this worker's bean builds
 */
public final class OutboxWorker<T extends SummaryEntity<T>> implements Runnable {

    private static final Logger LOG = Logger.getLogger(OutboxWorker.class);

    private final String schema;
    private final SummaryBean<T> bean;
    private final OutboxReader reader;
    private final int pollIntervalSeconds;
    private final Semaphore wakeSignal = new Semaphore(0);
    private volatile boolean running = true;
    private int consecutiveFailures = 0;   // touched only by this worker's own thread
    private long troubleSinceNanos;        // when the failing tries began (this worker's thread only)

    /** A worker on the connection's own schema. */
    public OutboxWorker(SummaryBean<T> bean, OutboxReader reader, int pollIntervalSeconds) {
        this(null, bean, reader, pollIntervalSeconds);
    }

    /** A worker on the tenant schema {@code schema} ({@code null} = the connection's own). */
    public OutboxWorker(String schema, SummaryBean<T> bean, OutboxReader reader, int pollIntervalSeconds) {
        this.schema = schema;
        this.bean = bean;
        this.reader = reader;
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    @Override
    public void run() {
        LOG.infof("worker started: schema=%s bean=%s entity=%s table=%s window=%s", said(), bean.name(), bean.entityType(),
                bean.table(), bean.window());
        while (running) {
            drainSafely();
            awaitWakeOrTimeout();
        }
        LOG.infof("worker stopped: schema=%s bean=%s", said(), bean.name());
    }

    private void drainSafely() {
        try {
            // the until-caught-up loop lives HERE, not in the reader, so stop() takes effect between the
            // bounded per-tx steps even mid-backlog — a second worker must never start while one still drains
            while (running) {
                int written = reader.drainOnce(schema, bean);         // each step is its own exactly-once transaction
                sayItWritesAgain();                                   // at the FIRST step that works — not after a backlog
                if (written == 0) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            sayTheTrouble(e);
        }
    }

    /** One ERROR when the trouble starts; the tries after it are DEBUG lines. */
    private void sayTheTrouble(RuntimeException failure) {
        consecutiveFailures++;
        if (consecutiveFailures == 1) {
            troubleSinceNanos = System.nanoTime();
            LOG.errorf(failure, "schema=%s bean=%s drain failed — offset STUCK, the summaries lag until it writes again. Tried again "
                    + "by itself (at every poll, %ds; at once on a ping), quietly: ONE line says when it writes again",
                    said(), bean.name(), pollIntervalSeconds);
        } else if (LOG.isDebugEnabled()) {
            LOG.debugf("schema=%s bean=%s drain failed again (%d tries) — tried again in %ds: %s", said(), bean.name(),
                    consecutiveFailures, pollIntervalSeconds, failure.toString());
        }
    }

    /** One INFO when it writes again after trouble: how long, how many tries. Nothing when there was none. */
    private void sayItWritesAgain() {
        if (consecutiveFailures > 0) {
            long seconds = (System.nanoTime() - troubleSinceNanos) / 1_000_000_000L;
            LOG.infof("schema=%s bean=%s writes again — after %d s and %d failed tr%s; nothing was lost: the bookmark had not moved",
                    said(), bean.name(), seconds, consecutiveFailures, consecutiveFailures == 1 ? "y" : "ies");
        }
        consecutiveFailures = 0;
    }

    /** The poll, whether the last drain worked or not: a store that is back is written again by the next poll at the latest. */
    private void awaitWakeOrTimeout() {
        try {
            wakeSignal.tryAcquire(pollIntervalSeconds, TimeUnit.SECONDS);
            wakeSignal.drainPermits();   // coalesce multiple pings into one drain
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    private String said() {
        return schema == null ? "(own)" : schema;
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
