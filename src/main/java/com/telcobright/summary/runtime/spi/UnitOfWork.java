package com.telcobright.summary.runtime.spi;

import com.telcobright.summary.engine.spi.SummaryStore;

/**
 * One batch's unit of work = ONE database transaction. The batch runner does load-merge-write through
 * {@link #store()}, then {@link #commit()}s once; on any failure it {@link #rollback()}s the whole batch.
 * This is the only place a transaction is controlled (the engine and store never commit/rollback).
 *
 * <p>It is a seam so the runner is testable without a database: production is JDBC over a MySQL connection;
 * tests inject a fake that records commit/rollback and whose store can be made to fail mid-batch.
 */
public interface UnitOfWork extends AutoCloseable {

    SummaryStore store();

    void commit();

    void rollback();

    @Override
    void close();
}
