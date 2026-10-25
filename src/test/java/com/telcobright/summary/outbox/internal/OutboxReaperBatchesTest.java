package com.telcobright.summary.outbox.internal;

import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.outbox.api.OutboxReader;
import com.telcobright.summary.registry.api.SummaryBeanRegistry;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.testkit.FakeOutboxStore.BoundedDelete;
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory;
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory.Tier;
import com.telcobright.summary.testkit.LogCapture;
import com.telcobright.summary.testkit.WifiFlowTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HARDEN-1 B4 — the reaper's trim deletes in BOUNDED batches: at most {@code summary.outbox.reap-batch} rows (10 000)
 * per transaction, the lowest ids first, batch after batch until that (schema, entity)'s passed rows are gone or the
 * pass's budget ({@code summary.outbox.reap-pass-budget-ms}, 5 000) is spent; what is left is the next pass's, said in
 * ONE INFO with the rows left. Every (schema, entity) gets its first batch whatever the budget: the {@code cdr}
 * entity's minute of rows is trimmed every pass, in one transaction, as before — even behind a {@code wifi_flow} backlog.
 */
class OutboxReaperBatchesTest {

    private static final int NEVER_POLLS = 3600, BATCH = 10_000;
    private static final long THE_BUDGET_ALLOWS = 60_000, NO_BUDGET = 0;
    private static final String CDR_DAILY = "dailyAdSummary", WIFI_DAILY = WifiFlowTestSupport.DAILY_BEAN;

    private final FakeUnitOfWorkFactory database = new FakeUnitOfWorkFactory();
    private OutboxReader reader;
    private SummaryBeanRegistry registry;
    private Tier btcl;

    /** A cdr bean and a wifi_flow bean, btcl served, the workers stopped again: the rows and the bookmarks are the test's. */
    @BeforeEach
    void btclServedWithACdrBeanAndAWifiFlowBean() {
        reader = new OutboxReader(database, new SummaryEngine(), 1000, 50, 8);
        registry = new SummaryBeanRegistry(reader, NEVER_POLLS);
        registry.register(AdTestSupport.dailyBean());
        registry.register(WifiFlowTestSupport.daily());
        registry.serve("btcl");
        registry.stopAll();
        btcl = database.tier("btcl");
    }

    @AfterEach
    void stopTheWorkers() {
        registry.stopAll();
    }

    private OutboxReaper reaperWithABudgetOf(long passBudgetMillis) {
        return new OutboxReaper(reader, registry, 60, BATCH, passBudgetMillis);
    }

    /** wifi_flow rows 1..25 000, every one passed by the wifi bean (billing-core's flow ingest, never trimmed before HARDEN-1). */
    private void aPassedWifiFlowBacklogOf25000Rows() {
        btcl.outbox().seedOf("wifi_flow", 1, 25_000, "x");
        btcl.outbox().advanceOffset("wifi_flow", WIFI_DAILY, 25_000);
    }

    private static BoundedDelete batchOf(String entityType, int deleted) {
        return new BoundedDelete(entityType, BATCH, deleted);
    }

    @Test
    void a_backlog_of_25000_passed_wifi_flow_rows_is_cleared_in_three_transactions_of_at_most_10000_when_the_budget_allows() {
        aPassedWifiFlowBacklogOf25000Rows();

        try (LogCapture said = LogCapture.of(OutboxReaper.class)) {
            assertEquals(25_000, reaperWithABudgetOf(THE_BUDGET_ALLOWS).reapOnce());
            assertTrue(said.lines().stream().noneMatch(line -> line.contains("stopped on its budget")), said.lines().toString());
        }
        assertEquals(List.of(batchOf("wifi_flow", 10_000), batchOf("wifi_flow", 10_000), batchOf("wifi_flow", 5_000)),
                btcl.outbox().boundedDeletesOf("wifi_flow"), "three transactions, none above the batch");
        assertEquals(List.of(), btcl.outbox().idsOf("wifi_flow"));
    }

    @Test
    void a_tiny_budget_leaves_rows_and_the_next_pass_goes_on_from_there() {
        aPassedWifiFlowBacklogOf25000Rows();
        OutboxReaper reaper = reaperWithABudgetOf(NO_BUDGET);

        try (LogCapture said = LogCapture.of(OutboxReaper.class)) {
            assertEquals(10_000, reaper.reapOnce(), "the first batch runs whatever the budget; the rest waits");
            assertEquals(10_001L, btcl.outbox().idsOf("wifi_flow").get(0), "the lowest ids went first");
            assertEquals(10_000, reaper.reapOnce(), "the next pass goes on from there");
            assertEquals(5_000, reaper.reapOnce());
            assertEquals(0, reaper.reapOnce());

            assertEquals(List.of("reaper: this pass stopped on its budget (summary.outbox.reap-pass-budget-ms=0) — the next pass goes on with "
                            + "the rows left: schema btcl, entity wifi_flow: 15000",
                            "reaper: this pass stopped on its budget (summary.outbox.reap-pass-budget-ms=0) — the next pass goes on with "
                            + "the rows left: schema btcl, entity wifi_flow: 5000"),
                    said.records(Level.INFO).stream().filter(line -> line.contains("stopped on its budget")).toList(),
                    "ONE INFO per pass that stops on the budget, with the rows left; none when nothing is left");
        }
        assertEquals(List.of(), btcl.outbox().idsOf("wifi_flow"));
    }

    @Test
    void the_cdr_entitys_minute_of_rows_goes_in_one_transaction_every_pass_even_behind_a_wifi_flow_backlog_with_no_budget() {
        aPassedWifiFlowBacklogOf25000Rows();
        btcl.outbox().seedOf("cdr", 25_001, 25_030, "x");                 // a minute of billing batches
        btcl.outbox().advanceOffset("cdr", CDR_DAILY, 25_030);

        assertEquals(10_030, reaperWithABudgetOf(NO_BUDGET).reapOnce(), "cdr's 30 rows and wifi_flow's first batch");
        assertEquals(List.of(batchOf("cdr", 30)), btcl.outbox().boundedDeletesOf("cdr"), "one transaction, every passed cdr row: as before");
        assertEquals(List.of(), btcl.outbox().idsOf("cdr"));
        assertEquals(15_000, btcl.outbox().idsOf("wifi_flow").size());
    }

    @Test
    void a_batch_below_one_row_or_a_negative_budget_is_refused_in_words() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new OutboxReaper(reader, registry, 60, 0, 5_000)).getMessage()
                .startsWith("summary.outbox.reap-batch must be at least 1 (it is 0)"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new OutboxReaper(reader, registry, 60, BATCH, -1)).getMessage()
                .contains("summary.outbox.reap-pass-budget-ms at least 0 (it is -1)"));
    }
}
