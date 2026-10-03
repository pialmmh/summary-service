package com.telcobright.summary.testkit;

import com.telcobright.summary.bean.spi.SqlDialect;
import com.telcobright.summary.engine.spi.SummaryStore;
import com.telcobright.summary.outbox.spi.OutboxStore;
import com.telcobright.summary.runtime.spi.UnitOfWork;

/** A unit of work over shared fake stores that records commit/rollback/close (the reader/reaper test surface). */
public final class FakeUnitOfWork implements UnitOfWork {

    /** The tier a fake drains for unless a test names another — what a real unit of work reads off its connection. */
    public static final String SCHEMA = "btcl";

    private final FakeSummaryStore store;
    private final FakeOutboxStore outbox;
    private final String schema;
    private final SqlDialect dialect;
    public boolean committed;
    public boolean rolledBack;
    public boolean closed;

    public FakeUnitOfWork(FakeSummaryStore store, FakeOutboxStore outbox) {
        this(store, outbox, SCHEMA);
    }

    public FakeUnitOfWork(FakeSummaryStore store, FakeOutboxStore outbox, String schema) {
        this(store, outbox, schema, SqlDialect.MYSQL);
    }

    public FakeUnitOfWork(FakeSummaryStore store, FakeOutboxStore outbox, String schema, SqlDialect dialect) {
        this.store = store;
        this.outbox = outbox;
        this.schema = schema;
        this.dialect = dialect;
    }

    @Override
    public SqlDialect dialect() {
        return dialect;
    }

    @Override
    public String schema() {
        return schema;
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
