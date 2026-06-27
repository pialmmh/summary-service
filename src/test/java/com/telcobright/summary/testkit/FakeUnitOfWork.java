package com.telcobright.summary.testkit;

import com.telcobright.summary.engine.spi.SummaryStore;
import com.telcobright.summary.outbox.spi.OutboxStore;
import com.telcobright.summary.runtime.spi.UnitOfWork;

/** A unit of work over shared fake stores that records commit/rollback/close (the reader/reaper test surface). */
public final class FakeUnitOfWork implements UnitOfWork {

    private final FakeSummaryStore store;
    private final FakeOutboxStore outbox;
    public boolean committed;
    public boolean rolledBack;
    public boolean closed;

    public FakeUnitOfWork(FakeSummaryStore store, FakeOutboxStore outbox) {
        this.store = store;
        this.outbox = outbox;
    }

    @Override
    public SummaryStore store() {
        return store;
    }

    @Override
    public OutboxStore outbox() {
        return outbox;
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
