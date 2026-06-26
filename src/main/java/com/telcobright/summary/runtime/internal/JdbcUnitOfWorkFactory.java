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
        try {
            Connection connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            return new JdbcUnitOfWork(connection);
        } catch (SQLException e) {
            throw new SummaryStoreException("could not begin summary unit of work", e);
        }
    }
}
