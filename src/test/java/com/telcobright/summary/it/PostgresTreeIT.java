package com.telcobright.summary.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.telcobright.summary.bean.spi.SqlDialect;
import com.telcobright.summary.beans.DailyChargeableSummaryBuilder;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.outbox.api.OutboxReader;
import com.telcobright.summary.outbox.internal.OutboxCodec;
import com.telcobright.summary.outbox.internal.OutboxReaper;
import com.telcobright.summary.ping.internal.PingListener;
import com.telcobright.summary.registry.api.SummaryBeanRegistry;
import com.telcobright.summary.runtime.internal.JdbcUnitOfWorkFactory;
import com.telcobright.summary.runtime.internal.TestPools;
import com.telcobright.summary.runtime.spi.UnitOfWork;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.summarybeans.call.CallSummaries;
import com.telcobright.summary.summarybeans.call.internal.CdrBlobMapper;
import com.telcobright.summary.tenancy.api.TenantWatcher;
import com.telcobright.summary.testkit.Await;
import com.telcobright.summary.testkit.BillingStandIn;
import com.telcobright.summary.testkit.LogCapture;
import com.telcobright.summary.testkit.PgLab;
import io.agroal.api.AgroalDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Briefs S6, S7, S8 on real PostgreSQL: ONE process (one registry, ONE pool as {@code summary_service}) serves
 * every tier schema of the root's tree. The schemas are made as the real ones are — prime-context's provisioning,
 * billing-core's outbox with its grant — and a view is written the way billing-core writes it: one outbox row in
 * EACH tier's schema, in that tier's own transaction. The tree is a list the test changes: what prime-context
 * would answer after a rebuild; a read of it is what the doorbell causes.
 *
 * <p>The schema names carry the run's prefix ({@code it_btcl}, {@code it_res_44}); a tier's name is its schema's,
 * so the rows say {@code it_res_44} where a deployment's say {@code res_44}.
 */
class PostgresTreeIT {

    private static final String BTCL = PgLab.schema("btcl"), RES_44 = PgLab.schema("res_44"), RES_45 = PgLab.schema("res_45"), RES_46 = PgLab.schema("res_46");
    private static final String DAILY = "dailyAdSummary", HOURLY = "hourlyAdSummary";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> tree = new CopyOnWriteArrayList<>(List.of(BTCL, RES_44));
    private AgroalDataSource pool;
    private OutboxReader reader;
    private SummaryBeanRegistry registry;
    private TenantWatcher watcher;

    @BeforeEach
    void theTreeOfBtcl() {
        assumeTrue(PgLab.reachable(), "the PostgreSQL lab is not reachable (tools/lab/pg-lab.sh up) — skipping integration test");
        for (String tier : List.of(BTCL, RES_44)) {
            PgLab.provisionTier(tier);
            PgLab.billingTables(tier);
        }
        PgLab.dropTier(RES_45);
        PgLab.dropTier(RES_46);
        // the pool names NO schema: every unit of work enters the tier it is for
        pool = TestPools.pool(SqlDialect.POSTGRESQL, PgLab.URL, PgLab.SUMMARY_SERVICE, "", 4);
        start(1);
    }

    /** The service's wiring, with the poll a test wants. */
    private void start(int pollSeconds) {
        reader = new OutboxReader(new JdbcUnitOfWorkFactory(pool, SqlDialect.POSTGRESQL), new SummaryEngine(), 1000, 1, 8);
        registry = new SummaryBeanRegistry(reader, pollSeconds);
        registry.register(AdTestSupport.dailyBean());
        registry.register(AdTestSupport.hourlyBean());
        registry.register(CallSummaries.forWindow("dailyCallSummarySg30", "daily", "30", 30, null));
        registry.register(DailyChargeableSummaryBuilder.create(CdrBlobMapper.create()).build());
        watcher = new TenantWatcher(registry, BTCL, () -> new ArrayList<>(tree));
    }

    @AfterEach
    void stop() {
        if (registry != null) registry.stopAll();
        if (pool != null) pool.close();
        for (String tier : List.of(BTCL, RES_44, RES_45, RES_46)) PgLab.dropTier(tier);
    }

    // ---- S6 ----

    @Test
    void a_view_through_btcl_and_res_44_gives_summary_rows_in_both_schemas() throws Exception {
        watcher.reloadNow();

        billingWritesTheSampleView(Map.of("res_44", RES_44, "btcl", BTCL));

        assertTrue(Await.until(() -> offset(RES_44, DAILY) == 1 && offset(BTCL, DAILY) == 1 && offset(RES_44, HOURLY) == 1 && offset(BTCL, HOURLY) == 1
                && offset(RES_44, "dailyCallSummarySg30") == 1 && offset(BTCL, "dailyChargeableSummary") == 1, 30_000), "every bean of both schemas summed it");
        assertEquals(RES_44 + "|1|12|7001|dhaka-north|mirpur-10|captive|image|done|2026-10-02 00:00:00|1|1|1|1|0|10|0.500000|0.000000", adDayRow(RES_44),
                "the advertiser's tier, in the reseller's own schema: partner 1 pays 0.50");
        assertEquals(BTCL + "|44|12|7001|dhaka-north|mirpur-10|captive|image|done|2026-10-02 00:00:00|1|1|1|1|0|10|0.400000|0.000000", adDayRow(BTCL),
                "the operator's tier, in the operator's schema: the reseller (partner 44) pays 0.40");
        assertEquals("2026-10-02 21:00:00", PgLab.queryText(PgLab.AD_SPHERE, RES_44, "select tup_starttime::text from sum_ad_hr_30"));
        assertEquals("1|cola-eid|dhaka-north|1|0.500000", PgLab.queryText(PgLab.AD_SPHERE, RES_44,
                "select tup_inpartnerid || '|' || tup_incomingroute || '|' || tup_outgoingroute || '|' || totalcalls || '|' || customercost from sum_voice_day_30"));
        assertEquals("30|BDT|1|0.40000000", PgLab.queryText(PgLab.AD_SPHERE, BTCL,
                "select tup_servicegroup || '|' || tup_billeduom || '|' || totalcount || '|' || billedamount from sum_chargeable_day"));
        assertEquals(1, PgLab.queryLong(PgLab.AD_SPHERE, BTCL, "select count(*) from sum_ad_day_30"), "each schema holds its own tier's row and no other");
        assertEquals(1, PgLab.queryLong(PgLab.AD_SPHERE, RES_44, "select count(*) from sum_ad_day_30"));
    }

    @Test
    void a_reseller_provisioned_at_run_time_gets_its_rows_with_no_restart() throws Exception {
        watcher.reloadNow();
        billingWritesTheSampleView(Map.of("res_44", RES_44, "btcl", BTCL));
        assertTrue(Await.until(() -> offset(BTCL, DAILY) == 1 && offset(RES_44, DAILY) == 1, 30_000));

        // a reseller is made at run time: prime-context provisions its schema; billing-core serves it and writes a view
        PgLab.provisionTier(RES_45);
        PgLab.billingTables(RES_45);
        billingWritesTheSampleView(Map.of("res_44", RES_45, "btcl", BTCL));                 // a view through btcl > res_45

        tree.add(RES_45);                                                                 // prime-context rebuilt the tree …
        assertEquals(Set.of(BTCL, RES_44, RES_45), watcher.reloadNow());                  // … and rang: the tree is read again

        assertTrue(Await.until(() -> offset(RES_45, DAILY) == 1 && offset(BTCL, DAILY) == 2, 30_000), "the new schema's view is summed, and the root's second one");
        assertEquals(RES_45 + "|1|12|7001|dhaka-north|mirpur-10|captive|image|done|2026-10-02 00:00:00|1|1|1|1|0|10|0.500000|0.000000", adDayRow(RES_45));
        assertEquals("sum_ad_day_30,sum_ad_hr_30,sum_chargeable_day,sum_voice_day_30,summary_affected_dlq,summary_offset", PgLab.queryText(PgLab.PRIME_CONTEXT, null,
                "select string_agg(tablename, ',' order by tablename) from pg_tables where schemaname = '" + RES_45 + "' and tableowner = 'summary_service'"),
                "its tables were made at its first use, by the service, as summary_service");
        assertEquals(2, PgLab.queryLong(PgLab.AD_SPHERE, BTCL, "select views from sum_ad_day_30"), "the root goes on: both views, one window row");
        assertTrue(registry.isRunning(RES_44, DAILY), "the reseller served before goes on too");
    }

    @Test
    void a_schema_the_tree_lost_is_stopped_and_served_again_goes_on_from_its_bookmark() throws Exception {
        watcher.reloadNow();
        billingWritesTheSampleView(Map.of("res_44", RES_44, "btcl", BTCL));
        assertTrue(Await.until(() -> offset(RES_44, DAILY) == 1, 30_000));

        tree.remove(RES_44);
        assertEquals(Set.of(BTCL), watcher.reloadNow());
        billingWritesTheSampleView(Map.of("res_44", RES_44, "btcl", BTCL));
        assertTrue(Await.never(() -> offset(RES_44, DAILY) == 2, 2500), "not served: its outbox waits");

        tree.add(RES_44);
        watcher.reloadNow();
        assertTrue(Await.until(() -> offset(RES_44, DAILY) == 2, 30_000), "served again: it goes on from its bookmark, the row that waited is summed");
        assertEquals(2, PgLab.queryLong(PgLab.AD_SPHERE, RES_44, "select views from sum_ad_day_30"), "exactly once each");
    }

    // ---- S7 ----

    @Test
    void billing_writes_first_summary_starts_second_every_row_is_summed() throws Exception {
        // three views in each tier's outbox BEFORE this service has ever served the schemas
        for (int view = 0; view < 3; view++) billingWritesTheSampleView(Map.of("res_44", RES_44, "btcl", BTCL));
        assertEquals(3, PgLab.queryLong(PgLab.SUMMARY_SERVICE, RES_44, "select count(*) from summary_affected"));

        watcher.reloadNow();

        assertTrue(Await.until(() -> offset(RES_44, DAILY) == 3 && offset(RES_44, HOURLY) == 3 && offset(BTCL, DAILY) == 3 && offset(BTCL, HOURLY) == 3, 30_000),
                "every bean of a schema seen for the first time started at 0");
        assertEquals(3, PgLab.queryLong(PgLab.AD_SPHERE, RES_44, "select views from sum_ad_day_30"), "none skipped");
        assertEquals(3, PgLab.queryLong(PgLab.AD_SPHERE, BTCL, "select views from sum_ad_hr_30"));
        assertEquals(3, PgLab.queryLong(PgLab.AD_SPHERE, RES_44, "select totalcalls from sum_voice_day_30"));
    }

    @Test
    void a_bean_switched_on_later_on_a_served_schema_sums_from_now() throws Exception {
        registry.stopAll();
        start(1);
        registry = new SummaryBeanRegistry(reader, 1);
        registry.register(AdTestSupport.dailyBean());                                     // the daily bean alone, at first
        watcher = new TenantWatcher(registry, BTCL, () -> new ArrayList<>(tree));
        watcher.reloadNow();
        billingWritesTheSampleView(Map.of("res_44", RES_44, "btcl", BTCL));
        billingWritesTheSampleView(Map.of("res_44", RES_44, "btcl", BTCL));
        assertTrue(Await.until(() -> offset(RES_44, DAILY) == 2, 30_000));

        registry.register(AdTestSupport.hourlyBean());                                    // switched on later
        registry.start(HOURLY);

        assertTrue(Await.until(() -> registry.isRunning(RES_44, HOURLY) && bookmarked(RES_44, HOURLY), 30_000));
        assertEquals(2, offset(RES_44, HOURLY), "the schema is served already: the late bean starts at the outbox head");
        billingWritesTheSampleView(Map.of("res_44", RES_44, "btcl", BTCL));
        assertTrue(Await.until(() -> offset(RES_44, HOURLY) == 3, 30_000));
        assertEquals(1, PgLab.queryLong(PgLab.AD_SPHERE, RES_44, "select views from sum_ad_hr_30"), "the one view that came after it was switched on");
        assertEquals(3, PgLab.queryLong(PgLab.AD_SPHERE, RES_44, "select views from sum_ad_day_30"), "the bean that was there from the start has all three");
    }

    @Test
    void a_schema_with_no_summary_affected_waits_no_table_is_made_one_warn_the_others_go_on_and_it_is_picked_up_with_no_restart() throws Exception {
        // The rule (the architect, on billing-core's BC-0004 F3): on PostgreSQL the role that makes a table owns it, so in
        // a tenant schema ONLY billing-core makes summary_affected. A new tier it has not written to has none: WAIT.
        PgLab.provisionTier(RES_46);                                                      // provisioned; billing-core has made nothing there
        tree.add(RES_46);

        try (LogCapture said = LogCapture.of(OutboxReader.class)) {
            assertEquals(Set.of(BTCL, RES_44, RES_46), watcher.reloadNow());

            // 1 · the other schemas go on
            billingWritesTheSampleView(Map.of("res_44", RES_44, "btcl", BTCL));
            assertTrue(Await.until(() -> offset(RES_44, DAILY) == 1 && offset(BTCL, DAILY) == 1, 30_000), "btcl and res_44 are drained while res_46 waits");
            assertTrue(registry.isRunning(RES_46, DAILY), "its workers run: they wait, they did not fail");
            Await.pause(3000);                                                            // four beans, a 1-second poll: many looks

            // 2 · no table is made in its place — the service made only what is its own
            assertEquals("sum_ad_day_30,sum_ad_hr_30,sum_chargeable_day,sum_voice_day_30,summary_affected_dlq,summary_offset", PgLab.queryText(PgLab.PRIME_CONTEXT, null,
                    "select string_agg(tablename, ',' order by tablename) from pg_tables where schemaname = '" + RES_46 + "'"), "no summary_affected");
            assertEquals(0, offset(RES_46, DAILY), "its first bookmark is 0: the first row billing writes will be summed");

            // 3 · ONE WARN, naming the schema and the table
            List<String> warnings = said.warnings().stream().filter(line -> line.contains(RES_46)).toList();
            assertEquals(1, warnings.size(), "said once, not by every bean and not at every poll: " + warnings);
            assertTrue(warnings.get(0).contains("schema=" + RES_46) && warnings.get(0).contains("summary_affected") && warnings.get(0).contains("WAITING"),
                    warnings.get(0));

            // 4 · billing-core makes its tables and writes: the schema is picked up with no restart
            PgLab.billingTables(RES_46);
            assertEquals("billing_core", PgLab.queryText(PgLab.PRIME_CONTEXT, null,
                    "select tableowner from pg_tables where schemaname = '" + RES_46 + "' and tablename = 'summary_affected'"), "billing-core could make it: it is its own");
            billingWritesTheSampleView(Map.of("res_44", RES_46, "btcl", BTCL));
            assertTrue(Await.until(() -> offset(RES_46, DAILY) == 1, 30_000), "the first row billing wrote is summed, by the workers that waited");
            assertEquals(1, PgLab.queryLong(PgLab.AD_SPHERE, RES_46, "select views from sum_ad_day_30"));
        }
    }

    // ---- S8 ----

    @Test
    void a_view_is_in_the_summary_within_a_few_seconds_of_its_ping() throws Exception {
        registry.stopAll();
        start(3600);                                                                      // the poll would take an hour: only the ping explains a row
        watcher.reloadNow();
        assertTrue(Await.until(() -> bookmarked(RES_44, DAILY) && bookmarked(BTCL, DAILY), 30_000));
        Await.pause(500);                                                                 // every worker did its first drain and waits

        billingWritesTheSampleView(Map.of("res_44", RES_44, "btcl", BTCL));
        long pinged = System.nanoTime();
        // billing-core's ping after res_44's batch committed (SummaryChangeNotificationPublisher)
        PingListener.wakeFor(registry, ("{\"tenant\":\"" + RES_44 + "\",\"entity\":\"cdr\",\"rows\":1}").getBytes(StandardCharsets.UTF_8));

        assertTrue(Await.until(() -> PgLab.queryLong(PgLab.AD_SPHERE, RES_44, "select count(*) from sum_ad_day_30") == 1, 5_000), "within a few seconds");
        long millis = (System.nanoTime() - pinged) / 1_000_000;
        System.out.println("S8 measured: the view was in res_44's summary " + millis + " ms after its ping (the poll is 3600 s)");
        assertTrue(millis < 5_000, "the view was in res_44's summary " + millis + " ms after its ping");
        assertTrue(Await.never(() -> offset(BTCL, DAILY) == 1, 1500), "btcl was not pinged yet: its row waits for its own ping (or its poll)");

        PingListener.wakeFor(registry, ("{\"tenant\":\"" + BTCL + "\",\"entity\":\"cdr\",\"rows\":1}").getBytes(StandardCharsets.UTF_8));
        assertTrue(Await.until(() -> offset(BTCL, DAILY) == 1, 5_000));
    }

    // ---- one pool, every schema ----

    @Test
    void one_pooled_connection_serves_schema_after_schema_and_each_unit_of_work_runs_in_its_own() {
        registry.stopAll();
        pool.close();
        // ONE connection for everything: it keeps the schema the last unit of work entered
        pool = TestPools.pool(SqlDialect.POSTGRESQL, PgLab.urlFor(BTCL), PgLab.SUMMARY_SERVICE, "", 1);
        JdbcUnitOfWorkFactory factory = new JdbcUnitOfWorkFactory(pool, SqlDialect.POSTGRESQL);
        OutboxReader onOne = new OutboxReader(factory, new SummaryEngine(), 1000, 1, 8);
        onOne.ensureInfraTables(BTCL);
        onOne.ensureInfraTables(RES_44);
        PgLab.run(PgLab.SUMMARY_SERVICE, BTCL, "insert into summary_offset (entity_type, bean_name, last_offset) values ('cdr', 'onlyInBtcl', 7)");
        PgLab.run(PgLab.SUMMARY_SERVICE, RES_44, "insert into summary_offset (entity_type, bean_name, last_offset) values ('cdr', 'onlyInRes44', 9)");

        assertEquals(Set.of("onlyInRes44"), bookmarksSeenIn(factory, RES_44));
        assertEquals(Set.of("onlyInBtcl"), bookmarksSeenIn(factory, BTCL));
        assertEquals(Set.of("onlyInRes44"), bookmarksSeenIn(factory, RES_44));
        // a unit of work that names no schema runs in the connection's OWN (the URL's), not where the last one left it
        assertEquals(Set.of("onlyInBtcl"), bookmarksSeenIn(factory, null), "it went home: the connection was still in " + RES_44);
        try (UnitOfWork home = factory.begin(null)) {
            assertEquals(BTCL, home.schema());
        }
    }

    @Test
    void the_reaper_trims_every_schema_by_its_own_bookmarks_with_the_right_billing_gave() throws Exception {
        watcher.reloadNow();
        billingWritesTheSampleView(Map.of("res_44", RES_44, "btcl", BTCL));
        billingWritesTheSampleView(Map.of("res_44", RES_44, "btcl", BTCL));
        assertTrue(Await.until(() -> allPassed(RES_44, 2) && allPassed(BTCL, 2), 30_000));
        long recordsBefore = PgLab.queryLong(PgLab.SUMMARY_SERVICE, BTCL, "select count(*) from cdr");

        int deleted = new OutboxReaper(reader, registry, "cdr", 60).reapOnce();

        assertEquals(4, deleted, "two rows in each schema, every bean passed them");
        assertEquals(0, PgLab.queryLong(PgLab.SUMMARY_SERVICE, RES_44, "select count(*) from summary_affected"));
        assertEquals(0, PgLab.queryLong(PgLab.SUMMARY_SERVICE, BTCL, "select count(*) from summary_affected"));
        assertEquals(recordsBefore, PgLab.queryLong(PgLab.SUMMARY_SERVICE, BTCL, "select count(*) from cdr"), "billing's records are untouched");
    }

    // ---- helpers ----

    /**
     * The design's sample message, as the switch sends it, written the way billing-core writes it: per tier ONE
     * transaction with its record and ONE outbox row, in the schema {@code schemaOf} gives for the wire's tenant.
     */
    private static void billingWritesTheSampleView(Map<String, String> schemaOf) {
        ArrayNode message = BillingStandIn.wireMessage("ad/sample-view-two-tiers.json");
        ObjectNode leafFacts;
        try {
            leafFacts = (ObjectNode) JSON.readTree(message.get(0).get("additionalMetaData").asText());
            ObjectNode root = (ObjectNode) message.get(1);
            ObjectNode whole = leafFacts.deepCopy();
            whole.remove(List.of("balanceBefore", "balanceAfter"));
            whole.setAll((ObjectNode) JSON.readTree(root.get("additionalMetaData").asText()));
            root.put("additionalMetaData", JSON.writeValueAsString(whole));          // the switch fills the facts into every tier
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Map<String, List<String>> byTier = new LinkedHashMap<>(BillingStandIn.outboxEntriesByTier(message));
        byTier.forEach((wireTenant, entries) -> PgLab.billingWrites(schemaOf.get(wireTenant), OutboxCodec.encode(AdTestSupport.batchJson(entries))));
    }

    private static String adDayRow(String schema) {
        return PgLab.queryText(PgLab.AD_SPHERE, schema, "select concat_ws('|', tup_tenant, tup_partnerid, tup_campaignid, tup_rulecode, tup_zone, tup_site, tup_app, "
                + "tup_mediakind, tup_outcome, tup_starttime, views, shown, completed, credited, failed, watchedsec, chargedamount, chargedunits) from sum_ad_day_30");
    }

    private static long offset(String schema, String bean) {
        return PgLab.queryLong(PgLab.SUMMARY_SERVICE, schema, "select coalesce(max(last_offset), 0) from summary_offset where bean_name = '" + bean + "'");
    }

    private static boolean bookmarked(String schema, String bean) {
        return PgLab.queryLong(PgLab.SUMMARY_SERVICE, schema, "select count(*) from summary_offset where bean_name = '" + bean + "'") == 1;
    }

    private static boolean allPassed(String schema, long id) {
        return PgLab.queryLong(PgLab.SUMMARY_SERVICE, schema, "select count(*) from summary_offset where last_offset >= " + id) == 4;
    }

    private static Set<String> bookmarksSeenIn(JdbcUnitOfWorkFactory factory, String schema) {
        try (UnitOfWork unitOfWork = factory.begin(schema)) {
            Set<String> beans = unitOfWork.outbox().bookmarkedBeans("cdr");
            unitOfWork.commit();
            return beans;
        }
    }
}
