package com.telcobright.summary.runtime.spi;

import com.telcobright.summary.engine.spi.SummaryStore;
import com.telcobright.summary.outbox.spi.OutboxStore;

/**
 * One drain's unit of work = ONE database transaction. A bean reads its offset + outbox rows through
 * {@link #outbox()}, writes its summaries through {@link #store()}, advances its offset through
 * {@link #outbox()}, then {@link #commit()}s once — so summaries + offset land together (exactly-once). Any
 * failure {@link #rollback()}s the whole drain. This is the only place a transaction is controlled (engine,
 * summary store, and outbox store never commit/rollback).
 *
 * <p>It is a seam so the reader is testable without a database: production is JDBC over a MySQL connection;
 * tests inject a fake that records commit/rollback and whose stores can be made to fail mid-drain.
 */
public interface UnitOfWork extends AutoCloseable {

    /**
     * The tenant schema this unit of work runs in, by its own name (a PostgreSQL schema, a MySQL database) —
     * what an ad summary row carries as {@code tup_tenant}. Never null: the drain hands it to the bean as the tier.
     */
    String schema();

    SummaryStore store();

    OutboxStore outbox();

    void commit();

    void rollback();

    @Override
    void close();
}
