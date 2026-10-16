package com.telcobright.summary.summarybeans.wifi;

import com.telcobright.summary.bean.spi.SqlDialect;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryTableSpec;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.outbox.api.OutboxReader;
import com.telcobright.summary.summarybeans.wifi.model.WifiDestinationSummary;
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory;
import com.telcobright.summary.testkit.WifiBeansTestSupport;
import com.telcobright.summary.testkit.WifiFlowTestSupport;
import com.telcobright.summary.testkit.WifiFlowTestSupport.Flow;
import com.telcobright.summary.testkit.WifiSessionTestSupport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.telcobright.summary.testkit.WifiFlowTestSupport.THE_HOUR_IN_DHAKA;
import static com.telcobright.summary.testkit.WifiFlowTestSupport.W1;
import static com.telcobright.summary.testkit.WifiFlowTestSupport.batchOf;
import static com.telcobright.summary.testkit.WifiFlowTestSupport.daily;
import static com.telcobright.summary.testkit.WifiFlowTestSupport.flow;
import static com.telcobright.summary.testkit.WifiFlowTestSupport.hourly;
import static com.telcobright.summary.testkit.WifiFlowTestSupport.knownHour;
import static com.telcobright.summary.testkit.WifiFlowTestSupport.rollup;
import static com.telcobright.summary.testkit.WifiFlowTestSupport.row;
import static com.telcobright.summary.testkit.WifiFlowTestSupport.sums;
import static com.telcobright.summary.testkit.WifiFlowTestSupport.window1;
import static com.telcobright.summary.testkit.WifiFlowTestSupport.window2;
import static com.telcobright.summary.testkit.WifiFlowTestSupport.window3;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SS-0006 item 3: the volume per DESTINATION per hour / day into {@code sum_wifi_dst_hr} / {@code sum_wifi_dst_day} —
 * the {@code wifi_flow} entity's blobs (a JSON array of {@code WifiFlowOutboxRow}), keyed by gateway, site, zone, remote,
 * protocol and port, the window's start put on the root tenant's zone (ruling 7), no money and no distinct devices
 * (rulings 5, 8). The known hour is {@code WifiFlowTestSupport}'s.
 */
class WifiDestinationSummaryBeanTest {

    @Test
    void the_destination_beans_read_the_wifi_flow_entity_and_write_the_dst_tables() {
        assertEquals("wifi_flow", daily().entityType(), "billing-core's WifiFlowOutbox.EntityType");
        assertEquals("sum_wifi_dst_day", daily().table());
        assertEquals("sum_wifi_dst_hr", hourly().table());
        assertEquals("dailyWifiDstSummary", daily().name(), "its own name: its own bookmark and worker");
        assertEquals("cdr", WifiBeansTestSupport.dailySession().entityType(), "the session beans stay on the call's stream");
    }

    @Test
    void one_flow_row_is_one_delta_keyed_by_gateway_site_zone_remote_proto_and_port_on_the_tenants_hour() {
        WifiDestinationSummary s = hourly().buildBatch(batchOf(List.of(flow(W1))), "btcl").get(0);

        assertEquals("bras-2", s.tup_gw, "the gateway, where the switch id would stand (ruling 5)");
        assertEquals("mirpur-10", s.tup_site);
        assertEquals("dhaka-north", s.tup_zone);
        assertEquals("142.250.74.78", s.tup_remote);
        assertEquals(6, s.tup_proto);
        assertEquals(443, s.tup_port);
        assertEquals(THE_HOUR_IN_DHAKA, s.tup_starttime, "09:41Z is 15:41 in Dhaka: the hour 15");
        assertEquals("1 | 15200 | 410300 | 120 | 340", sums(s));
    }

    @Test
    void a_replayed_hour_of_known_windows_is_the_known_rows_per_gw_site_zone_remote_proto_port() {
        Collection<WifiDestinationSummary> rows = rollup(hourly(), window1(), window2(), window3());

        assertEquals(3, rows.size());
        assertEquals("4 | 19300 | 502400 | 161 | 421", sums(row(rows, "mirpur-10", "142.250.74.78", 443, 6)), "r1 + r2 + r4 + r6: two devices, one destination");
        assertEquals("1 | 300 | 700 | 5 | 5", sums(row(rows, "mirpur-10", "1.1.1.1", 53, 17)), "r3");
        assertEquals("1 | 100 | 200 | 2 | 2", sums(row(rows, "uttara-7", "1.1.1.1", 53, 17)), "r5: the other site keys apart");
        assertTrue(rows.stream().allMatch(r -> r.tup_starttime.equals(THE_HOUR_IN_DHAKA) && r.tup_gw.equals("bras-2") && r.tup_zone.equals("dhaka-north")));
    }

    @Test
    void the_day_table_holds_the_same_three_rows_under_the_tenants_day() {
        Collection<WifiDestinationSummary> rows = rollup(daily(), knownHour());

        assertEquals(3, rows.size());
        assertEquals("4 | 19300 | 502400 | 161 | 421", sums(row(rows, "mirpur-10", "142.250.74.78", 443, 6)));
        assertTrue(rows.stream().allMatch(r -> r.tup_starttime.equals(LocalDateTime.of(2026, 10, 16, 0, 0))));
    }

    @Test
    void one_windows_rows_dropped_from_the_replay_change_the_bytes() {
        WifiDestinationSummary whole = row(rollup(hourly(), window1(), window2(), window3()), "mirpur-10", "142.250.74.78", 443, 6);
        WifiDestinationSummary without2 = row(rollup(hourly(), window1(), window3()), "mirpur-10", "142.250.74.78", 443, 6);

        assertNotEquals(whole.bytes_up, without2.bytes_up);
        assertNotEquals(whole.bytes_dn, without2.bytes_dn);
        assertNotEquals(whole.packets_up, without2.packets_up);
        assertNotEquals(whole.flows, without2.flows);
        assertEquals("3 | 16300 | 412400 | 131 | 361", sums(without2), "r1 + r2 + r6");
    }

    @Test
    void two_outbox_rows_of_one_part_the_5000_split_sum_exactly_as_one() {
        // billing-core splits a part's rows at 5 000 per outbox row (WifiFlowOutbox.RowsPerOutboxRow): the sums must not see the cut
        List<Flow> w1 = window1();
        Collection<WifiDestinationSummary> asOne = rollup(hourly(), w1);
        Collection<WifiDestinationSummary> asTwo = rollup(hourly(), w1.subList(0, 2), w1.subList(2, 3));

        assertEquals(asOne.size(), asTwo.size());
        for (WifiDestinationSummary one : asOne) {
            assertEquals(sums(one), sums(row(asTwo, one.tup_site, one.tup_remote, one.tup_port, one.tup_proto)), one.tupleKey().toString());
        }
    }

    @Test
    void the_windows_start_is_put_on_the_root_tenants_zone_before_it_is_cut_never_left_in_utc() {
        // 18:30Z on the 16th is 00:30 on the 17th in Dhaka: the day is the 17th, the hour is 00
        List<Flow> lateInUtc = List.of(flow("2026-10-16T18:30:00Z"));

        assertEquals(LocalDateTime.of(2026, 10, 17, 0, 0), daily().buildBatch(batchOf(lateInUtc), "btcl").get(0).tup_starttime, "Dhaka's 17th");
        assertEquals(LocalDateTime.of(2026, 10, 17, 0, 0), hourly().buildBatch(batchOf(lateInUtc), "btcl").get(0).tup_starttime, "Dhaka's hour 00");
        SummaryBean<WifiDestinationSummary> inUtc = WifiSummaries.destinationForWindow("utcDst", "daily", null, ZoneId.of("UTC"));
        assertEquals(LocalDateTime.of(2026, 10, 16, 0, 0), inUtc.buildBatch(batchOf(lateInUtc), "btcl").get(0).tup_starttime, "a UTC tenant would file it under the 16th");
    }

    @Test
    void the_profiles_zone_is_the_root_tenants_zone_dhaka_unless_the_profile_says_otherwise() {
        SummaryBean<WifiDestinationSummary> fromConfig = WifiSummaries.destinationForWindow("dailyWifiDstSummary", "daily", null);

        assertEquals(LocalDateTime.of(2026, 10, 17, 0, 0), fromConfig.buildBatch(batchOf(List.of(flow("2026-10-16T18:30:00Z"))), "btcl").get(0).tup_starttime,
                "DdlPartitions.tenantZone: summary.zone, default Asia/Dhaka — the voice beans' zone");
    }

    @Test
    void the_mac_is_no_key_and_is_not_counted_two_devices_to_one_destination_are_one_row() {
        Collection<WifiDestinationSummary> rows = rollup(hourly(), List.of(flow(W1), flow(W1).mac("aa:bb:cc:dd:ee:02").packets(1, 1).bytes(1, 1)));

        assertEquals(1, rows.size(), "no distinct_macs, no row per device (ruling 8)");
        assertEquals("2 | 15201 | 410301 | 121 | 341", sums(rows.iterator().next()));
        assertFalse(WifiDestinationSummary.INSERT_COLUMNS.contains("mac"), "no device column at all");
    }

    @Test
    void the_keys_the_message_carries_and_the_table_does_not_hold_are_read_past() {
        // sVlan, cVlan, accessType, pppoeSessionId, userName, operator, tenantNo, v, part, parts (the contract page's §2b)
        byte[] blob = batchOf(List.of(flow(W1).withExtraKeys("\"sVlan\":100,\"cVlan\":2001,\"accessType\":\"pppoe\",\"pppoeSessionId\":7,\"userName\":\"u\",\"operator\":\"btcl\",\"tenantNo\":1,\"v\":1,\"part\":1,\"parts\":2")));

        assertEquals("1 | 15200 | 410300 | 120 | 340", sums(hourly().buildBatch(blob, "btcl").get(0)));
    }

    @Test
    void a_row_with_no_readable_window_start_is_skipped_and_the_rest_of_the_blob_is_summed() {
        List<Flow> withOneBadRow = List.of(flow(W1), flow(null), flow("yesterday").packets(9, 9).bytes(9, 9));

        List<WifiDestinationSummary> built = hourly().buildBatch(batchOf(withOneBadRow), "btcl");

        assertEquals(1, built.size(), "the two rows with no window are not counted anywhere");
        assertEquals("1 | 15200 | 410300 | 120 | 340", sums(built.get(0)));
    }

    @Test
    void a_blob_that_is_not_an_array_of_rows_is_a_poison_row_never_a_silent_zero() {
        assertThrows(IllegalArgumentException.class, () -> hourly().buildBatch("{\"kind\":\"flow_window\"}".getBytes(StandardCharsets.UTF_8), "btcl"));
        assertThrows(IllegalArgumentException.class, () -> hourly().buildBatch("not json".getBytes(StandardCharsets.UTF_8), "btcl"));
    }

    @Test
    void a_cdr_blob_in_the_wifi_flow_stream_would_not_be_mistaken_for_flows() {
        // the two entities share the table, never the bookmark: a session's entry has no window_start — nothing is summed
        assertEquals(0, hourly().buildBatch(WifiSessionTestSupport.batchOf(WifiSessionTestSupport.measuredSession(LocalDateTime.of(2026, 10, 16, 9, 0))), "btcl").size());
    }

    @Test
    void a_second_tenants_rows_are_drained_for_that_tenants_schema_the_bean_carries_no_tenant_the_outbox_does() {
        // the outbox is per schema: a blob read in res_44's schema is summed into res_44's table; the rows name no tenant
        List<WifiDestinationSummary> inRoot = hourly().buildBatch(batchOf(List.of(flow(W1))), "btcl");
        List<WifiDestinationSummary> inReseller = hourly().buildBatch(batchOf(List.of(flow(W1).site("sylhet-2"))), "res_44");

        assertEquals("mirpur-10", inRoot.get(0).tup_site);
        assertEquals("sylhet-2", inReseller.get(0).tup_site);
        assertFalse(WifiDestinationSummary.INSERT_COLUMNS.contains("tenant"), "the schema is the tenant: no column for it (the voice tables' rule)");
    }

    // ---- the seeding rule (OutboxReader.seedBookmarks:188-222), pinned for the new entity ----

    @Test
    void a_wifi_flow_bean_on_a_schema_that_already_has_cdr_bookmarks_starts_at_0_not_at_the_head() {
        FakeUnitOfWorkFactory database = new FakeUnitOfWorkFactory();
        OutboxReader reader = new OutboxReader(database, new SummaryEngine(), 1000, 1, 8);
        // the schema was served before: the call beans have bookmarks, the outbox holds rows
        database.outbox.seed(1, "x");
        database.outbox.seed(2, "y");
        database.outbox.advanceOffset("cdr", "dailyCallSummarySg101", 2);
        database.outbox.advanceOffset("cdr", "dailyAdSummary", 2);

        Map<String, Long> seeded = reader.seedBookmarks(null, List.of(daily(), hourly()));

        assertEquals(Map.of("dailyWifiDstSummary", 0L, "hourlyWifiDstSummary", 0L), seeded, "a NEW entity type: no bookmark at all for it -> 0, every flow row ever written is summed");
        // whereas a cdr bean switched on now, beside the bookmarked ones, starts at the head ("sums from now")
        assertEquals(Map.of("dailyWifiSessionSummary", 2L), reader.seedBookmarks(null, List.of(WifiBeansTestSupport.dailySession())));
    }

    // ---- the table, described once for both engines ----

    @Test
    void the_table_is_described_with_bigint_sums_no_money_no_devices_the_bucket_in_the_key_and_the_two_indexes() {
        SummaryTableSpec spec = hourly().tableSpec();

        assertEquals("sum_wifi_dst_hr", spec.name());
        assertEquals("id," + WifiDestinationSummary.INSERT_COLUMNS, String.join(",", spec.columnNames()));
        assertEquals(List.of("id", "tup_starttime"), spec.primaryKey());
        assertEquals(List.of("ix_starttime", "ix_site_remote_starttime"), spec.indexes().stream().map(SummaryTableSpec.Index::name).toList());
        assertEquals(List.of("tup_site", "tup_remote", "tup_starttime"), spec.indexes().get(1).columns());
        assertEquals("tup_starttime", spec.partitionColumn());
        assertTrue(spec.keptUpToDate());
        for (String sum : List.of("bytes_up", "bytes_dn", "packets_up", "packets_dn", "flows")) {
            assertEquals(SummaryTableSpec.Type.BIGINT, spec.columns().stream().filter(c -> c.name().equals(sum)).findFirst().orElseThrow().type(), sum);
        }
        assertTrue(spec.columns().stream().noneMatch(c -> c.type() == SummaryTableSpec.Type.DECIMAL), "no money column (ruling 8)");
        assertTrue(spec.columnNames().stream().noneMatch(c -> c.contains("mac") || c.contains("distinct")), "no distinct_macs");
        assertEquals(45, spec.columns().stream().filter(c -> c.name().equals("tup_remote")).findFirst().orElseThrow().width());
    }

    @Test
    void the_ddl_is_one_partitioned_create_on_mysql_and_a_plain_table_with_its_two_indexes_on_postgresql() {
        System.setProperty("summary.ddl.partition-start", "2026-10-01");
        System.setProperty("summary.ddl.partition-days", "3");
        try {
            List<String> mysql = daily().tableDdl(SqlDialect.MYSQL);
            List<String> postgres = daily().tableDdl(SqlDialect.POSTGRESQL);

            assertEquals(1, mysql.size());
            assertTrue(mysql.get(0).startsWith("CREATE TABLE IF NOT EXISTS sum_wifi_dst_day (") && mysql.get(0).contains("PRIMARY KEY (id, tup_starttime)")
                    && mysql.get(0).contains("KEY ix_site_remote_starttime (tup_site, tup_remote, tup_starttime)") && mysql.get(0).contains("PARTITION BY RANGE COLUMNS(tup_starttime)"), mysql.get(0));
            assertEquals(3, postgres.size());
            assertTrue(postgres.get(0).startsWith("CREATE TABLE IF NOT EXISTS sum_wifi_dst_day (id BIGINT GENERATED BY DEFAULT AS IDENTITY") && !postgres.get(0).contains("PARTITION"), postgres.get(0));
            assertEquals("CREATE INDEX IF NOT EXISTS sum_wifi_dst_day_ix_site_remote_starttime ON sum_wifi_dst_day (tup_site, tup_remote, tup_starttime)", postgres.get(2));
        } finally {
            System.clearProperty("summary.ddl.partition-start");
            System.clearProperty("summary.ddl.partition-days");
        }
    }
}
