package com.telcobright.summary.beans.cdr;

import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.testkit.CdrTestSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The faithful 1:1 port invariants of the CdrSummary entity: build, merge, the Multiply quirk, key, SQL. */
class CdrSummaryTest {

    private static final WindowSize DAILY = WindowSize.parse("daily");

    private CdrSummary call() {
        CdrBlobEntry e = CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 14, 30));
        return CdrSummaryBuilder.build(e.cdr(), e.customer(), DAILY);
    }

    @Test
    void builder_derives_the_common_counters_from_the_blob() {
        CdrSummary s = call();
        assertEquals(1, s.totalcalls);
        assertEquals(1, s.connectedcalls);          // ConnectTime present
        assertEquals(1, s.connectedcallsCC);        // NERSuccess == 1
        assertEquals(1, s.successfulcalls);         // ChargingStatus
        assertEquals(0, s.actualduration.compareTo(new BigDecimal("60")));
        assertEquals(0, s.customercost.compareTo(new BigDecimal("1.0")));
        assertEquals(0, s.tax1.compareTo(new BigDecimal("0.5")));
        assertEquals("2026-06-19T00:00", s.tup_starttime.toString());   // daily bucket
    }

    @Test
    void merge_adds_every_counter_including_connectedcallsCC() {
        CdrSummary a = call();
        a.merge(call());
        assertEquals(2, a.totalcalls);
        assertEquals(2, a.connectedcallsCC);        // summed in Merge
        assertEquals(0, a.customercost.compareTo(new BigDecimal("2.0")));
    }

    @Test
    void multiply_scales_every_counter_except_connectedcallsCC() {
        CdrSummary a = call();   // totalcalls=1, connectedcallsCC=1
        a.multiply(3);
        assertEquals(3, a.totalcalls);
        assertEquals(3, a.connectedcalls);
        assertEquals(1, a.connectedcallsCC, "legacy quirk: connectedcallsCC is NOT scaled by Multiply");
        assertEquals(0, a.customercost.compareTo(new BigDecimal("3.0")));
    }

    @Test
    void tuple_key_is_equal_for_same_dimensions_but_differs_by_destination() {
        assertEquals(call().tupleKey(), call().tupleKey());

        CdrBlobEntry other = CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 14, 30), 99);
        assertNotEquals(call().tupleKey(), CdrSummaryBuilder.build(other.cdr(), other.customer(), DAILY).tupleKey());
    }

    @Test
    void insert_values_field_count_matches_the_insert_columns() {
        int columns = CdrSummary.INSERT_COLUMNS.split(",").length;
        String tuple = call().insertValues();
        int values = tuple.substring(1, tuple.length() - 1).split(",").length;
        assertEquals(columns, values, "one value per insert column");
        assertEquals(47, columns);
    }

    @Test
    void clone_with_fake_id_copies_counters_and_nulls_the_id() {
        CdrSummary a = call();
        a.setId(100L);
        CdrSummary c = a.cloneWithFakeId();
        assertNull(c.id());
        assertEquals(a.totalcalls, c.totalcalls);
        assertEquals(a.tupleKey(), c.tupleKey());
    }

    @Test
    void update_assignments_set_counters_only() {
        String update = call().updateAssignments();
        assertTrue(update.startsWith("totalcalls=1,"));
        assertTrue(update.contains("connectedcallsCC=1"));
        assertTrue(update.contains("customercost=1.0"));
    }
}
