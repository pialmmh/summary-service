package com.telcobright.summary.outbox.api;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryEntity;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.engine.spi.MissingWindowException;
import com.telcobright.summary.outbox.internal.OutboxCodec;
import com.telcobright.summary.outbox.internal.OutboxInfraDdl;
import com.telcobright.summary.outbox.spi.OutboxRow;
import com.telcobright.summary.runtime.spi.UnitOfWork;
import com.telcobright.summary.runtime.spi.UnitOfWorkFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drains one bean's outbox in ONE transaction per step — the exactly-once core. Each {@link #drainOnce} reads
 * the bean's {@code last_offset}, reads the next outbox rows, builds + merges their entities (the ratified
 * engine), then advances the offset and commits — summaries + offset together. Any failure rolls the whole step
 * back, so the offset never moves past un-written summaries (crash → reprocessed clean, no double-count).
 *
 * <p>POISON rows (a blob that fails to decode/build — deterministic on the data, unlike a transient SQL error)
 * would otherwise wedge the bean forever AND block the reaper for the whole entity. After
 * {@code quarantine-after} consecutive failures on the same head row, the row is copied to
 * {@code summary_affected_dlq} (per bean) and the offset advances past it — in one transaction, loudly logged.
 * SQL-layer failures are NEVER quarantined: they may be transient (or a config fault), and retrying loses nothing.
 */
@ApplicationScoped
public class OutboxReader {

    private static final Logger LOG = Logger.getLogger(OutboxReader.class);

    private final UnitOfWorkFactory unitOfWorkFactory;
    private final SummaryEngine engine;
    private final int segmentSize;
    private final int maxRowsPerTx;
    private final int quarantineAfter;
    /** beanName → {failing head row id, consecutive decode/build failures on it}. In-memory by design. */
    private final Map<String, long[]> poisonStreaks = new ConcurrentHashMap<>();
    private final AtomicBoolean infraEnsured = new AtomicBoolean(false);

    @Inject
    public OutboxReader(UnitOfWorkFactory unitOfWorkFactory, SummaryEngine engine,
                        @ConfigProperty(name = "summary.outbox.segment-size", defaultValue = "1000") int segmentSize,
                        @ConfigProperty(name = "summary.outbox.max-rows-per-tx", defaultValue = "1") int maxRowsPerTx,
                        @ConfigProperty(name = "summary.outbox.quarantine-after", defaultValue = "8") int quarantineAfter) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.engine = engine;
        this.segmentSize = segmentSize;
        this.maxRowsPerTx = maxRowsPerTx;
        this.quarantineAfter = quarantineAfter;
    }

    /** Drain until this bean is caught up; returns the total outbox rows processed. */
    public <T extends SummaryEntity<T>> int drain(SummaryBean<T> bean) {
        int total = 0;
        int processed;
        while ((processed = drainOnce(bean)) > 0) {
            total += processed;
        }
        return total;
    }

    /**
     * Ensure the service-level infra tables exist ({@code summary_offset}, {@code summary_affected_dlq}, and
     * a local/dev copy of {@code summary_affected}) — once per process, before the first worker starts.
     */
    public void ensureInfraTables() {
        if (!infraEnsured.compareAndSet(false, true)) {
            return;
        }
        UnitOfWork unitOfWork = unitOfWorkFactory.begin();
        try {
            for (String ddl : OutboxInfraDdl.createStatements()) {
                unitOfWork.store().executeNonQuery(ddl);   // DDL — autocommits server-side
            }
            unitOfWork.commit();
        } catch (RuntimeException failure) {
            infraEnsured.set(false);   // retry on the next start attempt
            LOG.error("infra table provisioning failed", failure);
            throw failure;
        } finally {
            closeQuietly(unitOfWork);
        }
    }

    /**
     * Self-provision the bean's target table (user directive 2026-07-02): run its {@code tableDdl()} —
     * {@code CREATE TABLE IF NOT EXISTS} carrying the full partition set — before its first drain. A no-op
     * when the table already exists (the pre-provisioned prod sets) or the bean ships no DDL.
     */
    public void ensureProvisioned(SummaryBean<?> bean) {
        String ddl = bean.tableDdl();
        if (ddl == null) {
            return;
        }
        UnitOfWork unitOfWork = unitOfWorkFactory.begin();
        try {
            unitOfWork.store().executeNonQuery(ddl);
            unitOfWork.commit();
            LOG.infof("bean=%s table %s ensured (CREATE IF NOT EXISTS)", bean.name(), bean.table());
        } catch (RuntimeException failure) {
            rollbackQuietly(unitOfWork, bean, failure);
            throw failure;
        } finally {
            closeQuietly(unitOfWork);
        }
    }

    /**
     * Seed the bean's offset at the outbox HEAD if it has no bookmark yet (its own small transaction) — run
     * BEFORE the bean's worker starts. A late-enabled bean must summarise from NOW, not from the arbitrary
     * residue the reaper happens not to have deleted (a partial backfill would look like complete windows).
     */
    public void initOffsetAtHead(SummaryBean<?> bean) {
        UnitOfWork unitOfWork = unitOfWorkFactory.begin();
        try {
            unitOfWork.outbox().initOffsetAtHead(bean.entityType(), bean.name());
            unitOfWork.commit();
        } catch (RuntimeException failure) {
            rollbackQuietly(unitOfWork, bean, failure);
            throw failure;
        } finally {
            closeQuietly(unitOfWork);
        }
    }

    /**
     * One transaction: read offset → per row IN ID ORDER: decode+build, then load-merge-write with the ROW's
     * {@code op} ({@code add}/{@code subtract}) → advance offset → commit. One outbox row = one packed billing
     * batch, so the load-windows-once invariant holds at exactly the legacy batch granularity; a later row in
     * the same tx re-reads windows an earlier row just wrote (same connection sees its own writes), which is
     * what makes a subtract row directly behind its add row correct. Returns the rows consumed (0 = caught up).
     */
    public <T extends SummaryEntity<T>> int drainOnce(SummaryBean<T> bean) {
        UnitOfWork unitOfWork = unitOfWorkFactory.begin();
        try {
            long offset = unitOfWork.outbox().readOffset(bean.entityType(), bean.name());
            List<OutboxRow> rows = unitOfWork.outbox().readAfter(bean.entityType(), offset, maxRowsPerTx);
            if (rows.isEmpty()) {
                unitOfWork.commit();
                return 0;
            }

            // row by row so ONE poison row doesn't discard its clean neighbours
            int consumed = 0;
            RuntimeException poison = null;
            OutboxRow poisonRow = null;
            for (OutboxRow row : rows) {
                List<T> entities;
                try {
                    entities = bean.buildBatch(OutboxCodec.decode(row.data()));
                } catch (RuntimeException decodeOrBuildFailure) {   // deterministic on the row's data -> poison
                    poison = decodeOrBuildFailure;
                    poisonRow = row;
                    break;
                }
                try {
                    engine.runBatch(bean, entities, row.mergeMode(), unitOfWork.store(), segmentSize);
                } catch (MissingWindowException subtractOnMissing) { // ruling A1: quarantinable, not a wedge
                    poison = subtractOnMissing;                      // (thrown pre-flush -> this row wrote nothing)
                    poisonRow = row;
                    break;
                }
                consumed++;
            }
            if (consumed == 0 && poison != null) {
                return quarantineOrRethrow(bean, poisonRow, poison, unitOfWork);
            }

            long newOffset = rows.get(consumed - 1).id();
            unitOfWork.outbox().advanceOffset(bean.entityType(), bean.name(), newOffset);
            unitOfWork.commit();
            poisonStreaks.remove(bean.name());
            if (poison != null) {
                LOG.warnf(poison, "bean=%s committed %d clean row(s); a poison row (id=%d) is now at the head",
                        bean.name(), consumed, poisonRow.id());
            } else if (LOG.isDebugEnabled()) {
                LOG.debugf("bean=%s drained rows=%d -> offset=%d", bean.name(), consumed, newOffset);
            }
            return consumed;
        } catch (RuntimeException failure) {
            rollbackQuietly(unitOfWork, bean, failure);
            throw failure;
        } finally {
            closeQuietly(unitOfWork);
        }
    }

    /** The failing row IS the head: quarantine it once the streak reaches the threshold, else rethrow. */
    private int quarantineOrRethrow(SummaryBean<?> bean, OutboxRow row, RuntimeException poison, UnitOfWork unitOfWork) {
        if (nextPoisonStreak(bean.name(), row.id()) < quarantineAfter) {
            throw poison;   // rolled back by the caller; the worker's backoff paces the retries
        }
        unitOfWork.outbox().deadLetter(bean.entityType(), bean.name(), row, summarize(poison));
        unitOfWork.outbox().advanceOffset(bean.entityType(), bean.name(), row.id());
        unitOfWork.commit();
        poisonStreaks.remove(bean.name());
        LOG.errorf(poison, "bean=%s QUARANTINED poison outbox row id=%d after %d attempts — copied to "
                + "summary_affected_dlq; that row's records are NOT summarised for this bean (repair via correction)",
                bean.name(), row.id(), quarantineAfter);
        return 1;
    }

    private int nextPoisonStreak(String beanName, long rowId) {
        long[] streak = poisonStreaks.compute(beanName,
                (k, prev) -> prev == null || prev[0] != rowId ? new long[]{rowId, 1} : new long[]{rowId, prev[1] + 1});
        return (int) streak[1];
    }

    private static String summarize(Throwable poison) {
        String text = poison.toString();
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private void rollbackQuietly(UnitOfWork unitOfWork, SummaryBean<?> bean, RuntimeException failure) {
        LOG.warnf(failure, "bean=%s drain rolled back; offset unchanged (will retry)", bean.name());
        try {
            unitOfWork.rollback();
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private void closeQuietly(UnitOfWork unitOfWork) {
        try {
            unitOfWork.close();
        } catch (RuntimeException closeFailure) {
            LOG.warn("unit of work close failed", closeFailure);
        }
    }
}
