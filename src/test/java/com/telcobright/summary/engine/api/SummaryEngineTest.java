package com.telcobright.summary.engine.api;

import com.telcobright.summary.beans.cdr.CdrVoiceSummaryBean;
import com.telcobright.summary.beans.cdr.RatedCdrEvent;
import com.telcobright.summary.engine.internal.RowFactory;
import com.telcobright.summary.engine.spi.SummaryRow;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.FakeSummaryStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.telcobright.summary.testkit.CdrTestSupport.DAY_TABLE;
import static com.telcobright.summary.testkit.CdrTestSupport.HOUR_TABLE;
import static com.telcobright.summary.testkit.CdrTestSupport.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The full load-merge-write pipeline over the fake store: load-once, fresh insert, existing-row update. */
class SummaryEngineTest {

    private final SummaryEngine engine = new SummaryEngine();
    private final CdrVoiceSummaryBean bean = CdrTestSupport.bean();

    @Test
    void fresh_batch_inserts_day_and_hour_rows() {
        FakeSummaryStore store = new FakeSummaryStore();

        BatchResult result = engine.runBatch(bean, List.of(CdrTestSupport.sg10Call(at(2026, 6, 19, 14, 30))), store);

        assertTrue(store.ranSqlMatching("insert into " + DAY_TABLE), "day row inserted");
        assertTrue(store.ranSqlMatching("insert into " + HOUR_TABLE), "hour row inserted");
        assertEquals(2, result.rowsInserted());   // one day + one hour
        assertEquals(0, result.rowsUpdated());
    }

    @Test
    void involved_windows_are_loaded_once_per_window_not_per_event() {
        FakeSummaryStore store = new FakeSummaryStore();
        // 5 calls across 3 distinct days and 4 distinct hours
        List<RatedCdrEvent> batch = List.of(
                CdrTestSupport.sg10Call(at(2026, 6, 19, 0, 30)),
                CdrTestSupport.sg10Call(at(2026, 6, 19, 14, 0)),
                CdrTestSupport.sg10Call(at(2026, 6, 20, 9, 15)),
                CdrTestSupport.sg10Call(at(2026, 6, 21, 23, 0)),
                CdrTestSupport.sg10Call(at(2026, 6, 21, 23, 45)));

        engine.runBatch(bean, batch, store);

        assertEquals(1, store.loadCount(DAY_TABLE), "day window loaded exactly once");
        assertEquals(1, store.loadCount(HOUR_TABLE), "hour window loaded exactly once");
        assertEquals(3, store.bucketsLoaded(DAY_TABLE).size(), "3 distinct day buckets in one load");
        assertEquals(4, store.bucketsLoaded(HOUR_TABLE).size(), "4 distinct hour buckets in one load");
    }

    @Test
    void existing_day_row_is_merged_onto_and_updated_by_id() {
        FakeSummaryStore store = new FakeSummaryStore();
        RatedCdrEvent call = CdrTestSupport.sg10Call(at(2026, 6, 19, 14, 30));

        // the persisted day row = exactly what this call builds, already in the DB as id 100 (one call)
        SummaryRow existingDay = RowFactory.delta(bean, bean.windows().get(0), call);
        existingDay.setId(100L);
        store.seed(DAY_TABLE, existingDay);

        engine.runBatch(bean, List.of(call), store);

        String dayUpdate = store.firstSqlMatching("update " + DAY_TABLE);
        assertNotNull(dayUpdate, "existing day row is UPDATEd, not inserted");
        assertTrue(dayUpdate.contains("where id=100"), "update targets the loaded row by id");
        assertTrue(dayUpdate.contains("totalcalls=2"), "1 existing + 1 new call");
        assertTrue(dayUpdate.contains("customercost=2.0"), "1.0 + 1.0");
        assertTrue(store.ranSqlMatching("insert into " + HOUR_TABLE), "hour had no prior row -> insert");
    }

    @Test
    void empty_batch_does_nothing() {
        FakeSummaryStore store = new FakeSummaryStore();

        BatchResult result = engine.runBatch(bean, List.of(), store);

        assertEquals(0, result.eventsProcessed());
        assertTrue(store.executedSql().isEmpty(), "no SQL for an empty batch");
        assertEquals(0, store.loadCount(DAY_TABLE), "no load for an empty batch");
    }
}
