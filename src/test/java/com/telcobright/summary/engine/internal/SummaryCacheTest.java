package com.telcobright.summary.engine.internal;

import com.telcobright.summary.summarybeans.call.model.CallSummary;
import com.telcobright.summary.engine.spi.MergeMode;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.FakeSummaryStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The typed cache merge math: increment, decrement, overwrite, insert-vs-update tracking, the redelivery story. */
class SummaryCacheTest {

    private static final String TABLE = CdrTestSupport.DAY_TABLE;

    private static SummaryCache<CallSummary> cache() {
        return new SummaryCache<>(TABLE, CallSummary.INSERT_COLUMNS, CallSummary.BUCKET_COLUMN);
    }

    private static CallSummary call() {
        return CdrTestSupport.daySummary(CdrTestSupport.at(2026, 6, 19, 14, 30));
    }

    private static long totalcalls(SummaryCache<CallSummary> cache) {
        return cache.rows().iterator().next().totalcalls;
    }

    @Test
    void add_into_a_new_window_inserts_and_accumulates() {
        SummaryCache<CallSummary> cache = cache();
        cache.merge(call(), MergeMode.ADD);
        cache.merge(call(), MergeMode.ADD);   // same tuple again

        assertEquals(1, cache.insertedCount(), "still ONE insert (a row inserted this batch stays an insert)");
        assertEquals(0, cache.updatedCount());
        assertEquals(2, totalcalls(cache));
    }

    @Test
    void add_onto_a_loaded_window_updates_it() {
        SummaryCache<CallSummary> cache = cache();
        CallSummary existing = call();
        existing.setId(100L);
        cache.populateExisting(existing);

        cache.merge(call(), MergeMode.ADD);

        assertEquals(0, cache.insertedCount());
        assertEquals(1, cache.updatedCount(), "a LOADED row becomes an update");
        assertEquals(2, totalcalls(cache));
    }

    @Test
    void subtract_decrements_a_loaded_window() {
        SummaryCache<CallSummary> cache = cache();
        CallSummary existing = call();
        for (int i = 0; i < 4; i++) {
            existing.merge(call());          // totalcalls = 5
        }
        existing.setId(100L);
        cache.populateExisting(existing);

        cache.merge(call(), MergeMode.SUBTRACT);

        assertEquals(4, totalcalls(cache));
    }

    @Test
    void subtract_negates_a_copy_not_the_callers_delta() {
        SummaryCache<CallSummary> cache = cache();
        CallSummary existing = call();
        existing.merge(call());            // window at 2
        existing.setId(100L);
        cache.populateExisting(existing);

        CallSummary delta = call();        // totalcalls = 1
        cache.merge(delta, MergeMode.SUBTRACT);

        assertEquals(1, totalcalls(cache), "window decremented");
        assertEquals(1, delta.totalcalls, "the caller's delta is NOT negated in place — it may be reused (e.g. day then hour cache)");
    }

    @Test
    void subtract_on_a_window_that_was_not_loaded_is_rejected() {
        SummaryCache<CallSummary> cache = cache();
        assertThrows(IllegalStateException.class, () -> cache.merge(call(), MergeMode.SUBTRACT));
    }

    @Test
    void overwrite_replaces_counters_for_the_correction_path() {
        SummaryCache<CallSummary> cache = cache();
        CallSummary existing = call();
        for (int i = 0; i < 9; i++) {
            existing.merge(call());          // totalcalls = 10 (a stale window)
        }
        existing.setId(100L);
        cache.populateExisting(existing);

        cache.merge(call(), MergeMode.OVERWRITE);   // recomputed truth = 1 call

        assertEquals(1, totalcalls(cache), "counters replaced, not added");
    }

    @Test
    void overwrite_of_a_row_inserted_this_batch_flushes_the_overwritten_values() {
        SummaryCache<CallSummary> cache = cache();
        cache.merge(call(), MergeMode.ADD);      // new window -> pending INSERT with totalcalls=1

        CallSummary recomputed = call();
        recomputed.merge(call());                // recomputed truth = 2 calls
        cache.merge(recomputed, MergeMode.OVERWRITE);

        FakeSummaryStore store = new FakeSummaryStore();
        cache.flush(store, 1000);

        String insert = store.firstSqlMatching("insert into " + TABLE);
        assertNotNull(insert, "the overwritten window still flushes as an INSERT (it has no DB id yet)");
        assertTrue(insert.contains("'2026-06-19 00:00:00',2,"),
                "the pending INSERT must carry the OVERWRITE values, not the stale first-merge object: " + insert);
        assertEquals(null, store.firstSqlMatching("update " + TABLE),
                "an inserted-this-batch row never becomes an UPDATE, even after OVERWRITE");
    }

    @Test
    void update_carries_the_date_bucket_so_mysql_prunes_to_one_partition() {
        SummaryCache<CallSummary> cache = cache();
        CallSummary existing = call();          // daily bucket of a 14:30 call -> tup_starttime = 2026-06-19 00:00:00
        existing.setId(100L);
        cache.populateExisting(existing);
        cache.merge(call(), MergeMode.ADD);     // a LOADED row + a merge -> an UPDATE

        FakeSummaryStore store = new FakeSummaryStore();
        cache.flush(store, 1000);

        String update = store.firstSqlMatching("update " + TABLE);
        assertNotNull(update, "the loaded window should have flushed as an UPDATE");
        assertTrue(update.contains(" where id=100 and tup_starttime='2026-06-19 00:00:00'"),
                "UPDATE must add the partition-key bucket so MySQL prunes to one date partition, not scan all: " + update);
    }

    @Test
    void redelivery_double_counts_and_overwrite_repairs_it() {
        CallSummary afterBatch1 = call();   // a window of 1 call, committed
        afterBatch1.setId(100L);

        SummaryCache<CallSummary> redelivered = cache();
        redelivered.populateExisting(afterBatch1);
        redelivered.merge(call(), MergeMode.ADD);
        assertEquals(2, totalcalls(redelivered), "increment is NOT idempotent — redelivery double-counts");

        CallSummary doubled = redelivered.rows().iterator().next();
        SummaryCache<CallSummary> correction = cache();
        correction.populateExisting(doubled);
        correction.merge(call(), MergeMode.OVERWRITE);
        assertEquals(1, totalcalls(correction), "correction recompute + overwrite repaired the count");
    }
}
