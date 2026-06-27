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

/**
 * Drains one bean's outbox in ONE transaction per step — the exactly-once core, replacing the old per-batch
 * runner. Each {@link #drainOnce} reads the bean's {@code last_offset}, reads the next outbox rows, builds +
 * merges their entities (the ratified engine), then advances the offset and commits — summaries + offset
 * together. Any failure rolls the whole step back, so the offset never moves past un-written summaries
 * (crash → reprocessed clean, no double-count). {@link #drain} loops until the bean is caught up.
 */
@ApplicationScoped
public class OutboxReader {

    private static final Logger LOG = Logger.getLogger(OutboxReader.class);

    private final UnitOfWorkFactory unitOfWorkFactory;
    private final SummaryEngine engine;
    private final int segmentSize;
    private final int maxRowsPerTx;

    @Inject
    public OutboxReader(UnitOfWorkFactory unitOfWorkFactory, SummaryEngine engine,
                        @ConfigProperty(name = "summary.outbox.segment-size", defaultValue = "1000") int segmentSize,
                        @ConfigProperty(name = "summary.outbox.max-rows-per-tx", defaultValue = "50") int maxRowsPerTx) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.engine = engine;
        this.segmentSize = segmentSize;
        this.maxRowsPerTx = maxRowsPerTx;
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
     * One transaction: read offset → read rows → build+merge+write summaries → advance offset → commit.
     * Returns the number of outbox rows processed (0 when the bean is caught up).
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
            List<T> entities = buildEntities(bean, rows);
            engine.runBatch(bean, entities, unitOfWork.store(), segmentSize);
            long newOffset = rows.get(rows.size() - 1).id();
            unitOfWork.outbox().advanceOffset(bean.entityType(), bean.name(), newOffset);
            unitOfWork.commit();
            if (LOG.isDebugEnabled()) {
                LOG.debugf("bean=%s drained rows=%d -> offset=%d entities=%d", bean.name(), rows.size(),
                        newOffset, entities.size());
            }
            return rows.size();
        } catch (RuntimeException failure) {
            rollbackQuietly(unitOfWork, bean, failure);
            throw failure;
        } finally {
            closeQuietly(unitOfWork);
        }
    }

    private <T extends SummaryEntity<T>> List<T> buildEntities(SummaryBean<T> bean, List<OutboxRow> rows) {
        List<T> entities = new ArrayList<>();
        for (OutboxRow row : rows) {
            entities.addAll(bean.buildBatch(OutboxCodec.decode(row.data())));
        }
        return entities;
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
