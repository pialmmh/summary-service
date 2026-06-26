package com.telcobright.summary.engine.internal;

import com.telcobright.summary.bean.spi.WindowDef;
import com.telcobright.summary.beans.cdr.CdrVoiceSummaryBean;
import com.telcobright.summary.beans.cdr.RatedCdrEvent;
import com.telcobright.summary.engine.spi.MergeMode;
import com.telcobright.summary.engine.spi.SummaryRow;
import com.telcobright.summary.engine.spi.WindowSchema;
import com.telcobright.summary.testkit.CdrTestSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The cache merge math: increment, decrement, overwrite, insert-vs-update tracking, and the redelivery story. */
class SummaryCacheTest {

    private final CdrVoiceSummaryBean bean = CdrTestSupport.bean();
    private final WindowDef dayWindow = bean.windows().get(0);
    private final WindowSchema schema = WindowSchemaFactory.build(bean, dayWindow);
    private final RatedCdrEvent call = CdrTestSupport.sg10Call(CdrTestSupport.at(2026, 6, 19, 14, 30));

    private SummaryRow delta() {
        return RowFactory.delta(bean, dayWindow, call);
    }

    private static BigDecimal counter(SummaryCache cache, String column) {
        return cache.rows().iterator().next().counter(column);
    }

    @Test
    void add_into_a_new_window_inserts_and_accumulates() {
        SummaryCache cache = new SummaryCache(schema);

        cache.merge(delta(), MergeMode.ADD);
        cache.merge(delta(), MergeMode.ADD);   // same tuple again

        assertEquals(1, cache.insertedCount(), "still ONE insert (a row inserted this batch stays an insert)");
        assertEquals(0, cache.updatedCount());
        assertEquals(0, counter(cache, "totalcalls").compareTo(new BigDecimal("2")));
        assertEquals(0, counter(cache, "customercost").compareTo(new BigDecimal("2.0")));
    }

    @Test
    void add_onto_a_loaded_window_updates_it() {
        SummaryCache cache = new SummaryCache(schema);
        SummaryRow existing = delta();
        existing.setId(100L);
        cache.populateExisting(existing);

        cache.merge(delta(), MergeMode.ADD);

        assertEquals(0, cache.insertedCount());
        assertEquals(1, cache.updatedCount(), "a LOADED row becomes an update");
        assertEquals(0, counter(cache, "totalcalls").compareTo(new BigDecimal("2")));
    }

    @Test
    void subtract_decrements_a_loaded_window() {
        SummaryCache cache = new SummaryCache(schema);
        SummaryRow existing = delta();
        for (int i = 0; i < 4; i++) {
            existing.mergeAdd(delta());          // existing totalcalls = 5
        }
        existing.setId(100L);
        cache.populateExisting(existing);

        cache.merge(delta(), MergeMode.SUBTRACT); // -1 call

        assertEquals(0, counter(cache, "totalcalls").compareTo(new BigDecimal("4")));
        assertEquals(0, counter(cache, "customercost").compareTo(new BigDecimal("4.0")));
    }

    @Test
    void subtract_on_a_window_that_was_not_loaded_is_rejected() {
        SummaryCache cache = new SummaryCache(schema);

        assertThrows(IllegalStateException.class, () -> cache.merge(delta(), MergeMode.SUBTRACT));
    }

    @Test
    void overwrite_replaces_counters_for_the_correction_path() {
        SummaryCache cache = new SummaryCache(schema);
        SummaryRow existing = delta();
        for (int i = 0; i < 9; i++) {
            existing.mergeAdd(delta());          // existing totalcalls = 10 (a wrong/stale window)
        }
        existing.setId(100L);
        cache.populateExisting(existing);

        cache.merge(delta(), MergeMode.OVERWRITE); // recomputed truth = 1 call

        assertEquals(0, counter(cache, "totalcalls").compareTo(BigDecimal.ONE), "counters replaced, not added");
    }

    @Test
    void redelivery_double_counts_and_overwrite_repairs_it() {
        // batch 1 committed a window of 1 call
        SummaryRow afterBatch1 = delta();
        afterBatch1.setId(100L);

        // redelivery: the same batch loads that row and ADDs again -> 2 (increment is NOT idempotent)
        SummaryCache redelivered = new SummaryCache(schema);
        redelivered.populateExisting(afterBatch1);
        redelivered.merge(delta(), MergeMode.ADD);
        assertEquals(0, counter(redelivered, "totalcalls").compareTo(new BigDecimal("2")), "double-counted");

        // correction recomputes the window from source-of-truth (1 call) and OVERWRITES -> repaired
        SummaryRow doubled = redelivered.rows().iterator().next();
        SummaryCache correction = new SummaryCache(schema);
        correction.populateExisting(doubled);
        correction.merge(delta(), MergeMode.OVERWRITE);
        assertEquals(0, counter(correction, "totalcalls").compareTo(BigDecimal.ONE), "correction repaired the count");
    }
}
