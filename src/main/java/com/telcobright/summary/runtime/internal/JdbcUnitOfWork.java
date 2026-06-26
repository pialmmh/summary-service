package com.telcobright.summary.runtime.internal;

import com.telcobright.summary.engine.spi.SummaryStore;
import com.telcobright.summary.engine.spi.SummaryStoreException;
import com.telcobright.summary.runtime.spi.UnitOfWork;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * A JDBC unit of work over one MySQL connection with autocommit OFF. The store writes through this same
 * connection, so commit/rollback here covers every INSERT/UPDATE/DELETE the batch did — the single
 * top-level transaction.
 */
final class JdbcUnitOfWork implements UnitOfWork {

    private final Connection connection;
    private final SummaryStore store;

    JdbcUnitOfWork(Connection connection) {
        this.connection = connection;
        this.store = new JdbcSummaryStore(connection);
    }

    @Override
    public SummaryStore store() {
        return store;
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
