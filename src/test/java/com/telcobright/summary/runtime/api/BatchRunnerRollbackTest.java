package com.telcobright.summary.runtime.api;

import com.telcobright.summary.beans.cdr.CdrSummary;
import com.telcobright.summary.beans.cdr.CdrSummaryBean;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.engine.spi.SummaryStore;
import com.telcobright.summary.runtime.spi.UnitOfWork;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.FakeSummaryStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one top-level transaction: success commits once; ANY failure rolls the whole batch back. No DB needed. */
class BatchRunnerRollbackTest {

    private final CdrSummaryBean bean = CdrTestSupport.dailyBean();
    private final List<CdrSummary> batch = List.of(CdrTestSupport.daySummary(CdrTestSupport.at(2026, 6, 19, 14, 30)));

    @Test
    void success_commits_once_and_does_not_roll_back() {
        RecordingUnitOfWork unitOfWork = new RecordingUnitOfWork(new FakeSummaryStore());
        BatchRunner runner = new BatchRunner(() -> unitOfWork, new SummaryEngine(), 1000);

        runner.run(bean, batch);

        assertTrue(unitOfWork.committed, "committed");
        assertFalse(unitOfWork.rolledBack, "not rolled back");
        assertTrue(unitOfWork.closed, "closed");
    }

    @Test
    void a_mid_batch_write_failure_rolls_the_whole_batch_back() {
        FakeSummaryStore failing = new FakeSummaryStore();
        failing.failWrites();
        RecordingUnitOfWork unitOfWork = new RecordingUnitOfWork(failing);
        BatchRunner runner = new BatchRunner(() -> unitOfWork, new SummaryEngine(), 1000);

        assertThrows(RuntimeException.class, () -> runner.run(bean, batch));

        assertFalse(unitOfWork.committed, "never committed");
        assertTrue(unitOfWork.rolledBack, "rolled back");
        assertTrue(unitOfWork.closed, "closed even on failure");
    }

    /** A unit of work that records commit/rollback/close around a fake store. */
    private static final class RecordingUnitOfWork implements UnitOfWork {
        private final SummaryStore store;
        boolean committed;
        boolean rolledBack;
        boolean closed;

        RecordingUnitOfWork(SummaryStore store) {
            this.store = store;
        }

        @Override
        public SummaryStore store() {
            return store;
        }

        @Override
        public void commit() {
            committed = true;
        }

        @Override
        public void rollback() {
            rolledBack = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
