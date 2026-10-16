package com.telcobright.summary.summarybeans.wifi;

import com.telcobright.summary.bean.spi.SqlDialect;
import com.telcobright.summary.bean.spi.SummaryTableSpec;
import com.telcobright.summary.bean.spi.TableDdl;
import com.telcobright.summary.summarybeans.wifi.model.WifiSessionSummary;
import com.telcobright.summary.testkit.WifiFlowTestSupport;
import com.telcobright.summary.testkit.WifiSessionTestSupport;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.telcobright.summary.testkit.WifiBeansTestSupport.THE_HOUR;
import static com.telcobright.summary.testkit.WifiBeansTestSupport.a;
import static com.telcobright.summary.testkit.WifiBeansTestSupport.b;
import static com.telcobright.summary.testkit.WifiBeansTestSupport.dailySession;
import static com.telcobright.summary.testkit.WifiBeansTestSupport.hourlySession;
import static com.telcobright.summary.testkit.WifiBeansTestSupport.rollup;
import static com.telcobright.summary.testkit.WifiBeansTestSupport.sums;
import static com.telcobright.summary.testkit.WifiSessionTestSupport.batchOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SS-0007 item 2: the WiFi SESSION sums split by BRAS (the owner's R4, "counters and all logs must include bras id") —
 * {@code tup_gw} = the record's {@code InMgwId} (the meta {@code gw}, billing-core's {@code WifiColumns.java:84}), cut to
 * 64 as the destination sums' {@code tup_gw}; {@code ''} for a record that names none, and for every row summed before
 * this version (the column's default, given to an older table at its first use).
 */
class WifiSessionSummaryByBrasTest {

    @Test
    void two_sessions_of_one_site_on_two_bras_are_two_rows() {
        Collection<WifiSessionSummary> rows = rollup(hourlySession(), List.of(a().gateway("bras-1"), b().gateway("bras-2")));

        assertEquals(2, rows.size(), "one site, one key but the BRAS: a row per BRAS");
        assertEquals("1 | 1 | 1800 | 1800.000000 | 1234567 | 12345678 | 30", sums(onBras(rows, "bras-1")));
        assertEquals("1 | 1 | 600 | 600.000000 | 1000 | 2000 | 10", sums(onBras(rows, "bras-2")));
    }

    @Test
    void two_sessions_of_one_site_on_one_bras_are_one_row() {
        Collection<WifiSessionSummary> rows = rollup(hourlySession(), List.of(a().gateway("bras-1"), b().gateway("bras-1")));

        assertEquals(1, rows.size());
        assertEquals("2 | 2 | 2400 | 1200.000000 | 1235567 | 12347678 | 40", sums(onBras(rows, "bras-1")));
    }

    @Test
    void a_record_that_names_no_bras_keys_under_the_empty_bras_beside_the_bras_rows() {
        WifiSessionSummary noGwInTheMeta = hourlySession().buildBatch(batchOf(a().gateway(null))).get(0);
        WifiSessionSummary bc0007 = hourlySession().buildBatch(batchOf(WifiSessionTestSupport.session(THE_HOUR))).get(0);

        assertEquals("", noGwInTheMeta.tup_gw, "a BC-0008 record whose meta named no gw");
        assertEquals("", bc0007.tup_gw, "a record of BC-0007's shape, no WiFi column at all");
        Collection<WifiSessionSummary> rows = rollup(hourlySession(), List.of(a().gateway(null), b().gateway(null), a().gateway("bras-2")));
        assertEquals(2, rows.size(), "the two with no BRAS in one row, never into the BRAS's row");
        assertEquals(2, onBras(rows, "").sessions);
        assertEquals(1, onBras(rows, "bras-2").sessions);
    }

    @Test
    void the_bras_is_read_from_InMgwId_never_the_route_the_site_or_the_session_key() {
        // the record's IncomingRoute is site-mirpur-10, its site column mirpur-10, its session key carries no BRAS
        WifiSessionSummary s = hourlySession().buildBatch(batchOf(a().gateway("bras-7"))).get(0);

        assertEquals("bras-7", s.tup_gw);
        assertEquals("mirpur-10", s.tup_site, "and the site stays the site");
    }

    @Test
    void a_bras_id_is_cut_to_its_column_and_trimmed_as_every_key_text_before_the_key_is_taken() {
        String wide = "b".repeat(70);
        Collection<WifiSessionSummary> cut = rollup(hourlySession(), List.of(a().gateway(wide), b().gateway("b".repeat(64) + "-other")));
        Collection<WifiSessionSummary> trimmed = rollup(hourlySession(), List.of(a().gateway(" bras-1 "), b().gateway("bras-1")));

        assertEquals(1, cut.size(), "both cut to the same 64 characters: one row — what is built keys as its reloaded row");
        assertEquals("b".repeat(64), cut.iterator().next().tup_gw);
        assertEquals(1, trimmed.size());
        assertEquals("bras-1", trimmed.iterator().next().tup_gw);
    }

    @Test
    void the_bras_stands_after_the_payer_in_the_key_the_clone_and_the_insert_values() {
        WifiSessionSummary one = hourlySession().buildBatch(batchOf(a().gateway("bras-1"))).get(0);
        WifiSessionSummary two = hourlySession().buildBatch(batchOf(a().gateway("bras-2"))).get(0);
        WifiSessionSummary clone = one.cloneWithFakeId();

        assertNotEquals(one.tupleKey(), two.tupleKey(), "the BRAS keys apart");
        assertEquals("bras-1", one.tupleKey().tokens().get(2));
        assertEquals("bras-1", clone.tup_gw, "a fresh INSERT candidate keeps its BRAS");
        assertEquals(one.tupleKey(), clone.tupleKey());
        assertTrue(one.insertValues().startsWith("(0,7,'bras-1','mirpur-10',"), one.insertValues());
        assertTrue(WifiSessionSummary.INSERT_COLUMNS.startsWith("tup_switchid,tup_inpartnerid,tup_gw,tup_site,"), WifiSessionSummary.INSERT_COLUMNS);
    }

    // ---- the table: a 64-wide dimension with its default; an older table is brought up by ONE statement ----

    @Test
    void the_table_names_tup_gw_a_64_wide_dimension_with_the_default_empty_as_the_destination_sums_do() {
        SummaryTableSpec spec = dailySession().tableSpec();
        SummaryTableSpec.Column gw = column(spec, "tup_gw");

        assertEquals(SummaryTableSpec.Type.VARCHAR, gw.type());
        assertEquals(64, gw.width());
        assertEquals("''", gw.defaultLiteral(), "a default: the column can be given to a table that holds rows");
        assertEquals(column(WifiFlowTestSupport.daily().tableSpec(), "tup_gw").width(), gw.width(), "the destination sums' width: one number");
        assertTrue(spec.indexes().stream().noneMatch(index -> index.columns().contains("tup_gw")), "no index names it");
    }

    @Test
    void a_table_made_by_3829e18_is_brought_up_by_one_add_column_with_its_default_and_the_second_time_by_none() {
        Map<String, Integer> madeBy3829e18 = theColumnsOf3829e18();

        assertEquals(List.of("ALTER TABLE sum_wifi_day_101 ADD COLUMN IF NOT EXISTS tup_gw VARCHAR(64) NOT NULL DEFAULT ''"),
                TableDdl.bringUpToDate(dailySession().tableSpec(), SqlDialect.POSTGRESQL, madeBy3829e18));
        assertEquals(List.of("ALTER TABLE sum_wifi_hr_101 ADD COLUMN tup_gw VARCHAR(64) NOT NULL DEFAULT ''"),
                TableDdl.bringUpToDate(hourlySession().tableSpec(), SqlDialect.MYSQL, madeBy3829e18));
        Map<String, Integer> afterTheFirstUse = new LinkedHashMap<>(madeBy3829e18);
        afterTheFirstUse.put("tup_gw", 64);
        for (SqlDialect engine : SqlDialect.values()) {
            assertEquals(List.of(), TableDdl.bringUpToDate(dailySession().tableSpec(), engine, afterTheFirstUse), engine + ": the second start has nothing to do");
        }
    }

    /** The catalog of sum_wifi_*_101 as 3829e18 (SS-0006) made it — name → a text column's width, or -1. */
    private static Map<String, Integer> theColumnsOf3829e18() {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (String name : List.of("id", "tup_switchid", "tup_inpartnerid")) columns.put(name, -1);
        columns.put("tup_site", 64);
        columns.put("tup_zone", 64);
        columns.put("tup_granttier", 16);
        columns.put("tup_category", -1);
        columns.put("tup_packagecode", 64);
        columns.put("tup_customercurrency", 16);
        columns.put("tup_causefamily", 16);
        for (String name : List.of("tup_starttime", "sessions", "sessions_connected", "total_duration_sec", "avg_session_sec", "bytes_up", "bytes_dn", "customercost")) {
            columns.put(name, -1);
        }
        return columns;
    }

    private static WifiSessionSummary onBras(Collection<WifiSessionSummary> rows, String gw) {
        List<WifiSessionSummary> found = rows.stream().filter(r -> r.tup_gw.equals(gw)).toList();
        if (found.size() != 1) throw new AssertionError("BRAS '" + gw + "' has " + found.size() + " row(s), not one: " + rows.size() + " row(s) in all");
        return found.get(0);
    }

    private static SummaryTableSpec.Column column(SummaryTableSpec spec, String name) {
        return spec.columns().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError(spec.name() + " has no " + name));
    }
}
