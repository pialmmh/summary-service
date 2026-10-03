package com.telcobright.summary.testkit;

import com.telcobright.summary.bean.spi.SqlDialect;
import com.telcobright.summary.runtime.spi.UnitOfWork;
import com.telcobright.summary.runtime.spi.UnitOfWorkFactory;

/** Hands out units of work over the SAME shared fake stores; keeps the last one for flag assertions. */
public final class FakeUnitOfWorkFactory implements UnitOfWorkFactory {

    public final FakeSummaryStore store;
    public final FakeOutboxStore outbox;
    public FakeUnitOfWork last;
    /** The schema the next units of work say they run in ({@code null} = a unit of work that names none). */
    public String schema = FakeUnitOfWork.SCHEMA;

    public FakeUnitOfWorkFactory(FakeSummaryStore store, FakeOutboxStore outbox) {
        this.store = store;
        this.outbox = outbox;
    }

    /** The engine the units of work say they run on. */
    public SqlDialect dialect = SqlDialect.MYSQL;

    /** A named schema is the one the unit of work runs in; {@code null} = this factory's own ({@link #schema}). */
    @Override
    public UnitOfWork begin(String named) {
        last = new FakeUnitOfWork(store, outbox, named != null ? named : schema, dialect);
        return last;
    }
}
