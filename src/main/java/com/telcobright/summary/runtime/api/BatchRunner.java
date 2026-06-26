package com.telcobright.summary.runtime.api;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryEntity;
import com.telcobright.summary.engine.api.BatchResult;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.runtime.spi.UnitOfWork;
import com.telcobright.summary.runtime.spi.UnitOfWorkFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Runs ONE batch as ONE transaction — the single top-level commit/rollback (the ported MySqlCdrBatchRunner
 * rule). It begins a unit of work, hands the engine the transaction-bound store, and commits once; ANY
 * exception rolls the whole batch back (every window persists together or none). No inner class commits.
 */
@ApplicationScoped
public class BatchRunner {

    private static final Logger LOG = Logger.getLogger(BatchRunner.class);

    private final UnitOfWorkFactory unitOfWorkFactory;
    private final SummaryEngine engine;
    private final int segmentSize;

    @Inject
    public BatchRunner(UnitOfWorkFactory unitOfWorkFactory, SummaryEngine engine,
                       @ConfigProperty(name = "summary.defaults.segment-size", defaultValue = "1000") int segmentSize) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.engine = engine;
        this.segmentSize = segmentSize;
    }

    public <T extends SummaryEntity<T>> BatchResult run(SummaryBean<T> bean, List<T> entities) {
        UnitOfWork unitOfWork = unitOfWorkFactory.begin();
        try {
            BatchResult result = engine.runBatch(bean, entities, unitOfWork.store(), segmentSize);
            unitOfWork.commit();
            return result;
        } catch (RuntimeException failure) {
            rollbackQuietly(unitOfWork, bean, entities.size(), failure);
            throw failure;
        } finally {
            closeQuietly(unitOfWork);
        }
    }

    private void rollbackQuietly(UnitOfWork unitOfWork, SummaryBean<?> bean, int events, RuntimeException failure) {
        LOG.warnf(failure, "bean=%s batch of %d events rolled back", bean.name(), events);
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
