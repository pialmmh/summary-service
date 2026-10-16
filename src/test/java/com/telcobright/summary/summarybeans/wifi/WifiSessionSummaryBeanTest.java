package com.telcobright.summary.summarybeans.wifi;

import com.telcobright.summary.bean.spi.SqlDialect;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryTableSpec;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.summarybeans.call.CallSummaries;
import com.telcobright.summary.summarybeans.wifi.internal.WifiCauseFamily;
import com.telcobright.summary.summarybeans.wifi.model.WifiSessionSummary;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.WifiSessionTestSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static com.telcobright.summary.testkit.WifiBeansTestSupport.THE_HOUR;
import static com.telcobright.summary.testkit.WifiBeansTestSupport.a;
import static com.telcobright.summary.testkit.WifiBeansTestSupport.b;
import static com.telcobright.summary.testkit.WifiBeansTestSupport.c;
import static com.telcobright.summary.testkit.WifiBeansTestSupport.d;
import static com.telcobright.summary.testkit.WifiBeansTestSupport.dailySession;
import static com.telcobright.summary.testkit.WifiBeansTestSupport.e;
import static com.telcobright.summary.testkit.WifiBeansTestSupport.f;
import static com.telcobright.summary.testkit.WifiBeansTestSupport.hourlySession;
import static com.telcobright.summary.testkit.WifiBeansTestSupport.knownHour;
import static com.telcobright.summary.testkit.WifiBeansTestSupport.rollup;
import static com.telcobright.summary.testkit.WifiBeansTestSupport.row;
import static com.telcobright.summary.testkit.WifiBeansTestSupport.sums;
import static com.telcobright.summary.testkit.WifiSessionTestSupport.batchOf;
import static com.telcobright.summary.testkit.WifiSessionTestSupport.measuredSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SS-0006 item 2: the WiFi SESSION summed per hour / day into {@code sum_wifi_hr_101} / {@code sum_wifi_day_101} — the
 * records of service group 101, keyed by their COLUMNS (the WiFi architect's rule a), with the sessions, the connected
 * ones, the seconds, the bytes, the cost, and the DERIVED average (the architect's ruling 4); {@code ValidFlag} 0 rows
 * summed (rule b). The known hour is {@code WifiBeansTestSupport}'s.
 */
class WifiSessionSummaryBeanTest {

    @Test
    void the_session_beans_write_the_wifi_101_tables_and_read_the_calls_own_stream() {
        assertEquals("sum_wifi_day_101", dailySession().table());
        assertEquals("sum_wifi_hr_101", hourlySession().table());
        assertEquals("cdr", dailySession().entityType(), "the same outbox stream as every call bean");
        assertEquals("dailyWifiSessionSummary", dailySession().name(), "its own name: its own bookmark and worker");
    }

    @Test
    void every_key_is_read_from_a_column_of_the_record_never_its_route_or_its_meta() {
        WifiSessionSummary s = dailySession().buildBatch(batchOf(a())).get(0);

        assertEquals("mirpur-10", s.tup_site, "InTrunkAdditionalInfo — not the IncomingRoute (site-mirpur-10)");
        assertEquals("dhaka-north", s.tup_zone, "OutTrunkAdditionalInfo");
        assertEquals("paid", s.tup_granttier, "MatchedPrefixY: the grant tier's word");
        assertEquals(2, s.tup_category, "Category: its number");
        assertEquals("wifi-30", s.tup_packagecode, "MatchedPrefixCustomer");
        assertEquals("TF_min", s.tup_customercurrency, "the chargeable's unit, as the 101 call bean keeps it");
        assertEquals(WifiCauseFamily.OTHER, s.tup_causefamily, "NORMAL_CLEARING: the subscriber's own sign-out");
        assertEquals(7, s.tup_inpartnerid, "the payer");
        assertEquals(0, s.tup_switchid);
        assertEquals(LocalDateTime.of(2026, 10, 16, 0, 0), s.tup_starttime, "the day bucket");
    }

    @Test
    void one_session_is_one_delta_with_its_seconds_its_bytes_its_cost_and_its_own_average() {
        WifiSessionSummary s = hourlySession().buildBatch(batchOf(a())).get(0);

        assertEquals("1 | 1 | 1800 | 1800.000000 | 1234567 | 12345678 | 30", sums(s));
        assertEquals(THE_HOUR, s.tup_starttime, "the hour bucket");
    }

    @Test
    void a_replayed_hour_of_known_records_is_the_known_row_per_key() {
        Collection<WifiSessionSummary> rows = rollup(hourlySession(), knownHour());

        assertEquals(4, rows.size(), "the paid minutes row, the money row, the uncosted zero-second row, the free row of the other site");
        assertEquals("3 | 3 | 2700 | 900.000000 | 1235567 | 12347678 | 45", sums(row(rows, "mirpur-10", "TF_min", "other")), "A + B + E");
        assertEquals("1 | 1 | 900 | 900.000000 | 500 | 7000 | 3.00", sums(row(rows, "mirpur-10", "BDT", "idle")), "C");
        assertEquals("1 | 0 | 0 | 0 | 0 | 0 | 0", sums(row(rows, "mirpur-10", "", "other")), "D: counted, not connected, 0 to every sum");
        assertEquals("1 | 1 | 900 | 900.000000 | 100 | 200 | 0", sums(row(rows, "uttara-7", "TF_min", "volume")), "F: free WiFi too");
        assertTrue(rows.stream().allMatch(r -> r.tup_starttime.equals(THE_HOUR)), "one hour");
    }

    @Test
    void the_day_table_holds_the_same_four_rows_under_the_day_bucket() {
        Collection<WifiSessionSummary> rows = rollup(dailySession(), knownHour());

        assertEquals(4, rows.size());
        assertEquals("3 | 3 | 2700 | 900.000000 | 1235567 | 12347678 | 45", sums(row(rows, "mirpur-10", "TF_min", "other")));
        assertTrue(rows.stream().allMatch(r -> r.tup_starttime.equals(LocalDateTime.of(2026, 10, 16, 0, 0))));
    }

    @Test
    void one_record_dropped_from_the_replay_changes_every_sum_of_its_row() {
        WifiSessionSummary whole = row(rollup(hourlySession(), List.of(a(), b(), e())), "mirpur-10", "TF_min", "other");
        WifiSessionSummary dropped = row(rollup(hourlySession(), List.of(a(), e())), "mirpur-10", "TF_min", "other");

        assertNotEquals(whole.sessions, dropped.sessions);
        assertNotEquals(whole.sessions_connected, dropped.sessions_connected);
        assertNotEquals(whole.total_duration_sec, dropped.total_duration_sec);
        assertNotEquals(whole.bytes_up, dropped.bytes_up);
        assertNotEquals(whole.bytes_dn, dropped.bytes_dn);
        assertNotEquals(0, whole.customercost.compareTo(dropped.customercost));
        assertNotEquals(0, whole.avg_session_sec.compareTo(dropped.avg_session_sec));
        assertEquals("2 | 2 | 2100 | 1050.000000 | 1234567 | 12345678 | 35", sums(dropped));
    }

    @Test
    void a_second_session_in_the_bucket_makes_the_average_total_over_connected_never_the_first_average_plus_the_second() {
        WifiSessionSummary first = hourlySession().buildBatch(batchOf(a())).get(0);
        WifiSessionSummary second = hourlySession().buildBatch(batchOf(b())).get(0);
        assertEquals(0, new BigDecimal("1800").compareTo(first.avg_session_sec));
        assertEquals(0, new BigDecimal("600").compareTo(second.avg_session_sec));

        WifiSessionSummary merged = row(rollup(hourlySession(), List.of(a(), b())), "mirpur-10", "TF_min", "other");

        assertEquals(0, new BigDecimal("1200").compareTo(merged.avg_session_sec), "2400 / 2 — not 1800 + 600");
        assertEquals(2400, merged.total_duration_sec);
        assertEquals(2, merged.sessions_connected);
    }

    @Test
    void a_session_of_zero_seconds_counts_in_sessions_not_in_connected_and_adds_zero_to_every_sum() {
        WifiSessionSummary s = hourlySession().buildBatch(batchOf(d())).get(0);

        assertEquals("1 | 0 | 0 | 0 | 0 | 0 | 0", sums(s));
        assertEquals("", s.tup_customercurrency, "no unit stamped: behind the voice's early return, as the 101 call bean has it");
        // and it keys beside the costed row of the same place, never into it
        Collection<WifiSessionSummary> rows = rollup(hourlySession(), List.of(a(), d()));
        assertEquals(2, rows.size());
        assertEquals("1 | 1 | 1800 | 1800.000000 | 1234567 | 12345678 | 30", sums(row(rows, "mirpur-10", "TF_min", "other")));
    }

    @Test
    void minutes_and_money_key_apart_and_are_never_summed_into_one_row() {
        Collection<WifiSessionSummary> rows = rollup(dailySession(), List.of(a(), c().cause("NORMAL_CLEARING")));

        assertEquals(2, rows.size(), "the unit is a key column");
        assertEquals(0, new BigDecimal("30").compareTo(row(rows, "mirpur-10", "TF_min", "other").customercost), "30 minutes");
        assertEquals(0, new BigDecimal("3.00").compareTo(row(rows, "mirpur-10", "BDT", "other").customercost), "3.00 taka — never added to the minutes");
    }

    @Test
    void a_record_whose_valid_flag_is_zero_is_summed_all_the_same() {
        // rule b: the unmeasured bytes are no measurement, but the session is a session — a report filters on the flag
        Collection<WifiSessionSummary> rows = rollup(hourlySession(), List.of(a(), e()));

        assertEquals(1, rows.size(), "the same key");
        assertEquals("2 | 2 | 2100 | 1050.000000 | 1234567 | 12345678 | 35", sums(rows.iterator().next()));
    }

    @Test
    void the_cause_family_keys_the_rows_apart_by_the_records_hangup_cause() {
        Collection<WifiSessionSummary> rows = rollup(hourlySession(), List.of(a(), a().cause("MEDIA_TIMEOUT"), a().cause("VOLUME_EXHAUSTED"),
                a().cause("deauth:user"), a().cause("BALANCE_EXHAUSTED"), a().cause("SYSTEM_SHUTDOWN"), a().cause("PACKAGE_EXPIRED")));

        assertEquals(7, rows.size());
        for (String family : List.of("other", "idle", "volume", "deauth", "balance", "shutdown", "time")) {
            assertEquals(1, row(rows, "mirpur-10", "TF_min", family).sessions, family);
        }
    }

    @Test
    void a_view_and_a_voice_call_in_the_same_batch_make_no_row_and_the_session_makes_none_in_their_beans() {
        String voice10 = CdrTestSupport.entryJson(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 10, 16, 9, 0)));
        String view30 = AdTestSupport.leafView(AdTestSupport.at(2026, 10, 16, 9, 0)).json();
        byte[] mixed = AdTestSupport.batchJson(List.of(voice10, view30, a().json()));
        SummaryBean<?> daily30 = CallSummaries.forWindow("dailyCallSummarySg30", "daily", "30", 30, null);

        assertEquals(1, dailySession().buildBatch(mixed).size(), "the one session");
        assertEquals("mirpur-10", dailySession().buildBatch(mixed).get(0).tup_site);
        assertEquals(1, daily30.buildBatch(mixed).size(), "the group-30 call bean: its one view");
        assertEquals(1, CdrTestSupport.dailyBean().buildBatch(mixed).size(), "the group-10 call bean: its one call");
        assertEquals(1, AdTestSupport.dailyBean().buildBatch(mixed, "btcl").size(), "the ad bean: its one view");
        assertEquals(0, dailySession().buildBatch(AdTestSupport.batchJson(List.of(voice10, view30))).size(), "no session: no row");
    }

    @Test
    void a_record_of_bc_0007s_shape_without_the_columns_is_summed_under_empty_keys() {
        // the 40 dead-lettered records and B1's proof row carry no WiFi columns: they still count, keyed as unknown
        WifiSessionSummary s = dailySession().buildBatch(batchOf(WifiSessionTestSupport.session(THE_HOUR))).get(0);

        assertEquals("", s.tup_site);
        assertEquals("", s.tup_zone);
        assertEquals("", s.tup_granttier);
        assertEquals(0, s.tup_category);
        assertEquals("", s.tup_packagecode);
        assertEquals("1 | 1 | 1800 | 1800.000000 | 0 | 0 | 30", sums(s), "no bytes on the record: 0");
    }

    @Test
    void a_key_text_wider_than_its_column_is_cut_before_the_key_is_taken() {
        String wide = "s".repeat(70);
        WifiSessionSummary s = dailySession().buildBatch(batchOf(measuredSession(THE_HOUR).site(wide).zone(wide).packageCode(wide).grantTier("a".repeat(20), 2))).get(0);

        assertEquals(64, s.tup_site.length());
        assertEquals(64, s.tup_zone.length());
        assertEquals(64, s.tup_packagecode.length());
        assertEquals(16, s.tup_granttier.length());
    }

    // ---- the table, described once for both engines ----

    @Test
    void the_table_is_described_with_bigint_sums_a_decimal_cost_and_average_the_bucket_in_the_key_and_the_two_indexes() {
        SummaryTableSpec spec = dailySession().tableSpec();

        assertEquals("sum_wifi_day_101", spec.name());
        assertEquals(List.of("id", "tup_switchid", "tup_inpartnerid", "tup_gw", "tup_site", "tup_zone", "tup_granttier", "tup_category", "tup_packagecode",
                "tup_customercurrency", "tup_causefamily", "tup_starttime", "sessions", "sessions_connected", "total_duration_sec", "avg_session_sec",
                "bytes_up", "bytes_dn", "customercost"), spec.columnNames(), "id, then the INSERT columns in their order");
        assertEquals("id," + WifiSessionSummary.INSERT_COLUMNS, String.join(",", spec.columnNames()));
        assertEquals(List.of("id", "tup_starttime"), spec.primaryKey(), "the partition column is in every unique key");
        assertEquals(List.of("ix_starttime", "ix_site_starttime"), spec.indexes().stream().map(SummaryTableSpec.Index::name).toList());
        assertEquals(List.of("tup_site", "tup_starttime"), spec.indexes().get(1).columns());
        assertEquals("tup_starttime", spec.partitionColumn());
        assertTrue(spec.keptUpToDate(), "net-new, the service's own: an older table is brought up to this");
        for (String sum : List.of("sessions", "sessions_connected", "total_duration_sec", "bytes_up", "bytes_dn")) {
            assertEquals(SummaryTableSpec.Type.BIGINT, columnOf(spec, sum).type(), sum);
        }
        assertEquals("DECIMAL 18,6", typeOf(columnOf(spec, "customercost")));
        assertEquals("DECIMAL 18,6", typeOf(columnOf(spec, "avg_session_sec")));
        assertEquals(64, columnOf(spec, "tup_site").width());
    }

    @Test
    void the_ddl_is_one_partitioned_create_on_mysql_and_a_plain_table_with_its_two_indexes_on_postgresql() {
        System.setProperty("summary.ddl.partition-start", "2026-10-01");
        System.setProperty("summary.ddl.partition-days", "3");
        try {
            List<String> mysql = hourlySession().tableDdl(SqlDialect.MYSQL);
            List<String> postgres = hourlySession().tableDdl(SqlDialect.POSTGRESQL);

            assertEquals(1, mysql.size(), "ONE statement: the table with its full partition set inside");
            assertTrue(mysql.get(0).startsWith("CREATE TABLE IF NOT EXISTS sum_wifi_hr_101 ("), mysql.get(0));
            assertTrue(mysql.get(0).contains("PRIMARY KEY (id, tup_starttime)"));
            assertTrue(mysql.get(0).contains("KEY ix_site_starttime (tup_site, tup_starttime)"));
            assertTrue(mysql.get(0).contains("PARTITION BY RANGE COLUMNS(tup_starttime)") && mysql.get(0).contains("PARTITION p20261001 "));
            assertTrue(mysql.get(0).contains("avg_session_sec DECIMAL(18,6) NOT NULL DEFAULT 0"));
            assertEquals(3, postgres.size(), "the table, then its two indexes");
            assertTrue(postgres.get(0).startsWith("CREATE TABLE IF NOT EXISTS sum_wifi_hr_101 (id BIGINT GENERATED BY DEFAULT AS IDENTITY"), postgres.get(0));
            assertTrue(postgres.get(0).contains("tup_starttime TIMESTAMP NOT NULL") && !postgres.get(0).contains("PARTITION"));
            assertEquals("CREATE INDEX IF NOT EXISTS sum_wifi_hr_101_ix_site_starttime ON sum_wifi_hr_101 (tup_site, tup_starttime)", postgres.get(2));
        } finally {
            System.clearProperty("summary.ddl.partition-start");
            System.clearProperty("summary.ddl.partition-days");
        }
    }

    private static SummaryTableSpec.Column columnOf(SummaryTableSpec spec, String name) {
        return spec.columns().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    private static String typeOf(SummaryTableSpec.Column column) {
        return column.type() + " " + column.width() + "," + column.scale();
    }
}
