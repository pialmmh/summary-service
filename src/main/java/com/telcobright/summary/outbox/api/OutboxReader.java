package com.telcobright.summary.outbox.api;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryEntity;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.outbox.internal.OutboxCodec;
import com.telcobright.summary.outbox.spi.OutboxRow;
import com.telcobright.summary.runtime.spi.UnitOfWork;
import com.telcobright.summary.runtime.spi.UnitOfWorkFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drains one bean's outbox in ONE transaction per step — the exactly-once core. Each {@link #drainOnce} reads
 * the bean's {@code last_offset}, reads the next outbox rows, builds + merges their entities (the ratified
 * engine), then advances the offset and commits — summaries + offset together. Any failure rolls the whole step
 * back, so the offset never moves past un-written summaries (crash → reprocessed clean, no double-count).
 *
 * <p>POISON rows (a blob that fails to decode/build — deterministic on the data, unlike a transient SQL error)
 * would otherwise wedge the bean forever AND block the reaper for the whole entity. After
 * {@code quarantine-after} consecutive failures on the same head row, the row is copied to
 * {@code summary_deadletter} (per bean) and the offset advances past it — in one transaction, loudly logged.
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
     * One transaction: read offset → read rows → build+merge+write summaries → advance offset → commit.
     * Returns the number of outbox rows consumed (0 when the bean is caught up).
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

            // decode/build row by row so ONE bad blob doesn't discard its clean neighbours
            List<T> entities = new ArrayList<>();
            int decoded = 0;
            RuntimeException poison = null;
            for (OutboxRow row : rows) {
                try {
                    entities.addAll(bean.buildBatch(OutboxCodec.decode(row.data())));
                    decoded++;
                } catch (RuntimeException decodeOrBuildFailure) {
                    poison = decodeOrBuildFailure;
                    break;   // rows after the bad one wait; rows before it commit below
                }
            }
            if (poison != null && decoded == 0) {
                return quarantineOrRethrow(bean, rows.get(0), poison, unitOfWork);
            }

            engine.runBatch(bean, entities, unitOfWork.store(), segmentSize);
            long newOffset = rows.get(decoded - 1).id();
            unitOfWork.outbox().advanceOffset(bean.entityType(), bean.name(), newOffset);
            unitOfWork.commit();
            poisonStreaks.remove(bean.name());
            if (poison != null) {
                LOG.warnf(poison, "bean=%s committed %d clean row(s); a poison row (id=%d) is now at the head",
                        bean.name(), decoded, rows.get(decoded).id());
            } else if (LOG.isDebugEnabled()) {
                LOG.debugf("bean=%s drained rows=%d -> offset=%d entities=%d", bean.name(), decoded,
                        newOffset, entities.size());
            }
            return decoded;
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
                + "summary_deadletter; that row's records are NOT summarised for this bean (repair via correction)",
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
