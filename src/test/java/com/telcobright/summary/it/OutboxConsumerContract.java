package com.telcobright.summary.it;

import com.telcobright.summary.bean.spi.SqlDialect;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.beans.DailyChargeableSummaryBuilder;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.outbox.api.OutboxReader;
import com.telcobright.summary.outbox.internal.OutboxCodec;
import com.telcobright.summary.runtime.internal.JdbcUnitOfWorkFactory;
import com.telcobright.summary.runtime.spi.UnitOfWork;
import com.telcobright.summary.runtime.spi.UnitOfWorkFactory;
import com.telcobright.summary.summarybeans.ad.internal.AdSummaryBean;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.summarybeans.call.CallSummaries;
import com.telcobright.summary.summarybeans.call.internal.CallSummaryBean;
import com.telcobright.summary.summarybeans.call.internal.CdrBlobMapper;
import com.telcobright.summary.summarybeans.call.model.CallSummary;
import com.telcobright.summary.summarybeans.chargeable.model.ChargeableSummary;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.CrashAtCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * THE CONTRACT of the outbox consumer over a real database — the same tests, word for word, on every engine the
 * store runs on ({@link OutboxConsumerIT} on MySQL, {@link PostgresOutboxConsumerIT} on PostgreSQL; brief S5:
 * "the same engine and the same one transaction; what changes is the edge"). It seeds {@code summary_affected}
 * with base64(gzip(JSON {Cdr,Chargeables[]})) v2 rows (+ op add/subtract — what billing writes), drains the
 * voice, chargeable and ad beans, and verifies summaries land, {@code last_offset} advances per bean, re-drains
 * are no-ops (exactly-once), corrections decrement, the reaper trims, poison rows dead-letter, head-init seeds,
 * a crash before the commit replays clean, and the tables SELF-PROVISION.
 *
 * <p>A subclass gives the engine: how a fresh tenant schema is made (the outbox as billing makes it there, the
 * voice tables) and the connections of the service and of the test. It SELF-SKIPS when its database does not answer.
 */
abstract class OutboxConsumerContract {

    protected static final String DAY_TABLE = CdrTestSupport.DAY_TABLE;
    protected static final String HOUR_TABLE = CdrTestSupport.HOUR_TABLE;

    protected final CallSummaryBean bean = CdrTestSupport.dailyBean();
    protected DataSource dataSource;
    protected OutboxReader reader;

    /** The engine under test. */
    protected abstract SqlDialect dialect();

    /**
     * A fresh tenant schema: the outbox as billing makes it on this engine, empty bookmarks, the voice day and
     * hour tables. Returns the SERVICE's datasource on it; skips the test (an assumption) when the lab is absent.
     */
    protected abstract DataSource freshSchema();

    /** The schema's own name — what a unit of work on it says it runs in. */
    protected abstract String schemaName();

    /** A connection that may WRITE the outbox, as billing-core does. */
    protected abstract Connection billingConnection() throws SQLException;

    /** A connection for the test's own reads. */
    protected abstract Connection dbConnection() throws SQLException;

    @BeforeEach
    void setUp() {
        dataSource = freshSchema();
        reader = new OutboxReader(unitOfWorkFactory(), new SummaryEngine(), 1000, 50, 8);
        serviceTables();
    }

    /** What the SERVICE makes in the fresh schema before a test drains (an engine whose fixture made them by hand: nothing). */
    protected void serviceTables() {
    }

    protected UnitOfWorkFactory unitOfWorkFactory() {
        return new JdbcUnitOfWorkFactory(dataSource, dialect());
    }

    @Test
    void drains_the_outbox_writes_summaries_advances_offset_and_is_exactly_once() {
        // row 1: two calls on the same day -> the daily bean merges them; row 2: another day
        seedOutbox(1, CdrTestSupport.encodedBatch(List.of(
                CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 10, 0)),
                CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 15, 0)))));
        seedOutbox(2, CdrTestSupport.encodedBatch(List.of(
                CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 20, 9, 0)))));

        int processed = reader.drain(bean);

        assertEquals(2, processed, "two outbox rows consumed");
        assertEquals(2, count(DAY_TABLE), "two day windows -> two summary rows");
        assertEquals(3, sumTotalCalls(), "three calls counted across the windows");
        assertEquals(2, offset("dailyCallSummary"), "last_offset advanced to the last row id");

        // re-drain: nothing new, no double-count
        int again = reader.drain(bean);
        assertEquals(0, again);
        assertEquals(2, count(DAY_TABLE));
        assertEquals(3, sumTotalCalls());
    }

    @Test
    void drains_a_week_of_outbox_rows_into_seven_day_windows() {
        // 7 outbox rows, one per day June 19..25, with day-index calls (1,2,…,7) -> 28 calls, 7 day windows
        int expectedCalls = 0;
        for (int i = 0; i < 7; i++) {
            int callsThatDay = i + 1;
            seedOutbox(i + 1, CdrTestSupport.encodedBatch(
                    CdrTestSupport.series(CdrTestSupport.at(2026, 6, 19 + i, 0, 0), 60, callsThatDay)));
            expectedCalls += callsThatDay;
        }

        int processed = reader.drain(bean);

        assertEquals(7, processed, "seven outbox rows consumed");
        assertEquals(7, count(DAY_TABLE), "seven distinct day windows");
        assertEquals(expectedCalls, sumTotalCalls(), "1+2+…+7 = 28 calls counted across the windows");
        assertEquals(7, offset("dailyCallSummary"), "last_offset advanced to the last row id");

        // re-drain is exactly-once
        assertEquals(0, reader.drain(bean));
        assertEquals(7, count(DAY_TABLE));
        assertEquals(expectedCalls, sumTotalCalls());
    }

    @Test
    void the_reaper_deletes_rows_only_after_all_active_beans_passed_them() {
        seedOutbox(1, CdrTestSupport.encodedBatch(List.of(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 10, 0)))));
        seedOutbox(2, CdrTestSupport.encodedBatch(List.of(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 20, 9, 0)))));
        Set<String> activeBeans = Set.of("dailyCallSummary", "hourlyCallSummary");

        assertEquals(2, reader.drain(bean), "the daily bean catches up first");
        assertEquals(0, reapLike(activeBeans), "hourly has no offset row yet -> nothing is safe to delete");
        assertEquals(2, count("summary_affected"), "rows retained for the lagging bean");

        CallSummaryBean hourly = CdrTestSupport.hourlyBean();
        assertEquals(2, reader.drain(hourly), "the hourly bean catches up");
        assertEquals(2, count(HOUR_TABLE), "two hour windows written");

        assertEquals(2, reapLike(activeBeans), "both beans passed -> both rows reaped");
        assertEquals(0, count("summary_affected"), "outbox trimmed");
        assertEquals(0, reader.drain(bean), "post-reap drains are no-ops");
    }

    @Test
    void a_failed_drain_rolls_back_completely_and_the_redelivery_counts_once() {
        seedOutbox(1, CdrTestSupport.encodedBatch(List.of(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 10, 0)))));

        // same bean NAME (same offset bookmark) but a table that does not exist -> the tx fails mid-drain,
        // exactly like a crash before commit
        CallSummaryBean broken = (CallSummaryBean) CallSummaries.forWindow("dailyCallSummary", "daily", "9", 10, null);
        assertThrows(RuntimeException.class, () -> reader.drain(broken));

        assertEquals(0, offset("dailyCallSummary"), "offset unchanged after the failed transaction");
        assertEquals(0, count(DAY_TABLE), "no partial summary rows leaked");

        // the redelivery (healthy bean, same bookmark) processes the row exactly once
        assertEquals(1, reader.drain(bean));
        assertEquals(1, offset("dailyCallSummary"));
        assertEquals(1, sumTotalCalls(), "counted exactly once despite the redelivery");
    }

    @Test
    void a_poison_row_is_quarantined_to_the_deadletter_table_and_skipped() {
        seedOutbox(1, "%%% not base64 %%%");
        seedOutbox(2, CdrTestSupport.encodedBatch(List.of(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 10, 0)))));
        OutboxReader quickQuarantine = new OutboxReader(unitOfWorkFactory(), new SummaryEngine(), 1000, 50, 3);

        for (int attempt = 1; attempt < 3; attempt++) {
            assertThrows(RuntimeException.class, () -> quickQuarantine.drainOnce(bean));
            assertEquals(0, offset("dailyCallSummary"));
        }

        assertEquals(1, quickQuarantine.drainOnce(bean), "threshold reached -> row consumed as a dead letter");
        assertEquals(1, count("summary_affected_dlq"), "the poison blob is preserved for repair");
        assertEquals(1, offset("dailyCallSummary"), "offset advanced past the poison row");

        assertEquals(1, quickQuarantine.drain(bean), "the clean row behind it drains normally");
        assertEquals(1, sumTotalCalls());
    }

    @Test
    void head_init_seeds_a_late_enabled_bean_at_the_outbox_head() {
        seedOutbox(1, CdrTestSupport.encodedBatch(List.of(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 10, 0)))));
        seedOutbox(2, CdrTestSupport.encodedBatch(List.of(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 20, 9, 0)))));

        reader.initOffsetAtHead(bean);

        assertEquals(2, offset("dailyCallSummary"), "bookmark seeded at the current head");
        assertEquals(0, reader.drain(bean), "pre-enablement residue is not consumed");
        assertEquals(0, count(DAY_TABLE), "no partial backfill masquerading as complete windows");

        reader.initOffsetAtHead(bean);
        assertEquals(2, offset("dailyCallSummary"), "head-init is a no-op once the bookmark exists");

        seedOutbox(3, CdrTestSupport.encodedBatch(List.of(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 21, 8, 0)))));
        assertEquals(1, reader.drain(bean), "rows landing after enablement flow normally");
        assertEquals(3, offset("dailyCallSummary"));
    }

    @Test
    void a_subtract_correction_row_decrements_the_committed_window() {
        // original batch: two calls in one day window
        seedOutbox(1, CdrTestSupport.encodedBatch(List.of(
                CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 10, 0)),
                CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 15, 0)))));
        assertEquals(1, reader.drain(bean));
        assertEquals(2, sumTotalCalls(), "window committed at 2 calls");

        // a billing correction removes one of them: op='subtract' with the OLD values
        seedOutbox(2, "subtract", CdrTestSupport.encodedBatch(List.of(
                CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 10, 0)))));
        assertEquals(1, reader.drain(bean));

        assertEquals(1, sumTotalCalls(), "2 - 1 = 1 after the subtract row");
        assertEquals(1, count(DAY_TABLE), "still ONE window row (decremented in place)");
        assertEquals(2, offset("dailyCallSummary"));

        // exactly-once still holds across ops
        assertEquals(0, reader.drain(bean));
        assertEquals(1, sumTotalCalls());
    }

    @Test
    void the_chargeable_bean_self_provisions_its_table_and_rolls_up_every_leg() {
        System.setProperty("summary.ddl.partition-start", "2026-06-01");
        System.setProperty("summary.ddl.partition-days", "60");   // small horizon: fast CREATE, real partitions
        try {
            SummaryBean<ChargeableSummary> chargeableDaily =
                    DailyChargeableSummaryBuilder.create(CdrBlobMapper.create()).build();
            reader.ensureProvisioned(chargeableDaily);             // MySQL: partitioned, the full set in the CREATE; PostgreSQL: plain
            reader.ensureProvisioned(chargeableDaily);             // idempotent over the existing table

            // sg10Entry = customer + supplier legs; sg11Entry = one leg -> 3 chargeable rows from 2 cdrs
            seedOutbox(1, CdrTestSupport.encodedBatch(List.of(
                    CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 10, 0)),
                    CdrTestSupport.sg11Entry(CdrTestSupport.at(2026, 6, 19, 15, 0)))));

            assertEquals(1, reader.drain(chargeableDaily), "same outbox stream, its own offset bookmark");

            assertEquals(3, count("sum_chargeable_day"), "every leg is a row: 2 SG10 legs + 1 SG11 leg");
            assertEquals(1, queryLong("select count(*) from sum_chargeable_day where tup_assigneddirection=2"),
                    "the supplier leg keys separately");
            assertEquals(0, new java.math.BigDecimal("3.8").compareTo(   // 1.0 customer + 0.8 supplier + 2.0 sg11
                    queryDecimal("select coalesce(sum(BilledAmount),0) from sum_chargeable_day")));
            assertEquals(1, offset("dailyChargeableSummary"), "independent bookmark from the voice beans");

            assertEquals(0, reader.drain(chargeableDaily), "re-drain is a no-op (exactly-once per bean)");
            assertEquals(3, count("sum_chargeable_day"));
        } finally {
            System.clearProperty("summary.ddl.partition-start");
            System.clearProperty("summary.ddl.partition-days");
        }
    }

    @Test
    void the_ad_beans_read_the_cdr_stream_and_carry_the_schema_they_are_drained_for() {
        System.setProperty("summary.ddl.partition-start", "2026-09-01");
        System.setProperty("summary.ddl.partition-days", "60");
        try {
            AdSummaryBean adDaily = AdTestSupport.dailyBean();
            reader.ensureProvisioned(adDaily);
            // ONE outbox row, as billing writes a batch: an ad view (group 30), its refused twin, and a voice call (group 10)
            String voice = CdrTestSupport.entryJson(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 9, 29, 10, 0)));
            seedOutbox(1, OutboxCodec.encode(AdTestSupport.batchJson(List.of(
                    AdTestSupport.leafView(AdTestSupport.at(2026, 9, 29, 10, 0)).json(),
                    AdTestSupport.refusedView(AdTestSupport.at(2026, 9, 29, 11, 0)).json(), voice))));

            assertEquals(1, reader.drain(adDaily), "the ad bean drains the call's own stream, entity cdr");

            assertEquals(2, count("sum_ad_day_30"), "the done view and the refused one key apart; the voice call is not an ad view");
            assertEquals(2, queryLong("select coalesce(sum(views),0) from sum_ad_day_30"));
            assertEquals(2, queryLong("select count(*) from sum_ad_day_30 where tup_tenant='" + schemaName() + "'"),
                    "tup_tenant is the schema's own name: the database this unit of work runs in");
            assertEquals(0, new java.math.BigDecimal("0.50").compareTo(
                    queryDecimal("select chargedamount from sum_ad_day_30 where tup_outcome='done'")));
            assertEquals(0, java.math.BigDecimal.ZERO.compareTo(queryDecimal("select sum(chargedunits) from sum_ad_day_30")),
                    "both views were paid in money: no units");
            assertEquals(1, queryLong("select failed from sum_ad_day_30 where tup_outcome='failed'"));
            assertEquals(1, offset("dailyAdSummary"), "its own bookmark on the shared stream");

            assertEquals(1, reader.drain(bean), "the voice bean reads the SAME row from its own bookmark");
            assertEquals(1, sumTotalCalls(), "and counts only its group-10 call");

            assertEquals(0, reader.drain(adDaily), "re-drain is a no-op (exactly once per bean)");
            assertEquals(2, queryLong("select coalesce(sum(views),0) from sum_ad_day_30"));
        } finally {
            System.clearProperty("summary.ddl.partition-start");
            System.clearProperty("summary.ddl.partition-days");
        }
    }

    @Test
    void two_apps_that_share_32_characters_keep_their_own_rows_in_the_real_table() {
        System.setProperty("summary.ddl.partition-start", "2026-09-01");
        System.setProperty("summary.ddl.partition-days", "60");
        try {
            AdSummaryBean adDaily = AdTestSupport.dailyBean();
            reader.ensureProvisioned(adDaily);
            String shared = "wifi-captive-portal-dhaka-north-";               // 32 characters
            String retail = shared + "retail-1", campus = shared + "campus-1";
            java.time.LocalDateTime t = AdTestSupport.at(2026, 9, 29, 10, 0);
            seedOutbox(1, OutboxCodec.encode(AdTestSupport.batchOf(AdTestSupport.leafView(t).app(retail), AdTestSupport.leafView(t).app(campus))));
            seedOutbox(2, OutboxCodec.encode(AdTestSupport.batchOf(AdTestSupport.leafView(t.plusHours(1)).app(retail))));

            assertEquals(2, reader.drain(adDaily));

            assertEquals(2, count("sum_ad_day_30"), "a VARCHAR(32) column would refuse a 40-character name, or merge the two");
            assertEquals(2, queryLong("select views from sum_ad_day_30 where tup_app='" + retail + "'"),
                    "the second batch RELOADED the row and merged into it: the stored name keys as the built one");
            assertEquals(1, queryLong("select views from sum_ad_day_30 where tup_app='" + campus + "'"));
        } finally {
            System.clearProperty("summary.ddl.partition-start");
            System.clearProperty("summary.ddl.partition-days");
        }
    }

    @Test
    void one_outbox_row_of_ad_views_feeds_the_ad_the_call_and_the_chargeable_tables() {
        System.setProperty("summary.ddl.partition-start", "2026-09-01");
        System.setProperty("summary.ddl.partition-days", "60");
        try {
            AdSummaryBean adDaily = AdTestSupport.dailyBean();
            SummaryBean<CallSummary> call30 = CallSummaries.forWindow("dailyCallSummarySg30", "daily", "30", 30, null);
            SummaryBean<ChargeableSummary> chargeable = DailyChargeableSummaryBuilder.create(CdrBlobMapper.create()).build();
            reader.ensureProvisioned(adDaily);
            reader.ensureProvisioned(call30);          // sum_voice_day_30: made by the bean, full partitions inside the CREATE
            reader.ensureProvisioned(chargeable);
            java.time.LocalDateTime t = AdTestSupport.at(2026, 9, 29, 10, 0);
            // shown and paid 0.50; admitted, never shown, still paid 0.50 (no return policy); refused, paid nothing
            seedOutbox(1, OutboxCodec.encode(AdTestSupport.batchOf(AdTestSupport.leafView(t),
                    AdTestSupport.leafView(t.plusMinutes(5)).admittedNeverShown(), AdTestSupport.refusedView(t.plusMinutes(9)))));

            assertEquals(1, reader.drain(adDaily));
            assertEquals(1, reader.drain(call30), "the same row, its own bookmark");
            assertEquals(1, reader.drain(chargeable), "the same row, its own bookmark");

            assertEquals(3, queryLong("select coalesce(sum(views),0) from sum_ad_day_30"));
            assertEquals(0, new java.math.BigDecimal("1.00").compareTo(queryDecimal("select sum(chargedamount) from sum_ad_day_30")));
            assertEquals(3, queryLong("select coalesce(sum(totalcalls),0) from sum_voice_day_30"), "the three views as calls");
            assertEquals(1, queryLong("select coalesce(sum(connectedcalls),0) from sum_voice_day_30"), "one was shown");
            assertEquals(0, new java.math.BigDecimal("1.00").compareTo(queryDecimal("select sum(customercost) from sum_voice_day_30")),
                    "0.50 + 0.50 + 0: the never-shown view's charge is there — no ChargingStatus early return for group 30");
            assertEquals(0, new java.math.BigDecimal("1.00").compareTo(
                    queryDecimal("select sum(BilledAmount) from sum_chargeable_day where tup_servicegroup=30")), "the three sums agree");
            assertEquals(3, queryLong("select coalesce(sum(totalcount),0) from sum_chargeable_day where tup_servicegroup=30"));

            assertEquals(0, reader.drain(call30), "re-drain is a no-op (exactly once per bean)");
            assertEquals(3, queryLong("select coalesce(sum(totalcalls),0) from sum_voice_day_30"));
        } finally {
            System.clearProperty("summary.ddl.partition-start");
            System.clearProperty("summary.ddl.partition-days");
        }
    }

    // ---- the edge's own rules (brief S5), the same on every engine ----

    @Test
    void a_crash_between_the_write_and_the_commit_leaves_nothing_and_the_replay_counts_once() {
        seedOutbox(1, CdrTestSupport.encodedBatch(List.of(
                CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 10, 0)),
                CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 15, 0)))));
        seedOutbox(2, CdrTestSupport.encodedBatch(List.of(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 20, 9, 0)))));
        // both rows in ONE transaction; the summaries and the bookmark are written; then the process dies
        CrashAtCommit dying = new CrashAtCommit(unitOfWorkFactory(), 1);
        OutboxReader doomed = new OutboxReader(dying, new SummaryEngine(), 1000, 50, 8);

        assertThrows(RuntimeException.class, () -> doomed.drainOnce(bean));

        assertEquals(1, dying.crashes, "the crash was at the commit, after the writes");
        assertEquals(0, offset("dailyCallSummary"), "the bookmark did not move");
        assertEquals(0, count(DAY_TABLE), "and no summary row survived: the two are one transaction");

        assertEquals(2, reader.drain(bean), "the restart reads the same two rows again");
        assertEquals(3, sumTotalCalls(), "each call counted exactly once");
        assertEquals(2, offset("dailyCallSummary"));
        assertEquals(0, reader.drain(bean));
    }

    @Test
    void a_crash_after_one_committed_step_replays_only_the_step_that_was_lost() {
        seedOutbox(1, CdrTestSupport.encodedBatch(List.of(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 10, 0)))));
        seedOutbox(2, CdrTestSupport.encodedBatch(List.of(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 11, 0)))));
        seedOutbox(3, CdrTestSupport.encodedBatch(List.of(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 12, 0)))));
        // one outbox row per transaction: the first commits, the process dies at the second commit
        CrashAtCommit dying = new CrashAtCommit(unitOfWorkFactory(), 2);
        OutboxReader stepByStep = new OutboxReader(dying, new SummaryEngine(), 1000, 1, 8);

        assertThrows(RuntimeException.class, () -> stepByStep.drain(bean));

        assertEquals(1, offset("dailyCallSummary"), "the first step is committed, the second is not");
        assertEquals(1, sumTotalCalls(), "the day window holds the first call only — the second step's UPDATE was rolled back");

        assertEquals(2, reader.drain(bean), "the restart reads rows 2 and 3, not row 1 again");
        assertEquals(3, sumTotalCalls(), "1 + 2: exactly once across the crash");
        assertEquals(1, count(DAY_TABLE), "one window, merged");
        assertEquals(3, offset("dailyCallSummary"));
    }

    @Test
    void a_text_with_a_backslash_and_a_quote_is_stored_as_written_and_reloads_under_the_same_key() {
        // the entities write their own SQL literals; the two engines read a backslash differently by default —
        // a value that came back changed would key apart from its own row and open a second one
        String app = "wi\\fi 'retail' c:\\ads\\";            // wi\fi 'retail' c:\ads\
        AdSummaryBean adDaily = AdTestSupport.dailyBean();
        provisionSmall(adDaily);
        java.time.LocalDateTime t = AdTestSupport.at(2026, 9, 29, 10, 0);
        seedOutbox(1, OutboxCodec.encode(AdTestSupport.batchOf(AdTestSupport.leafView(t).app(app).site("o'neil \\ 1"))));
        seedOutbox(2, OutboxCodec.encode(AdTestSupport.batchOf(AdTestSupport.leafView(t.plusHours(1)).app(app).site("o'neil \\ 1"))));

        assertEquals(2, reader.drain(adDaily), "row 2 RELOADS the window row 1 wrote");

        assertEquals(1, count("sum_ad_day_30"), "one key, one row");
        assertEquals(2, queryLong("select views from sum_ad_day_30"), "the reloaded row keyed as the built one, so it merged");
        assertEquals(app, queryText("select tup_app from sum_ad_day_30"), "stored character for character");
        assertEquals("o'neil \\ 1", queryText("select tup_site from sum_ad_day_30"));
    }

    @Test
    void many_windows_changed_in_one_drain_are_updated_in_joined_statements_and_every_one_lands() {
        // row 1 inserts five day windows; row 2 touches the same five -> five UPDATEs. With a segment of two they
        // go out as three round trips of ;-joined statements (2 + 2 + 1): every one must be applied and counted
        OutboxReader smallSegments = new OutboxReader(unitOfWorkFactory(), new SummaryEngine(), 2, 50, 8);
        List<com.telcobright.summary.summarybeans.call.model.CdrBlobEntry> fiveDays = new java.util.ArrayList<>();
        for (int day = 0; day < 5; day++) fiveDays.add(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19 + day, 10, 0)));
        seedOutbox(1, CdrTestSupport.encodedBatch(fiveDays));
        seedOutbox(2, CdrTestSupport.encodedBatch(fiveDays));

        assertEquals(2, smallSegments.drain(bean));

        assertEquals(5, count(DAY_TABLE));
        assertEquals(10, sumTotalCalls());
        assertEquals(5, queryLong("select count(*) from " + DAY_TABLE + " where totalcalls = 2"), "each of the five windows was updated");
    }

    @Test
    void a_window_is_cut_on_the_tenants_wall_clock_whatever_zone_the_jvm_runs_in() {
        // the cdr's times are the TENANT's wall clock (Asia/Dhaka). A container runs in UTC: 00:30 in Dhaka is
        // 18:30 of the day before there — a zone conversion anywhere on the way would file the view under the wrong day
        java.util.TimeZone jvmZone = java.util.TimeZone.getDefault();
        AdSummaryBean adDaily = AdTestSupport.dailyBean();
        AdSummaryBean adHourly = AdTestSupport.hourlyBean();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
            provisionSmall(adDaily);
            provisionSmall(adHourly);
            seedOutbox(1, OutboxCodec.encode(AdTestSupport.batchOf(AdTestSupport.leafView(AdTestSupport.at(2026, 10, 3, 0, 30)))));
            assertEquals(1, reader.drain(adDaily));
            assertEquals(1, reader.drain(adHourly));

            assertEquals(1, queryLong("select count(*) from sum_ad_day_30 where tup_starttime = '2026-10-03 00:00:00'"), "Dhaka's 3rd, not UTC's 2nd");
            assertEquals(1, queryLong("select count(*) from sum_ad_hr_30 where tup_starttime = '2026-10-03 00:00:00'"), "the hour 00, not 18");

            // the same service, restarted in another zone, reloads the window it wrote and merges into it
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"));
            seedOutbox(2, OutboxCodec.encode(AdTestSupport.batchOf(AdTestSupport.leafView(AdTestSupport.at(2026, 10, 3, 23, 45)))));
            assertEquals(1, reader.drain(adDaily));
            assertEquals(1, reader.drain(adHourly));

            assertEquals(1, count("sum_ad_day_30"), "23:45 of the 3rd is still the 3rd: the reloaded window keyed the same");
            assertEquals(2, queryLong("select views from sum_ad_day_30 where tup_starttime = '2026-10-03 00:00:00'"));
            assertEquals(1, queryLong("select count(*) from sum_ad_hr_30 where tup_starttime = '2026-10-03 23:00:00'"));
        } finally {
            java.util.TimeZone.setDefault(jvmZone);
        }
    }

    /** Make a bean's table with a small MySQL partition horizon around the tests' dates (PostgreSQL has none). */
    protected void provisionSmall(SummaryBean<?> selfProvisioned) {
        System.setProperty("summary.ddl.partition-start", "2026-09-01");
        System.setProperty("summary.ddl.partition-days", "60");
        try {
            reader.ensureProvisioned(selfProvisioned);
        } finally {
            System.clearProperty("summary.ddl.partition-start");
            System.clearProperty("summary.ddl.partition-days");
        }
    }

    // ---- helpers ----

    /** Mirrors OutboxReaper.reapOnce over a real UnitOfWork: min(last_offset) across the active set, then delete. */
    private long reapLike(Set<String> activeBeans) {
        UnitOfWork unitOfWork = unitOfWorkFactory().begin();
        try {
            long min = unitOfWork.outbox().minOffset("cdr", activeBeans);
            int deleted = min > 0 ? unitOfWork.outbox().deleteUpTo("cdr", min) : 0;
            unitOfWork.commit();
            return deleted;
        } finally {
            unitOfWork.close();
        }
    }

    protected void seedOutbox(long id, String data) {
        seedOutbox(id, "add", data);
    }

    /** Written as billing-core writes it: its own connection, the id it was given. */
    protected void seedOutbox(long id, String op, String data) {
        String sql = "insert into summary_affected(id, entity_type, op, data) values(?, 'cdr', ?, ?)";
        try (Connection c = billingConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, op);
            ps.setString(3, data);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("seed failed", e);
        }
    }

    protected long count(String table) {
        return queryLong("select count(*) from " + table);
    }

    protected long sumTotalCalls() {
        return queryLong("select coalesce(sum(totalcalls),0) from " + DAY_TABLE);
    }

    protected long offset(String beanName) {
        return queryLong("select coalesce(max(last_offset),0) from summary_offset where bean_name='" + beanName + "'");
    }

    protected long queryLong(String sql) {
        try (Connection c = dbConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    protected java.math.BigDecimal queryDecimal(String sql) {
        try (Connection c = dbConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getBigDecimal(1);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    protected String queryText(String sql) {
        try (Connection c = dbConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    protected static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
