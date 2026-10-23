package com.telcobright.summary.registry.api;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.outbox.api.OutboxReader;
import com.telcobright.summary.outbox.internal.OutboxCodec;
import com.telcobright.summary.outbox.internal.OutboxReaper;
import com.telcobright.summary.ping.internal.PingListener;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.summarybeans.call.CallSummaries;
import com.telcobright.summary.testkit.Await;
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory;
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory.Tier;
import com.telcobright.summary.testkit.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Briefs S6, S7, S8 over the fakes (each tenant schema has its own outbox, bookmarks and tables; the workers are
 * the real threads): one process serves every schema of the tree — a worker and a bookmark per (schema, bean); a
 * schema seen at run time is served with no restart; a schema seen for the first time starts at offset 0 and a
 * bean switched on later at the head; a ping wakes the workers of the tenant it names.
 */
class SummaryBeanRegistryTest {

    private static final String DAILY = "dailyAdSummary", HOURLY = "hourlyAdSummary", CALL30 = "dailyCallSummarySg30";
    private static final LocalDateTime T = AdTestSupport.at(2026, 10, 2, 21, 14);
    /** A poll so long that only the start's own drain, or a wake, can explain a drained row. */
    private static final int NEVER_POLLS = 3600;

    private final FakeUnitOfWorkFactory database = new FakeUnitOfWorkFactory();
    private SummaryBeanRegistry registry;

    private SummaryBeanRegistry registry(int pollSeconds, SummaryBean<?>... beans) {
        registry = new SummaryBeanRegistry(new OutboxReader(database, new SummaryEngine(), 1000, 50, 8), pollSeconds);
        for (SummaryBean<?> bean : beans) registry.register(bean);
        return registry;
    }

    @AfterEach
    void stopTheWorkers() {
        if (registry != null) registry.stopAll();
    }

    private static String leafViewRow() {
        return OutboxCodec.encode(AdTestSupport.batchOf(AdTestSupport.leafView(T)));
    }

    private static String rootViewRow() {
        return OutboxCodec.encode(AdTestSupport.batchOf(AdTestSupport.rootView(T)));
    }

    private static long offset(Tier tier, String bean) {
        return tier.outbox().readOffset("cdr", bean);
    }

    // ---- S6 ----

    @Test
    void every_schema_has_its_own_worker_and_its_own_bookmark_for_every_bean() {
        registry(1, AdTestSupport.dailyBean(), AdTestSupport.hourlyBean());
        Tier btcl = database.tier("btcl"), res44 = database.tier("res_44");
        // one view through btcl > res_44: billing wrote ONE outbox row in EACH tier's schema
        res44.outbox().seed(1, leafViewRow());
        btcl.outbox().seed(1, rootViewRow());

        assertEquals(List.of(DAILY, HOURLY), registry.serve("btcl").stream().sorted().toList());
        registry.serve("res_44");

        assertEquals(Set.of("btcl", "res_44"), registry.servedSchemas());
        for (String schema : List.of("btcl", "res_44")) {
            assertTrue(registry.isRunning(schema, DAILY) && registry.isRunning(schema, HOURLY), "a worker per (schema, bean): " + schema);
        }
        assertTrue(Await.until(() -> offset(btcl, DAILY) == 1 && offset(btcl, HOURLY) == 1 && offset(res44, DAILY) == 1 && offset(res44, HOURLY) == 1, 10_000),
                "four bookmarks, each in its own schema, each moved by its own worker");
        String inBtcl = btcl.store().firstSqlMatching("insert into sum_ad_day_30");
        String inRes44 = res44.store().firstSqlMatching("insert into sum_ad_day_30");
        assertTrue(inBtcl.contains("values ('btcl',44,"), "the operator's row, in the operator's schema: " + inBtcl);
        assertTrue(inRes44.contains("values ('res_44',61,"), "the reseller's row, in the reseller's schema: " + inRes44);
        assertEquals(1, btcl.store().countSqlMatching("insert into sum_ad_day_30"), "each schema's summary holds its own tier's record only");
        assertEquals(null, database.store.firstSqlMatching("insert"), "nothing was written outside the two schemas");
    }

    @Test
    void a_schema_first_seen_at_run_time_is_served_with_no_restart_its_tables_made_then() {
        registry(1, AdTestSupport.dailyBean());
        registry.serve("btcl");
        Tier btcl = database.tier("btcl");
        btcl.outbox().seed(1, rootViewRow());
        assertTrue(Await.until(() -> offset(btcl, DAILY) == 1, 10_000));
        assertFalse(database.schemasEntered().contains("res_45"), "the reseller does not exist yet");

        // prime-context provisioned res_45; the next read of the tree serves it
        Tier res45 = database.tier("res_45");
        res45.outbox().seed(1, leafViewRow());
        registry.serve("res_45");

        assertTrue(res45.store().ranSqlMatching("CREATE TABLE IF NOT EXISTS summary_offset"), "its bookmark table: made at its first use");
        assertTrue(res45.store().ranSqlMatching("CREATE TABLE IF NOT EXISTS sum_ad_day_30"), "its summary table: made at its first use");
        assertTrue(Await.until(() -> offset(res45, DAILY) == 1, 10_000), "and its view is summed");
        assertTrue(res45.store().firstSqlMatching("insert into sum_ad_day_30").contains("values ('res_45',61,"));
        assertTrue(registry.isRunning("btcl", DAILY), "the schema served before goes on, untouched");
        assertEquals(1, btcl.store().countSqlMatching("CREATE TABLE IF NOT EXISTS sum_ad_day_30"), "its table was made once, at ITS first use");
    }

    @Test
    void serving_a_schema_again_starts_nothing_twice() {
        registry(NEVER_POLLS, AdTestSupport.dailyBean());

        registry.serve("btcl");
        registry.serve("btcl");

        Tier btcl = database.tier("btcl");
        assertEquals(1, btcl.store().countSqlMatching("CREATE TABLE IF NOT EXISTS sum_ad_day_30"), "the running worker is left alone");
        assertEquals(1, btcl.store().countSqlMatching("CREATE TABLE IF NOT EXISTS summary_offset"), "the infra tables: once per schema");
    }

    @Test
    void a_bean_whose_table_cannot_be_made_stops_that_bean_only_and_is_tried_again() {
        registry(NEVER_POLLS, AdTestSupport.dailyBean(), AdTestSupport.hourlyBean());
        Tier btcl = database.tier("btcl");
        btcl.store().failSqlStartingWith("CREATE TABLE IF NOT EXISTS sum_ad_hr_30");

        assertEquals(List.of(DAILY), registry.serve("btcl"), "the daily bean runs; the hourly one's table could not be made");
        assertFalse(registry.isRunning("btcl", HOURLY));

        btcl.store().failSqlStartingWith(null);
        assertEquals(List.of(DAILY, HOURLY), registry.serve("btcl").stream().sorted().toList(), "served again: the bean it lacked starts");
    }

    @Test
    void a_schema_the_tree_lost_is_stopped_and_its_bookmarks_stay() {
        registry(1, AdTestSupport.dailyBean());
        Tier res44 = database.tier("res_44");
        res44.outbox().seed(1, leafViewRow());
        registry.serve("btcl");
        registry.serve("res_44");
        assertTrue(Await.until(() -> offset(res44, DAILY) == 1, 10_000));

        registry.unserve("res_44");

        assertEquals(Set.of("btcl"), registry.servedSchemas());
        assertFalse(registry.isRunning("res_44", DAILY));
        assertTrue(registry.isRunning("btcl", DAILY));
        assertEquals(1, offset(res44, DAILY), "its bookmark is where it was: served again, it goes on from there");
    }

    @Test
    void a_one_tenant_deployment_serves_the_connections_own_schema_entered_by_nobody() {
        registry(1, AdTestSupport.dailyBean());
        database.outbox.seed(1, rootViewRow());

        registry.serve(SummaryBeanRegistry.OWN_SCHEMA);

        assertTrue(Await.until(() -> database.outbox.readOffset("cdr", DAILY) == 1, 10_000));
        assertTrue(database.schemasEntered().isEmpty(), "no schema was entered by name: the unit of work ran where the URL opens");
        assertEquals(Set.of(SummaryBeanRegistry.OWN_SCHEMA), registry.servedSchemas());
    }

    // ---- S7 ----

    @Test
    void a_schema_seen_for_the_first_time_starts_at_offset_0_every_row_billing_wrote_before_is_summed() {
        registry(1, AdTestSupport.dailyBean(), AdTestSupport.hourlyBean());
        Tier res44 = database.tier("res_44");
        res44.outbox().seed(1, leafViewRow());                  // billing wrote first …
        res44.outbox().seed(2, leafViewRow());
        res44.outbox().seed(3, leafViewRow());

        registry.serve("res_44");                               // … summary-service starts second

        assertTrue(Await.until(() -> offset(res44, DAILY) == 3 && offset(res44, HOURLY) == 3, 10_000),
                "BOTH beans started at 0 — the first bean's bookmark did not make the schema look served to the second");
        assertEquals(3, res44.store().countSqlMatching("insert into sum_ad_day_30"), "every row is summed, none skipped");
        assertEquals(3, res44.store().countSqlMatching("insert into sum_ad_hr_30"));
    }

    @Test
    void a_bean_switched_on_later_on_a_served_schema_sums_from_now() {
        registry(1, AdTestSupport.dailyBean());
        Tier btcl = database.tier("btcl");
        btcl.outbox().seed(1, rootViewRow());
        btcl.outbox().seed(2, rootViewRow());
        registry.serve("btcl");
        assertTrue(Await.until(() -> offset(btcl, DAILY) == 2, 10_000));

        // the hourly bean is switched on later: the schema is served already, so it starts at the outbox HEAD
        registry.register(AdTestSupport.hourlyBean());
        registry.start(HOURLY);

        assertTrue(Await.until(() -> registry.isRunning("btcl", HOURLY) && btcl.outbox().hasBookmark("cdr", HOURLY), 10_000));
        assertEquals(2, offset(btcl, HOURLY), "its bookmark is the head: the two rows before it are not its");
        btcl.outbox().seed(3, rootViewRow());
        assertTrue(Await.until(() -> offset(btcl, HOURLY) == 3 && offset(btcl, DAILY) == 3, 10_000), "what lands after flows to both");
        assertEquals(1, btcl.store().countSqlMatching("insert into sum_ad_hr_30"), "row 3 only");
    }

    @Test
    void a_bean_enabled_by_a_restart_on_a_served_schema_sums_from_now_never_from_the_residue() {
        // An earlier run served btcl with the daily bean alone, up to row 2; rows 1 and 2 are still in the outbox (the
        // reaper has not trimmed them). The profile then gains the hourly bean and the service is RESTARTED: the schema
        // is served already, so the new bean starts at the head — summing rows 1 and 2 would be a partial backfill that
        // looks like complete windows. This is decided when the schema is served, BEFORE its workers start.
        Tier btcl = database.tier("btcl");
        btcl.outbox().seed(1, rootViewRow());
        btcl.outbox().seed(2, rootViewRow());
        btcl.outbox().advanceOffset("cdr", DAILY, 2);           // the earlier run's bookmark
        registry(1, AdTestSupport.dailyBean(), AdTestSupport.hourlyBean());

        registry.serve("btcl");                                 // the restart

        assertTrue(Await.until(() -> btcl.outbox().hasBookmark("cdr", HOURLY), 10_000));
        assertEquals(2, offset(btcl, HOURLY), "the late bean's first bookmark is the head");
        assertEquals(2, offset(btcl, DAILY), "the bean that was there keeps its own");
        assertTrue(Await.never(() -> btcl.store().ranSqlMatching("insert into sum_ad_hr_30"), 2500), "the residue is never summed by the late bean");

        btcl.outbox().seed(3, rootViewRow());
        assertTrue(Await.until(() -> offset(btcl, HOURLY) == 3 && offset(btcl, DAILY) == 3, 10_000), "what lands after the restart flows to both");
        assertEquals(1, btcl.store().countSqlMatching("insert into sum_ad_hr_30"), "row 3 only");
    }

    @Test
    void a_bookmark_that_exists_is_never_moved_by_a_restart() {
        registry(NEVER_POLLS, AdTestSupport.dailyBean(), AdTestSupport.hourlyBean());
        Tier btcl = database.tier("btcl");
        btcl.outbox().advanceOffset("cdr", DAILY, 7);          // the daily bean stopped here before the restart
        btcl.outbox().advanceOffset("cdr", HOURLY, 5);
        for (int id = 1; id <= 9; id++) btcl.outbox().seed(id, "%%% never read in this test %%%");

        OutboxReader reader = new OutboxReader(database, new SummaryEngine(), 1000, 50, 8);
        assertTrue(reader.seedBookmarks("btcl", List.of(AdTestSupport.dailyBean(), AdTestSupport.hourlyBean())).isEmpty(), "both have one: nothing to give");

        assertEquals(7, offset(btcl, DAILY));
        assertEquals(5, offset(btcl, HOURLY));
    }

    @Test
    void a_schema_with_no_summary_affected_waits_no_table_is_made_one_warn_the_others_go_on_and_it_is_picked_up_with_no_restart() {
        // The rule (the architect, on billing-core's BC-0004 F3): in a tenant schema ONLY billing-core makes
        // summary_affected. A tier it has not written to yet has none: summary-service WAITS for that schema.
        database.dialect = com.telcobright.summary.bean.spi.SqlDialect.POSTGRESQL;
        registry(1, AdTestSupport.dailyBean(), AdTestSupport.hourlyBean());
        Tier btcl = database.tier("btcl"), res46 = database.tier("res_46");
        res46.outbox().outboxPresent = false;                   // prime-context provisioned it; billing-core has not written yet
        btcl.outbox().seed(1, rootViewRow());

        try (LogCapture said = LogCapture.of(OutboxReader.class)) {
            registry.serve("btcl");
            registry.serve("res_46");

            // 1 · the other schemas go on
            assertTrue(Await.until(() -> offset(btcl, DAILY) == 1 && offset(btcl, HOURLY) == 1, 10_000), "btcl is drained while res_46 waits");
            // the waiting schema is looked at again and again (a 1-second poll, two beans) …
            assertTrue(registry.isRunning("res_46", DAILY) && registry.isRunning("res_46", HOURLY), "its workers run: they wait, they did not fail");
            Await.pause(2500);

            // 2 · no table is made in its place
            assertEquals(null, res46.store().firstSqlMatching("CREATE TABLE IF NOT EXISTS summary_affected "), "the outbox is billing-core's to make");
            assertTrue(res46.store().ranSqlMatching("CREATE TABLE IF NOT EXISTS summary_offset"), "what is the service's own IS made: its bookmarks …");
            assertTrue(res46.store().ranSqlMatching("CREATE TABLE IF NOT EXISTS sum_ad_day_30"), "… and its summary tables");
            assertEquals(null, res46.store().firstSqlMatching("insert"), "nothing is drained");

            // 3 · ONE WARN, naming the schema and the table — not one per bean, not one per poll
            List<String> warnings = said.warnings().stream().filter(line -> line.contains("res_46")).toList();
            assertEquals(1, warnings.size(), "said once: " + warnings);
            assertTrue(warnings.get(0).contains("schema=res_46") && warnings.get(0).contains("summary_affected") && warnings.get(0).contains("WAITING"),
                    warnings.get(0));
            assertTrue(said.warnings().stream().noneMatch(line -> line.contains("schema=btcl")), "a schema that has its outbox says nothing");

            // 4 · the table appears: that schema is picked up with no restart
            res46.outbox().outboxPresent = true;                // billing-core wrote its first batch there
            res46.outbox().seed(1, leafViewRow());
            assertTrue(Await.until(() -> offset(res46, DAILY) == 1 && offset(res46, HOURLY) == 1, 10_000), "its first row is summed, by the workers that waited");
            assertTrue(res46.store().firstSqlMatching("insert into sum_ad_day_30").contains("values ('res_46',61,"));
            assertTrue(said.lines().stream().anyMatch(line -> line.contains("schema=res_46") && line.contains("is there now")), "and it says so");
        }
    }

    // ---- S8 ----

    @Test
    void a_ping_wakes_the_workers_of_the_tenant_it_names_and_no_other() {
        registry(NEVER_POLLS, AdTestSupport.dailyBean());
        Tier btcl = database.tier("btcl"), res44 = database.tier("res_44");
        registry.serve("btcl");
        registry.serve("res_44");
        assertTrue(Await.until(() -> btcl.outbox().hasBookmark("cdr", DAILY) && res44.outbox().hasBookmark("cdr", DAILY), 10_000));
        Await.pause(300);                                       // both workers have done their first drain and now wait
        btcl.outbox().seed(1, rootViewRow());
        res44.outbox().seed(1, leafViewRow());

        // billing-core's ping after res_44's batch committed
        int woken = PingListener.wakeFor(registry, "{\"tenant\":\"res_44\",\"entity\":\"cdr\",\"rows\":1}".getBytes(StandardCharsets.UTF_8));

        assertEquals(1, woken, "res_44's one worker");
        assertTrue(Await.until(() -> offset(res44, DAILY) == 1, 10_000), "the named tenant's view is summed at once");
        assertTrue(Await.never(() -> offset(btcl, DAILY) == 1, 1000), "btcl was not pinged: its worker sleeps on its timer");

        assertEquals(1, PingListener.wakeFor(registry, "{\"tenant\":\"btcl\",\"entity\":\"cdr\",\"rows\":1}".getBytes(StandardCharsets.UTF_8)));
        assertTrue(Await.until(() -> offset(btcl, DAILY) == 1, 10_000));
    }

    @Test
    void a_ping_wakes_the_workers_of_the_entity_it_names() {
        registry(NEVER_POLLS, AdTestSupport.dailyBean(), CallSummaries.forWindow(CALL30, "daily", "30", 30, null));
        registry.serve("btcl");

        assertEquals(2, registry.wake("btcl", "cdr"), "both beans read the cdr stream");
        assertEquals(0, registry.wake("btcl", "sms"), "no bean of that entity");
        assertEquals(0, registry.wake("res_99", "cdr"), "not a schema this process serves");
        assertEquals(2, registry.wake("btcl", null), "a ping that names no entity wakes the tenant's workers");
    }

    @Test
    void a_ping_that_cannot_be_read_or_names_no_tenant_wakes_every_worker_as_before() {
        registry(NEVER_POLLS, AdTestSupport.dailyBean());
        Tier btcl = database.tier("btcl"), res44 = database.tier("res_44");
        registry.serve("btcl");
        registry.serve("res_44");
        assertTrue(Await.until(() -> btcl.outbox().hasBookmark("cdr", DAILY) && res44.outbox().hasBookmark("cdr", DAILY), 10_000));
        Await.pause(300);
        btcl.outbox().seed(1, rootViewRow());
        res44.outbox().seed(1, leafViewRow());

        assertEquals(-1, PingListener.wakeFor(registry, "not json".getBytes(StandardCharsets.UTF_8)), "-1 = everyone");

        assertTrue(Await.until(() -> offset(btcl, DAILY) == 1 && offset(res44, DAILY) == 1, 10_000));
        assertEquals(-1, PingListener.wakeFor(registry, "{\"rows\":3}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(-1, PingListener.wakeFor(registry, null));
    }

    @Test
    void the_workers_of_a_one_tenant_deployment_wake_on_every_ping() {
        registry(NEVER_POLLS, AdTestSupport.dailyBean());
        registry.serve(SummaryBeanRegistry.OWN_SCHEMA);

        assertEquals(1, registry.wake("telcobright", "cdr"), "they serve the connection's own schema, whatever its name");
    }

    // ---- the poll stays as the fallback; the reaper works per schema ----

    @Test
    void with_no_ping_at_all_the_poll_still_drains() {
        registry(1, AdTestSupport.dailyBean());
        Tier btcl = database.tier("btcl");
        registry.serve("btcl");
        assertTrue(Await.until(() -> btcl.outbox().hasBookmark("cdr", DAILY), 10_000));
        Await.pause(200);

        btcl.outbox().seed(1, rootViewRow());                   // nobody pings

        assertTrue(Await.until(() -> offset(btcl, DAILY) == 1, 10_000), "the 1-second poll found it");
    }

    @Test
    void the_reaper_trims_each_schemas_outbox_by_that_schemas_own_bookmarks() {
        registry(NEVER_POLLS, AdTestSupport.dailyBean(), AdTestSupport.hourlyBean());
        OutboxReader reader = new OutboxReader(database, new SummaryEngine(), 1000, 50, 8);
        registry = new SummaryBeanRegistry(reader, NEVER_POLLS);
        registry.register(AdTestSupport.dailyBean());
        registry.register(AdTestSupport.hourlyBean());
        Tier btcl = database.tier("btcl"), res44 = database.tier("res_44"), res46 = database.tier("res_46");
        res46.outbox().outboxPresent = false;
        for (Tier tier : List.of(btcl, res44)) for (int id = 1; id <= 3; id++) tier.outbox().seed(id, "x");
        registry.serve("btcl");
        registry.serve("res_44");
        registry.serve("res_46");
        registry.stopAll();                                     // the bookmarks below are the test's
        btcl.outbox().advanceOffset("cdr", DAILY, 3);
        btcl.outbox().advanceOffset("cdr", HOURLY, 3);          // both passed all three
        res44.outbox().advanceOffset("cdr", DAILY, 3);
        res44.outbox().advanceOffset("cdr", HOURLY, 1);         // the hourly bean lags

        int deleted = new OutboxReaper(reader, registry, "cdr", 60).reapOnce();

        assertEquals(4, deleted, "3 in btcl, 1 in res_44; res_46 has no outbox to trim");
        assertEquals(0, btcl.outbox().rowCount());
        assertEquals(2, res44.outbox().rowCount(), "rows 2 and 3 wait for res_44's own laggard");
    }

    @Test
    void a_schema_the_reaper_cannot_reach_is_said_once_and_once_more_when_it_trims_again() {
        // brief S16: a database that is away does not write a line per pass for every schema
        com.telcobright.summary.testkit.StoreThatGoesAway store = new com.telcobright.summary.testkit.StoreThatGoesAway(database);
        OutboxReader reader = new OutboxReader(store, new SummaryEngine(), 1000, 50, 8);
        registry = new SummaryBeanRegistry(reader, NEVER_POLLS);
        registry.register(AdTestSupport.dailyBean());
        Tier btcl = database.tier("btcl");
        for (int id = 1; id <= 3; id++) btcl.outbox().seed(id, "x");
        registry.serve("btcl");
        registry.stopAll();
        btcl.outbox().advanceOffset("cdr", DAILY, 3);
        OutboxReaper reaper = new OutboxReaper(reader, registry, "cdr", 60);

        try (com.telcobright.summary.testkit.LogCapture said = com.telcobright.summary.testkit.LogCapture.of(OutboxReaper.class)) {
            store.goAway();
            for (int pass = 0; pass < 5; pass++) assertEquals(0, reaper.reapOnce());
            store.comeBack();
            assertEquals(3, reaper.reapOnce(), "the first pass after the store is back trims");
            assertEquals(0, reaper.reapOnce());

            assertEquals(1, said.warnings().size(), "ONE line when the trouble starts, not one per pass: " + said.lines());
            assertTrue(said.warnings().get(0).startsWith("reaper: schema btcl failed this pass"), said.warnings().get(0));
            assertEquals(java.util.List.of("reaper: schema btcl trims again — after 5 failed passes"),
                    said.lines().stream().filter(line -> line.contains("trims again —")).toList());
        }
    }

    @Test
    void starting_a_bean_nobody_registered_is_refused() {
        registry(NEVER_POLLS, AdTestSupport.dailyBean());

        assertThrows(IllegalArgumentException.class, () -> registry.start("noSuchBean"));
    }
}
