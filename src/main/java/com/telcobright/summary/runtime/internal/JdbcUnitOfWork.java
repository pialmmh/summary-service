package com.telcobright.summary.runtime.internal;

import com.telcobright.summary.engine.spi.SummaryStore;
import com.telcobright.summary.engine.spi.SummaryStoreException;
import com.telcobright.summary.outbox.spi.OutboxStore;
import com.telcobright.summary.runtime.spi.UnitOfWork;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * A JDBC unit of work over one MySQL connection with autocommit OFF. The summary store AND the outbox store
 * both write through this same connection, so commit/rollback here covers the summaries AND the offset advance
 * together — the single top-level transaction that makes a drain exactly-once.
 */
final class JdbcUnitOfWork implements UnitOfWork {

    private final Connection connection;
    private final SummaryStore store;
    private final OutboxStore outbox;

    JdbcUnitOfWork(Connection connection) {
        this.connection = connection;
        this.store = new JdbcSummaryStore(connection);
        this.outbox = new JdbcOutboxStore(connection);
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
        try {
            connection.commit();
        } catch (SQLException e) {
            throw new SummaryStoreException("commit failed", e);
        }
    }

    @Override
    public void rollback() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            throw new SummaryStoreException("rollback failed", e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new SummaryStoreException("connection close failed", e);
        }
    }
}
