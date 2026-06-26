package com.telcobright.summary.engine.spi;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * The database seam the engine writes through. The production implementation is JDBC over the ONE
 * transaction-bound MySQL connection the batch owns; tests inject an in-memory fake (the fake IS the test
 * surface). The store performs NO commit/rollback — the batch runner owns the single transaction.
 */
public interface SummaryStore {

    /**
     * Load EVERY existing row whose bucket falls in {@code buckets}, for one window, in ONE query. This is the
     * "load all involved windows once" rule — loading per event would double-count. May return an empty list.
     */
    List<SummaryRow> load(WindowSchema schema, Collection<LocalDateTime> buckets);

    /** Execute one INSERT/UPDATE/DELETE statement (or a {@code ;}-joined segment); returns affected rows. */
    int executeNonQuery(String sql);
}
