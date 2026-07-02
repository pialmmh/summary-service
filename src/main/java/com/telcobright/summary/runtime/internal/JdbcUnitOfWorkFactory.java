package com.telcobright.summary.runtime.internal;

import com.telcobright.summary.engine.spi.SummaryStoreException;
import com.telcobright.summary.runtime.spi.UnitOfWork;
import com.telcobright.summary.runtime.spi.UnitOfWorkFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Opens a fresh MySQL connection from the Agroal datasource with autocommit OFF for each batch. The
 * connection (and its transaction) lives only for that batch and is closed by {@link UnitOfWork#close()}.
 */
@ApplicationScoped
public class JdbcUnitOfWorkFactory implements UnitOfWorkFactory {

    private final DataSource dataSource;

    @Inject
    public JdbcUnitOfWorkFactory(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public UnitOfWork begin() {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException e) {
            throw new SummaryStoreException("could not begin summary unit of work", e);
        }
        try {
            connection.setAutoCommit(false);
            return new JdbcUnitOfWork(connection);
        } catch (SQLException | RuntimeException e) {
            // a stale pooled connection failing here must go back closed, not leak checked-out of Agroal
            // (leaked retries during a MySQL outage would drain the pool and outlive the outage)
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e instanceof SQLException sql ? new SummaryStoreException("could not begin summary unit of work", sql)
                    : (RuntimeException) e;
        }
    }
}
