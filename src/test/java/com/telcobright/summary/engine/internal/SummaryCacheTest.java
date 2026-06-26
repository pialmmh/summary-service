package com.telcobright.summary.engine.internal;

import com.telcobright.summary.beans.cdr.CdrSummary;
import com.telcobright.summary.engine.spi.MergeMode;
import com.telcobright.summary.testkit.CdrTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The typed cache merge math: increment, decrement, overwrite, insert-vs-update tracking, the redelivery story. */
class SummaryCacheTest {

    private static final String TABLE = CdrTestSupport.DAY_TABLE;

    private static SummaryCache<CdrSummary> cache() {
        return new SummaryCache<>(TABLE, CdrSummary.INSERT_COLUMNS);
    }

    private static CdrSummary call() {
        return CdrTestSupport.daySummary(CdrTestSupport.at(2026, 6, 19, 14, 30));
    }

    private static long totalcalls(SummaryCache<CdrSummary> cache) {
        return cache.rows().iterator().next().totalcalls;
    }

    @Test
    void add_into_a_new_window_inserts_and_accumulates() {
        SummaryCache<CdrSummary> cache = cache();
        cache.merge(call(), MergeMode.ADD);
        cache.merge(call(), MergeMode.ADD);   // same tuple again

        assertEquals(1, cache.insertedCount(), "still ONE insert (a row inserted this batch stays an insert)");
        assertEquals(0, cache.updatedCount());
        assertEquals(2, totalcalls(cache));
    }

    @Test
    void add_onto_a_loaded_window_updates_it() {
        SummaryCache<CdrSummary> cache = cache();
        CdrSummary existing = call();
        existing.setId(100L);
        cache.populateExisting(existing);

        cache.merge(call(), MergeMode.ADD);

        assertEquals(0, cache.insertedCount());
        assertEquals(1, cache.updatedCount(), "a LOADED row becomes an update");
        assertEquals(2, totalcalls(cache));
    }

    @Test
    void subtract_decrements_a_loaded_window() {
        SummaryCache<CdrSummary> cache = cache();
        CdrSummary existing = call();
        for (int i = 0; i < 4; i++) {
            existing.merge(call());          // totalcalls = 5
        }
        existing.setId(100L);
        cache.populateExisting(existing);

        cache.merge(call(), MergeMode.SUBTRACT);

        assertEquals(4, totalcalls(cache));
    }

    @Test
    void subtract_on_a_window_that_was_not_loaded_is_rejected() {
        SummaryCache<CdrSummary> cache = cache();
        assertThrows(IllegalStateException.class, () -> cache.merge(call(), MergeMode.SUBTRACT));
    }

    @Test
    void overwrite_replaces_counters_for_the_correction_path() {
        SummaryCache<CdrSummary> cache = cache();
        CdrSummary existing = call();
        for (int i = 0; i < 9; i++) {
            existing.merge(call());          // totalcalls = 10 (a stale window)
        }
        existing.setId(100L);
        cache.populateExisting(existing);

        cache.merge(call(), MergeMode.OVERWRITE);   // recomputed truth = 1 call

        assertEquals(1, totalcalls(cache), "counters replaced, not added");
    }

    @Test
    void redelivery_double_counts_and_overwrite_repairs_it() {
        CdrSummary afterBatch1 = call();   // a window of 1 call, committed
        afterBatch1.setId(100L);

        SummaryCache<CdrSummary> redelivered = cache();
        redelivered.populateExisting(afterBatch1);
        redelivered.merge(call(), MergeMode.ADD);
        assertEquals(2, totalcalls(redelivered), "increment is NOT idempotent — redelivery double-counts");

        CdrSummary doubled = redelivered.rows().iterator().next();
        SummaryCache<CdrSummary> correction = cache();
        correction.populateExisting(doubled);
        correction.merge(call(), MergeMode.OVERWRITE);
        assertEquals(1, totalcalls(correction), "correction recompute + overwrite repaired the count");
    }
}
