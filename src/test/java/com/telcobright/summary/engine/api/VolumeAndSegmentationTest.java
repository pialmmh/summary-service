package com.telcobright.summary.engine.api;

import com.telcobright.summary.summarybeans.call.model.CallSummary;
import com.telcobright.summary.summarybeans.call.internal.CallSummaryBean;
import com.telcobright.summary.summarybeans.call.model.CdrBlobEntry;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.FakeSummaryStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.telcobright.summary.testkit.CdrTestSupport.DAY_TABLE;
import static com.telcobright.summary.testkit.CdrTestSupport.at;
import static com.telcobright.summary.testkit.CdrTestSupport.batchJson;
import static com.telcobright.summary.testkit.CdrTestSupport.dailyBean;
import static com.telcobright.summary.testkit.CdrTestSupport.daySummary;
import static com.telcobright.summary.testkit.CdrTestSupport.distinctKeysAt;
import static com.telcobright.summary.testkit.CdrTestSupport.series;
import static com.telcobright.summary.testkit.CdrTestSupport.sg10Entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Volume + segmentation: a big batch must split into bounded multi-row INSERT segments, many distinct windows
 * must still load in ONE query, and a batch mixing existing + new windows must UPDATE the loaded rows by id and
 * INSERT the rest — all proven against the in-memory store by counting the SQL the engine emits.
 */
class VolumeAndSegmentationTest {

    private final SummaryEngine engine = new SummaryEngine();
    private final CallSummaryBean bean = dailyBean();

    @Test
    void large_batch_splits_inserts_at_the_segment_boundary() {
        FakeSummaryStore store = new FakeSummaryStore();
        // 2500 distinct-key calls in ONE day window -> 2500 inserts
        List<CallSummary> entities = bean.buildBatch(batchJson(distinctKeysAt(at(2026, 6, 19, 10, 0), 2500)));

        BatchResult result = engine.runBatch(bean, entities, store, 1000);

        assertEquals(2500, result.rowsInserted());
        long insertSegments = store.executedSql().stream()
                .filter(s -> s.startsWith("insert into " + DAY_TABLE)).count();
        assertEquals(3, insertSegments, "2500 rows / 1000 per segment = 3 multi-row INSERTs");
        assertEquals(1, store.loadCount(DAY_TABLE), "the involved window is loaded exactly once");
    }

    @Test
    void forty_distinct_day_windows_load_in_a_single_query() {
        FakeSummaryStore store = new FakeSummaryStore();
        // one call per day for 40 days (step 1440 min = 1 day)
        List<CallSummary> entities = bean.buildBatch(batchJson(series(at(2026, 1, 1, 0, 0), 1440, 40)));

        BatchResult result = engine.runBatch(bean, entities, store, 1000);

        assertEquals(1, store.loadCount(DAY_TABLE), "all 40 windows loaded once, not per event");
        assertEquals(40, store.bucketsLoaded(DAY_TABLE).size(), "40 distinct day buckets in that one load");
        assertEquals(40, result.rowsInserted());
    }

    @Test
    void batch_updates_existing_windows_and_inserts_new_ones() {
        FakeSummaryStore store = new FakeSummaryStore();
        CallSummary day19 = daySummary(at(2026, 6, 19, 8, 0));
        day19.setId(100L);
        CallSummary day20 = daySummary(at(2026, 6, 20, 8, 0));
        day20.setId(101L);
        store.seed(DAY_TABLE, day19.tup_starttime, day19);
        store.seed(DAY_TABLE, day20.tup_starttime, day20);

        // same dimensions as the seeded rows for 19/20 (so they merge -> UPDATE); 21/22/23 are new -> INSERT
        List<CdrBlobEntry> calls = List.of(
                sg10Entry(at(2026, 6, 19, 9, 0)), sg10Entry(at(2026, 6, 20, 9, 0)),
                sg10Entry(at(2026, 6, 21, 9, 0)), sg10Entry(at(2026, 6, 22, 9, 0)), sg10Entry(at(2026, 6, 23, 9, 0)));

        BatchResult result = engine.runBatch(bean, bean.buildBatch(batchJson(calls)), store, 1000);

        assertEquals(3, result.rowsInserted(), "three new day windows inserted");
        assertEquals(2, result.rowsUpdated(), "two existing day windows updated");
        String update = store.firstSqlMatching("update " + DAY_TABLE);
        assertNotNull(update, "existing windows are UPDATEd");
        assertTrue(update.contains("where id=100") && update.contains("where id=101"), "updates target loaded ids");
        assertTrue(update.contains("totalcalls=2"), "1 existing + 1 new call per existing window");
    }
}
