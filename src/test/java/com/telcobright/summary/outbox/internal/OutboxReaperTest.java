package com.telcobright.summary.outbox.internal;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.config.internal.ProfileYamlLoader;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.outbox.api.OutboxReader;
import com.telcobright.summary.registry.api.SummaryBeanRegistry;
import com.telcobright.summary.runtime.spi.UnitOfWorkFactory;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory;
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory.Tier;
import com.telcobright.summary.testkit.LogCapture;
import com.telcobright.summary.testkit.StoreThatGoesAway;
import com.telcobright.summary.testkit.WifiFlowTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HARDEN-1 B — the reaper trims EVERY entity that has a registered bean, each by {@code min(last_offset)} over THAT
 * entity's beans only; an entity with no registered bean is never trimmed (SS-0008 D1: a bean that comes back must find
 * its rows). Before, it trimmed ONE entity ({@code summary.outbox.entity-type}, default {@code cdr}): a WiFi tenant's
 * {@code wifi_flow} rows stayed for ever (SS-0008 F1). The property is retired: set, it is said once at start and
 * changes nothing. A trouble is said once per (schema, entity), and once more when that entity trims again (S16).
 *
 * <p>The rows of the two entities share one id sequence (one table), interleaved as billing-core writes them: cdr at the
 * odd ids, wifi_flow at the even ones.
 */
class OutboxReaperTest {

    private static final int NEVER_POLLS = 3600;
    private static final String CDR_DAILY = "dailyAdSummary", CDR_HOURLY = "hourlyAdSummary";
    private static final String WIFI_DAILY = WifiFlowTestSupport.DAILY_BEAN, WIFI_HOURLY = WifiFlowTestSupport.HOURLY_BEAN;

    private final FakeUnitOfWorkFactory database = new FakeUnitOfWorkFactory();
    private OutboxReader reader;
    private SummaryBeanRegistry registry;

    @AfterEach
    void stopTheWorkers() {
        if (registry != null) registry.stopAll();
    }

    /** The reaper as the service makes it. */
    private OutboxReaper reaper() {
        return new OutboxReaper(reader, registry, 60);
    }

    /** The reaper of a profile that still sets the retired {@code summary.outbox.entity-type}. */
    private OutboxReaper reaperWhoseProfileStillNames(String entityType) {
        return new OutboxReaper(reader, registry, Optional.of(entityType), 60);
    }

    /** These beans registered, the schema btcl served, the workers stopped again: the bookmarks below are the test's. */
    private Tier btclServedWith(UnitOfWorkFactory units, SummaryBean<?>... beans) {
        reader = new OutboxReader(units, new SummaryEngine(), 1000, 50, 8);
        registry = new SummaryBeanRegistry(reader, NEVER_POLLS);
        for (SummaryBean<?> bean : beans) registry.register(bean);
        registry.serve("btcl");
        registry.stopAll();
        return database.tier("btcl");
    }

    /** cdr rows 1, 3, 5, 7 and wifi_flow rows 2, 4, 6, 8 — one table, one id sequence. */
    private static void cdrAndWifiFlowRowsInterleaved(Tier tier) {
        for (long id = 1; id <= 8; id++) tier.outbox().seedOf(id % 2 == 1 ? "cdr" : "wifi_flow", id, "x");
    }

    private static void bookmark(Tier tier, String entityType, String bean, long offset) {
        tier.outbox().advanceOffset(entityType, bean, offset);
    }

    @Test
    void every_entity_that_has_beans_is_trimmed_to_its_own_beans_minimum() {
        Tier btcl = btclServedWith(database, AdTestSupport.dailyBean(), AdTestSupport.hourlyBean(), WifiFlowTestSupport.daily(), WifiFlowTestSupport.hourly());
        cdrAndWifiFlowRowsInterleaved(btcl);
        bookmark(btcl, "cdr", CDR_DAILY, 7);
        bookmark(btcl, "cdr", CDR_HOURLY, 5);           // cdr's laggard: its minimum is 5
        bookmark(btcl, "wifi_flow", WIFI_DAILY, 8);
        bookmark(btcl, "wifi_flow", WIFI_HOURLY, 2);    // wifi_flow's laggard: its minimum is 2

        assertEquals(4, reaper().reapOnce(), "cdr 1, 3, 5 (up to ITS minimum 5) and wifi_flow 2 (up to ITS minimum 2)");
        assertEquals(List.of(7L), btcl.outbox().idsOf("cdr"));
        assertEquals(List.of(4L, 6L, 8L), btcl.outbox().idsOf("wifi_flow"), "4 and 6 wait for the wifi laggard, whatever cdr's beans passed");
        assertEquals(0, reaper().reapOnce(), "nothing more until a bean moves");
    }

    @Test
    void a_wifi_flow_row_with_no_wifi_bean_registered_is_never_trimmed() {
        Tier btcl = btclServedWith(database, AdTestSupport.dailyBean(), AdTestSupport.hourlyBean());
        cdrAndWifiFlowRowsInterleaved(btcl);
        bookmark(btcl, "cdr", CDR_DAILY, 8);
        bookmark(btcl, "cdr", CDR_HOURLY, 8);
        bookmark(btcl, "wifi_flow", WIFI_DAILY, 8);     // the bookmark of a wifi bean that is NOT registered now

        assertEquals(4, reaper().reapOnce(), "the cdr rows only");
        assertEquals(List.of(), btcl.outbox().idsOf("cdr"));
        assertEquals(List.of(2L, 4L, 6L, 8L), btcl.outbox().idsOf("wifi_flow"), "no wifi bean registered: a bean that comes back must find its rows");
    }

    @Test
    void a_trouble_is_said_once_per_schema_and_entity_and_once_more_when_that_entity_trims_again() {
        StoreThatGoesAway store = new StoreThatGoesAway(database);
        Tier btcl = btclServedWith(store, AdTestSupport.dailyBean(), WifiFlowTestSupport.daily());
        cdrAndWifiFlowRowsInterleaved(btcl);
        bookmark(btcl, "cdr", CDR_DAILY, 8);
        bookmark(btcl, "wifi_flow", WIFI_DAILY, 8);
        OutboxReaper reaper = reaper();

        try (LogCapture said = LogCapture.of(OutboxReaper.class)) {
            store.goAway();
            for (int pass = 0; pass < 5; pass++) assertEquals(0, reaper.reapOnce());
            store.comeBack();
            assertEquals(8, reaper.reapOnce(), "the first pass after the store is back trims both entities");
            assertEquals(0, reaper.reapOnce());

            assertEquals(List.of("reaper: schema btcl, entity cdr failed this pass", "reaper: schema btcl, entity wifi_flow failed this pass"),
                    said.warnings().stream().map(line -> line.substring(0, line.indexOf(';'))).sorted().toList(),
                    "ONE line per (schema, entity) when its trouble starts, not one per pass: " + said.lines());
            assertEquals(List.of("reaper: schema btcl, entity cdr trims again — after 5 failed passes",
                            "reaper: schema btcl, entity wifi_flow trims again — after 5 failed passes"),
                    said.lines().stream().filter(line -> line.contains("trims again —")).sorted().toList());
        }
    }

    @Test
    void the_retired_entity_type_key_is_said_once_at_start_and_changes_nothing() {
        Tier btcl = btclServedWith(database, AdTestSupport.dailyBean(), WifiFlowTestSupport.daily());
        cdrAndWifiFlowRowsInterleaved(btcl);
        bookmark(btcl, "cdr", CDR_DAILY, 8);
        bookmark(btcl, "wifi_flow", WIFI_DAILY, 8);
        OutboxReaper reaper = reaperWhoseProfileStillNames("wifi_flow");

        try (LogCapture said = LogCapture.of(OutboxReaper.class)) {
            reaper.start();
            reaper.stop();
            List<String> aboutTheKey = said.lines().stream().filter(line -> line.contains("summary.outbox.entity-type")).toList();
            assertEquals(1, aboutTheKey.size(), "ONE line at start: " + said.lines());
            assertEquals(aboutTheKey, said.records(Level.INFO).stream().filter(line -> line.contains("summary.outbox.entity-type")).toList(), "an INFO");
            assertTrue(aboutTheKey.get(0).startsWith("summary.outbox.entity-type=wifi_flow is IGNORED"), aboutTheKey.get(0));
            assertTrue(aboutTheKey.get(0).contains("EACH entity that has a registered bean"), "it says why: " + aboutTheKey.get(0));
        }
        assertEquals(8, reaper.reapOnce(), "both entities, whatever the retired key names");
    }

    @Test
    void a_profile_without_the_retired_key_hears_nothing_about_it() {
        btclServedWith(database, AdTestSupport.dailyBean());
        OutboxReaper reaper = reaper();

        try (LogCapture said = LogCapture.of(OutboxReaper.class)) {
            reaper.start();
            reaper.stop();
            assertTrue(said.lines().stream().noneMatch(line -> line.contains("summary.outbox.entity-type")), said.lines().toString());
        }
    }

    @Test
    void the_jars_own_profiles_no_longer_set_the_retired_key() {
        for (String[] tenant : new String[][] {{"btcl", "lab"}, {"btcl", "radius2"}, {"tcbl", "dev"}}) {
            assertNull(ProfileYamlLoader.loadProfile(new ProfileYamlLoader.ActiveTenant(tenant[0], tenant[1])).get(OutboxReaper.RETIRED_ENTITY_TYPE_KEY),
                    tenant[0] + "/" + tenant[1]);
        }
    }
}
