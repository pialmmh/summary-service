package com.telcobright.summary.testkit;

import com.telcobright.summary.runtime.spi.UnitOfWork;
import com.telcobright.summary.runtime.spi.UnitOfWorkFactory;

/** Hands out units of work over the SAME shared fake stores; keeps the last one for flag assertions. */
public final class FakeUnitOfWorkFactory implements UnitOfWorkFactory {

    public final FakeSummaryStore store;
    public final FakeOutboxStore outbox;
    public FakeUnitOfWork last;

    public FakeUnitOfWorkFactory(FakeSummaryStore store, FakeOutboxStore outbox) {
        this.store = store;
        this.outbox = outbox;
    }

    @Override
    public UnitOfWork begin() {
        last = new FakeUnitOfWork(store, outbox);
        return last;
    }
}
