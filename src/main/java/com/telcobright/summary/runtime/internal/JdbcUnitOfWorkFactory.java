package com.telcobright.summary.runtime.internal;

import com.telcobright.summary.bean.spi.SqlDialect;
import com.telcobright.summary.engine.spi.SummaryStoreException;
import com.telcobright.summary.runtime.spi.UnitOfWork;
import com.telcobright.summary.runtime.spi.UnitOfWorkFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Opens a fresh connection from the store's pool with autocommit OFF for each batch, ENTERED into the tenant
 * schema the batch is for. The connection (and its transaction) lives only for that batch and is closed by
 * {@link UnitOfWork#close()}. Everything an engine needs done to a connection before a drain is done here, and
 * nowhere else:
 *
 * <ul>
 *   <li><b>the schema</b> — MySQL: the tenant's database becomes the connection's ({@code USE}); PostgreSQL: the
 *       tenant's schema becomes the {@code search_path}. A pooled connection keeps the last schema it was given,
 *       so once any unit of work has named one, EVERY unit of work is entered explicitly;</li>
 *   <li><b>string literals</b> (PostgreSQL) — the entities render their SQL literals the MySQL way: a backslash is
 *       doubled. PostgreSQL's default ({@code standard_conforming_strings = on}) would store both: a zone or an app
 *       with a backslash would reload under a different key and get a second row for the same window. The session
 *       is set to read a backslash as MySQL does, so the same text means the same on both engines.</li>
 * </ul>
 */
@ApplicationScoped
public class JdbcUnitOfWorkFactory implements UnitOfWorkFactory {

    /** A tenant schema's name: what goes into {@code USE} / {@code SET search_path} unquoted, and nothing else. */
    private static final Pattern SCHEMA_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    private final Supplier<DataSource> dataSource;
    private final Supplier<SqlDialect> dialect;
    private volatile String ownSchema;
    private volatile boolean ownSchemaAsked;
    private volatile boolean entersSchemas;

    /** The service's wiring: the pool and the engine of the active profile, both asked at the first use. */
    @Inject
    public JdbcUnitOfWorkFactory(StoreDataSource store) {
        this.dataSource = store::get;
        this.dialect = store::dialect;
    }

    /** A MySQL datasource (tests and embedders, as before PostgreSQL existed). */
    public JdbcUnitOfWorkFactory(DataSource dataSource) {
        this(dataSource, SqlDialect.MYSQL);
    }

    public JdbcUnitOfWorkFactory(DataSource dataSource, SqlDialect dialect) {
        this.dataSource = () -> dataSource;
        this.dialect = () -> dialect;
    }

    @Override
    public UnitOfWork begin(String schema) {
        SqlDialect engine = dialect.get();
        if (schema != null) {
            requireSchemaName(schema);
            entersSchemas = true;
        }
        Connection connection;
        try {
            connection = dataSource.get().getConnection();
        } catch (SQLException e) {
            throw new SummaryStoreException("could not begin summary unit of work", e);
        }
        try {
            String entered = enter(connection, engine, schema);
            connection.setAutoCommit(false);
            return new JdbcUnitOfWork(connection, entered, engine);
        } catch (SQLException | RuntimeException e) {
            // a stale pooled connection failing here must go back closed, not leak checked-out of the pool
            // (leaked retries during an outage would drain the pool and outlive the outage)
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e instanceof SQLException sql ? new SummaryStoreException("could not begin summary unit of work", sql)
                    : (RuntimeException) e;
        }
    }

    /** Prepare the connection for a drain in {@code schema}; returns the name of the schema it now runs in. */
    private String enter(Connection connection, SqlDialect engine, String schema) throws SQLException {
        String own = ownSchemaOf(connection, engine);
        if (schema == null && own == null) {
            throw new SQLException("the store's URL names no " + (engine == SqlDialect.POSTGRESQL ? "schema" : "database")
                    + " and the unit of work names none — a summary worker must know the schema it serves");
        }
        // once one unit of work has entered a schema, a pooled connection may still be in it: go home explicitly
        String target = schema != null ? schema : entersSchemas ? own : null;
        if (engine == SqlDialect.POSTGRESQL) {
            try (Statement session = connection.createStatement()) {
                session.execute((target == null ? "" : "SET search_path TO " + target + "; ")
                        + "SET standard_conforming_strings TO off; SET escape_string_warning TO off");
            }
        } else if (target != null) {
            connection.setCatalog(target);
        }
        return schema != null ? schema : own;
    }

    /**
     * The connection's own schema, by name: the database a MySQL URL opens, the first schema of a PostgreSQL
     * URL's search path ({@code currentSchema=}); null when the URL names none (a deployment that serves a tree
     * enters every schema by name and needs none). Asked on the FIRST connection, before any is entered
     * elsewhere, then remembered.
     */
    private String ownSchemaOf(Connection connection, SqlDialect engine) throws SQLException {
        if (ownSchemaAsked) {
            return ownSchema;
        }
        synchronized (this) {
            if (!ownSchemaAsked) {
                String name = engine == SqlDialect.POSTGRESQL ? connection.getSchema() : connection.getCatalog();
                ownSchema = name == null || name.isBlank() ? null : name;
                ownSchemaAsked = true;
            }
            return ownSchema;
        }
    }

    private static void requireSchemaName(String schema) {
        if (!SCHEMA_NAME.matcher(schema).matches()) {
            throw new IllegalArgumentException("'" + schema + "' is not a tenant schema's name (letters, digits and _, at most 63)");
        }
    }
}
