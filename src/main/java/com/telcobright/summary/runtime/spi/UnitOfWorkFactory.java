package com.telcobright.summary.runtime.spi;

/**
 * Begins a fresh {@link UnitOfWork} (a new transaction-bound connection) for each batch — ON a tenant schema: a
 * PostgreSQL schema of the switch database, a MySQL database of the server. One process serves every tenant of
 * its root's tree through ONE pool, so the schema is entered per unit of work, never per pool.
 */
public interface UnitOfWorkFactory {

    /**
     * A unit of work in the tenant schema {@code schema} (its own name: {@code btcl}, {@code res_44}).
     * {@code null} = the connection's own schema, as its URL opens it (the one-tenant deployments).
     */
    UnitOfWork begin(String schema);

    /** A unit of work in the connection's own schema. */
    default UnitOfWork begin() {
        return begin(null);
    }
}
