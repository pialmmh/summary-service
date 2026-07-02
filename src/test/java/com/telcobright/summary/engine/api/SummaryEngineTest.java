package com.telcobright.summary.engine.api;

import com.telcobright.summary.summarybeans.call.model.CallSummary;
import com.telcobright.summary.summarybeans.call.internal.CallSummaryBean;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.FakeSummaryStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.telcobright.summary.testkit.CdrTestSupport.DAY_TABLE;
import static com.telcobright.summary.testkit.CdrTestSupport.at;
import static com.telcobright.summary.testkit.CdrTestSupport.daySummary;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The full load-merge-write pipeline over the fake store, typed on CallSummary: load-once, insert, update. */
class SummaryEngineTest {

    private final SummaryEngine engine = new SummaryEngine();
    private final CallSummaryBean bean = CdrTestSupport.dailyBean();

    @Test
    void fresh_batch_inserts_a_day_row() {
        FakeSummaryStore store = new FakeSummaryStore();

        BatchResult result = engine.runBatch(bean, List.of(daySummary(at(2026, 6, 19, 14, 30))), store);

        assertTrue(store.ranSqlMatching("insert into " + DAY_TABLE), "day row inserted");
        assertEquals(1, result.rowsInserted());
        assertEquals(0, result.rowsUpdated());
    }

    @Test
    void involved_windows_are_loaded_once_not_per_event() {
        FakeSummaryStore store = new FakeSummaryStore();
        List<CallSummary> batch = List.of(
                daySummary(at(2026, 6, 19, 0, 30)),
                daySummary(at(2026, 6, 19, 14, 0)),    // same day 19
                daySummary(at(2026, 6, 20, 9, 15)),
                daySummary(at(2026, 6, 21, 23, 0)),
                daySummary(at(2026, 6, 21, 23, 45)));  // same day 21

        engine.runBatch(bean, batch, store);

        assertEquals(1, store.loadCount(DAY_TABLE), "the window is loaded exactly once");
        assertEquals(3, store.bucketsLoaded(DAY_TABLE).size(), "3 distinct day buckets in one load");
    }

    @Test
    void existing_day_row_is_merged_onto_and_updated_by_id() {
        FakeSummaryStore store = new FakeSummaryStore();
        CallSummary existing = daySummary(at(2026, 6, 19, 14, 30));   // one call, already persisted as id 100
        existing.setId(100L);
        store.seed(DAY_TABLE, existing.tup_starttime, existing);

        engine.runBatch(bean, List.of(daySummary(at(2026, 6, 19, 14, 30))), store);

        String update = store.firstSqlMatching("update " + DAY_TABLE);
        assertNotNull(update, "existing day row is UPDATEd, not inserted");
        assertTrue(update.contains("where id=100"), "update targets the loaded row by id");
        assertTrue(update.contains("totalcalls=2"), "1 existing + 1 new call");
        assertTrue(update.contains("customercost=2.0"), "1.0 + 1.0");
    }

    @Test
    void empty_batch_does_nothing() {
        FakeSummaryStore store = new FakeSummaryStore();

        BatchResult result = engine.runBatch(bean, List.of(), store);

        assertEquals(0, result.eventsProcessed());
        assertTrue(store.executedSql().isEmpty(), "no SQL for an empty batch");
        assertEquals(0, store.loadCount(DAY_TABLE));
    }

    @Test
    void replace_windows_is_a_prototype_that_throws_until_implemented() {
        // REPLACE (drop the window, recreate from ALL its inputs) is a settings-visible prototype only —
        // all outbox polls are INCREMENTAL (user directive 2026-07-03)
        FakeSummaryStore store = new FakeSummaryStore();

        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> engine.replaceWindows(bean, List.of(daySummary(at(2026, 6, 19, 14, 30))), store, 1000));

        assertTrue(e.getMessage().contains("not implemented"), e.getMessage());
        assertTrue(store.executedSql().isEmpty(), "the prototype must not touch the database");
    }
}
