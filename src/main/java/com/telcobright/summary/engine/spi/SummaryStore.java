package com.telcobright.summary.engine.spi;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * The database seam the engine writes through. Production is JDBC over the ONE transaction-bound MySQL
 * connection the batch owns; tests inject an in-memory fake (the fake IS the test surface). The store performs
 * NO commit/rollback — the batch runner owns the single transaction.
 */
public interface SummaryStore {

    /**
     * Load EVERY existing row whose bucket falls in {@code buckets}, for one table, in ONE query — the
     * "load all involved windows once" rule (loading per event would double-count). Selects {@code id} plus
     * {@code insertColumnsCsv} where {@code bucketColumn IN (buckets)} and maps each row via {@code mapper}.
     */
    <T> List<T> load(String table, String insertColumnsCsv, String bucketColumn,
                     Collection<LocalDateTime> buckets, RowMapper<T> mapper);

    /** Execute one INSERT/UPDATE/DELETE statement (or a {@code ;}-joined segment); returns affected rows. */
    int executeNonQuery(String sql);
}
