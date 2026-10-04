package com.telcobright.summary.tenancy.api;

import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.outbox.api.OutboxReader;
import com.telcobright.summary.outbox.internal.OutboxCodec;
import com.telcobright.summary.registry.api.SummaryBeanRegistry;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.testkit.Await;
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory;
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory.Tier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Brief S6: one process serves its root tenant's WHOLE tree, read from prime-context; a new reseller schema is
 * picked up at the next read of the tree (the doorbell's effect) with no restart; one the tree lost is stopped.
 * The tree here is a list a test changes — what prime-context would answer.
 */
class TenantWatcherTest {

    private static final String DAILY = "dailyAdSummary";

    private final FakeUnitOfWorkFactory database = new FakeUnitOfWorkFactory();
    private final SummaryBeanRegistry registry = new SummaryBeanRegistry(new OutboxReader(database, new SummaryEngine(), 1000, 50, 8), 1);
    private final List<String> tree = new CopyOnWriteArrayList<>(List.of("btcl", "res_44"));
    private volatile IOException primeContextDown;
    private final TenantWatcher watcher = new TenantWatcher(registry, "btcl", () -> {
        if (primeContextDown != null) throw primeContextDown;
        return new ArrayList<>(tree);
    });

    {
        registry.register(AdTestSupport.dailyBean());
    }

    @AfterEach
    void stop() {
        registry.stopAll();
    }

    @Test
    void every_schema_of_the_tree_is_served() throws Exception {
        assertEquals(Set.of("btcl", "res_44"), watcher.reloadNow());

        assertTrue(registry.isRunning("btcl", DAILY) && registry.isRunning("res_44", DAILY));
    }

    @Test
    void a_reseller_provisioned_at_run_time_is_served_at_the_next_read_of_the_tree_with_no_restart() throws Exception {
        watcher.reloadNow();
        Tier res45 = database.tier("res_45");
        res45.outbox().seed(1, OutboxCodec.encode(AdTestSupport.batchOf(AdTestSupport.leafView(AdTestSupport.at(2026, 10, 2, 21, 14)))));

        tree.add("res_45");                                     // prime-context provisioned it, rebuilt the tree, rang the doorbell
        assertEquals(Set.of("btcl", "res_44", "res_45"), watcher.reloadNow());

        assertTrue(res45.store().ranSqlMatching("CREATE TABLE IF NOT EXISTS sum_ad_day_30"), "its tables are made at its first use");
        assertTrue(Await.until(() -> res45.outbox().readOffset("cdr", DAILY) == 1, 10_000), "the view billing wrote there is summed");
        assertTrue(res45.store().firstSqlMatching("insert into sum_ad_day_30").contains("values ('res_45',"));
    }

    @Test
    void a_schema_the_tree_lost_is_no_longer_served() throws Exception {
        watcher.reloadNow();

        tree.remove("res_44");
        assertEquals(Set.of("btcl"), watcher.reloadNow());

        assertFalse(registry.isRunning("res_44", DAILY));
        assertTrue(registry.isRunning("btcl", DAILY));
    }

    @Test
    void a_tree_that_cannot_be_read_changes_nothing() throws Exception {
        watcher.reloadNow();

        primeContextDown = new IOException("prime-context answered HTTP 503");
        assertThrows(IOException.class, watcher::reloadNow);

        assertEquals(Set.of("btcl", "res_44"), registry.servedSchemas(), "the schemas served go on");
        assertTrue(registry.isRunning("btcl", DAILY) && registry.isRunning("res_44", DAILY));

        primeContextDown = null;
        tree.add("res_45");
        assertEquals(Set.of("btcl", "res_44", "res_45"), watcher.reloadNow(), "and the next read is taken");
    }

    @Test
    void a_name_that_is_not_a_schemas_is_never_served() throws Exception {
        tree.add("res_45; drop schema btcl cascade");
        tree.add("res-46");
        tree.add("");

        assertEquals(Set.of("btcl", "res_44"), watcher.reloadNow());

        assertEquals(Set.of("btcl", "res_44"), database.schemasEntered(), "no unit of work was begun on such a name");
    }

    @Test
    void a_tree_that_names_no_schema_is_refused_and_nothing_is_stopped() throws Exception {
        watcher.reloadNow();

        tree.clear();
        tree.add("not a name");
        assertThrows(IllegalStateException.class, watcher::reloadNow);

        assertEquals(Set.of("btcl", "res_44"), registry.servedSchemas(), "an empty tree is a broken answer, not an order to stop everything");
    }

    @Test
    void a_schema_that_cannot_be_served_does_not_hold_up_the_others_and_is_tried_again() throws Exception {
        // res_47 is in the tree but its schema is not ready: nothing can be created in it yet
        database.tier("res_47").store().failSqlStartingWith("CREATE TABLE IF NOT EXISTS");
        tree.add(1, "res_47");

        assertEquals(Set.of("btcl", "res_44"), watcher.reloadNow(), "the schemas before and after it are served");

        database.tier("res_47").store().failSqlStartingWith(null);
        assertEquals(Set.of("btcl", "res_44", "res_47"), watcher.reloadNow(), "the next read of the tree serves it");
        assertTrue(registry.isRunning("res_47", DAILY));
    }

    @Test
    void a_deployment_without_a_tree_serves_the_connections_own_schema_as_before() {
        // no summary.tenants.mode in the active profile (tcbl/dev): single
        TenantWatcher single = new TenantWatcher(registry);

        single.start();

        assertTrue(Await.until(() -> registry.servedSchemas().equals(Set.of(SummaryBeanRegistry.OWN_SCHEMA)) && registry.isRunning(SummaryBeanRegistry.OWN_SCHEMA, DAILY), 10_000));
        assertTrue(database.schemasEntered().isEmpty(), "no schema is entered by name");
        single.stop();
    }

    @Test
    void a_store_that_does_not_answer_at_the_start_does_not_fail_the_start_and_is_served_when_it_answers() {
        // a one-tenant deployment whose database is down when the service starts
        database.store.failSqlStartingWith("CREATE TABLE IF NOT EXISTS");
        TenantWatcher single = new TenantWatcher(registry, null, null, 100);

        single.start();                                          // does not throw: nothing is served on the caller's thread

        assertTrue(Await.never(() -> !registry.servedSchemas().isEmpty(), 700), "not served while the store refuses — and tried again meanwhile");
        database.store.failSqlStartingWith(null);                // the store answers
        assertTrue(Await.until(() -> registry.isRunning(SummaryBeanRegistry.OWN_SCHEMA, DAILY), 10_000), "served by a retry: no restart, no doorbell");
        single.stop();
    }

    @Test
    void a_schema_that_could_not_be_served_is_tried_again_soon_with_no_doorbell_and_no_refresh() {
        database.tier("res_47").store().failSqlStartingWith("CREATE TABLE IF NOT EXISTS");
        tree.add("res_47");
        TenantWatcher watching = new TenantWatcher(registry, "btcl", () -> new ArrayList<>(tree), 100);

        watching.start();

        assertTrue(Await.until(() -> registry.servedSchemas().equals(Set.of("btcl", "res_44")), 10_000), "the others are served");
        assertTrue(Await.never(() -> registry.servedSchemas().contains("res_47"), 500));
        database.tier("res_47").store().failSqlStartingWith(null);  // its schema is ready now
        assertTrue(Await.until(() -> registry.isRunning("res_47", DAILY), 10_000), "served by a retry: nobody rang, nothing was restarted");
        watching.stop();
    }

    @Test
    void a_tree_that_could_not_be_read_at_the_start_is_asked_again_soon() {
        primeContextDown = new IOException("prime-context does not answer");
        TenantWatcher watching = new TenantWatcher(registry, "btcl", () -> {
            if (primeContextDown != null) throw primeContextDown;
            return new ArrayList<>(tree);
        }, 100);

        watching.start();

        assertTrue(Await.never(() -> !registry.servedSchemas().isEmpty(), 500), "nothing is served from a tree nobody could read");
        primeContextDown = null;
        assertTrue(Await.until(() -> registry.servedSchemas().equals(Set.of("btcl", "res_44")), 10_000), "the tree is read by a retry");
        watching.stop();
    }
}
