package com.telcobright.summary.registry.internal;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.LogCapture;
import com.telcobright.summary.testkit.WifiSessionTestSupport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.logging.Level;

import static com.telcobright.summary.testkit.CdrTestSupport.at;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SS-0008 item 2: the cdr rows of a kind the tenant did not enable are COUNTED — once per row, by the one counting bean's
 * committed drains — and said: one WARN per tenant, kind and day ({@code KIND_NOT_ENABLED <kind> <n> rows}), the day's total
 * in an INFO when the day turns.
 */
class KindGateTest {

    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    private static final SummaryBean<?> COUNTING = CdrTestSupport.dailyBean();     // dailyCallSummary
    private static final SummaryBean<?> ANOTHER = CdrTestSupport.hourlyBean();     // hourlyCallSummary

    /** A clock that can be moved: the gate reads the day from it. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(String instant) {
            this.now = Instant.parse(instant);
        }

        void moveTo(String instant) {
            now = Instant.parse(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            MovableClock self = this;
            return new Clock() {
                @Override public ZoneId getZone() { return zone; }
                @Override public Clock withZone(ZoneId other) { return self.withZone(other); }
                @Override public Instant instant() { return self.now; }
            };
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** ONE outbox row as billing writes it: 2 voice calls, 3 ad views (one carrying no chargeable), 1 WiFi session, 1 record of group 4. */
    private static byte[] aMixedRow() {
        return ("[" + String.join(",",
                CdrTestSupport.entryJson(CdrTestSupport.sg10Entry(at(2026, 10, 24, 9, 0))),
                CdrTestSupport.entryJson(CdrTestSupport.sg11Entry(at(2026, 10, 24, 9, 5))),
                AdTestSupport.leafView(at(2026, 10, 24, 21, 14)).json(),
                AdTestSupport.rootView(at(2026, 10, 24, 21, 14)).json(),
                AdTestSupport.leafView(at(2026, 10, 24, 21, 15)).noChargeable().json(),
                WifiSessionTestSupport.measuredSession(at(2026, 10, 24, 9, 2)).json(),
                CdrTestSupport.entryJson(new com.telcobright.summary.summarybeans.call.model.CdrBlobEntry(
                        CdrTestSupport.sg10Entry(at(2026, 10, 24, 9, 9)).cdr(), List.of(new com.telcobright.summary.summarybeans.call.model.Chargeable(
                        4, 4, 1, 1L, "BDT", "1714", at(2026, 10, 24, 9, 9), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))))
        ) + "]").getBytes(StandardCharsets.UTF_8);
    }

    private static KindGate gate(MovableClock clock, List<String> kinds) {
        KindGate gate = new KindGate(clock);
        gate.countFor(TenantKinds.of(kinds), COUNTING.name(), DHAKA);
        return gate;
    }

    @Test
    void only_the_one_counting_bean_counts_and_only_when_a_kind_is_not_enabled() {
        MovableClock clock = new MovableClock("2026-10-24T03:00:00Z");

        assertTrue(gate(clock, List.of()).counts("btcl", COUNTING), "a call tenant: the counting bean counts");
        assertFalse(gate(clock, List.of()).counts("btcl", ANOTHER), "no other bean: a row is counted once, whatever number of beans read it");
        assertFalse(gate(clock, List.of("call", "ad", "wifi")).counts("btcl", COUNTING), "every kind enabled: there is nothing to count");
        KindGate noBean = new KindGate(clock);
        noBean.countFor(TenantKinds.of(List.of()), null, DHAKA);
        assertFalse(noBean.counts("btcl", COUNTING), "no cdr bean enabled: nobody counts");
        assertFalse(new KindGate(clock).counts("btcl", COUNTING), "untold (tests, embedders): counts nothing");
    }

    @Test
    void a_record_is_the_kind_of_its_customer_legs_group_else_its_cdrs_and_only_the_kinds_not_enabled_are_counted() {
        KindGate callTenant = gate(new MovableClock("2026-10-24T03:00:00Z"), List.of());

        var tally = callTenant.tally(List.of(aMixedRow()));

        assertEquals(2, tally.size(), "the ad and the wifi kinds; the voice calls and the group-4 record are the call's: " + tally);
        assertEquals(3, tally.get(KnownKinds.byWord("ad")), "3 views — the one with no chargeable by its Cdr.ServiceGroup 30");
        assertEquals(1, tally.get(KnownKinds.byWord("wifi")));
        assertTrue(gate(new MovableClock("2026-10-24T03:00:00Z"), List.of("call", "ad")).tally(List.of(aMixedRow())).keySet()
                .equals(java.util.Set.of(KnownKinds.byWord("wifi"))), "a call + ad tenant counts the WiFi session alone");
        assertEquals(java.util.Set.of(KnownKinds.byWord("call"), KnownKinds.byWord("wifi")),
                gate(new MovableClock("2026-10-24T03:00:00Z"), List.of("ad")).tally(List.of(aMixedRow())).keySet(), "an ad tenant counts the call's and the WiFi's");
        assertEquals(3, gate(new MovableClock("2026-10-24T03:00:00Z"), List.of("ad")).tally(List.of(aMixedRow())).get(KnownKinds.byWord("call")),
                "the two calls and the group-4 record");
    }

    @Test
    void one_warn_per_tenant_kind_and_day_and_the_days_total_when_the_day_turns() {
        MovableClock clock = new MovableClock("2026-10-23T19:30:00Z");   // 01:30 of the 24th in Dhaka: the tenant's day
        KindGate gate = gate(clock, List.of());
        LocalDate the24th = LocalDate.of(2026, 10, 24);

        try (LogCapture said = LogCapture.of(KindGate.class)) {
            gate.committed("btcl", List.of(aMixedRow()));
            gate.committed("btcl", List.of(aMixedRow(), aMixedRow()));
            gate.committed("res_44", List.of(aMixedRow()));

            List<String> warnings = said.warnings();
            assertEquals(4, warnings.size(), "one per tenant and kind (btcl ad, btcl wifi, res_44 ad, res_44 wifi): " + warnings);
            assertTrue(warnings.get(0).startsWith("KIND_NOT_ENABLED ad 3 rows — tenant btcl, day 2026-10-24: the cdr rows of the ad kind "
                    + "(ad = service group 30) are not summed here, call (summary.kinds names none: the call alone, as before kinds); "
                    + "their outbox rows are consumed as every other."), warnings.get(0));
            assertTrue(warnings.get(1).startsWith("KIND_NOT_ENABLED wifi 1 rows — tenant btcl, day 2026-10-24"), warnings.get(1));
            assertTrue(warnings.get(2).startsWith("KIND_NOT_ENABLED ad 3 rows — tenant res_44, day 2026-10-24"), warnings.get(2));
            assertEquals(9, gate.rowsNotSummed("btcl", "ad", the24th), "3 rows in each of the 3 outbox rows of btcl");
            assertEquals(3, gate.rowsNotSummed("btcl", "wifi", the24th));
            assertEquals(3, gate.rowsNotSummed("res_44", "ad", the24th));

            clock.moveTo("2026-10-24T18:30:00Z");                        // 00:30 of the 25th in Dhaka
            gate.committed("btcl", List.of(aMixedRow()));

            assertEquals(6, said.warnings().size(), "a new day: a new WARN per kind for btcl");
            assertTrue(said.warnings().get(4).startsWith("KIND_NOT_ENABLED ad 3 rows — tenant btcl, day 2026-10-25"), said.warnings().get(4));
            assertTrue(said.records(Level.INFO).contains("KIND_NOT_ENABLED ad 9 rows in all — tenant btcl, day 2026-10-24 (the day's total; not summed)"),
                    "the day that ended: its total: " + said.records(Level.INFO));
            assertTrue(said.records(Level.INFO).contains("KIND_NOT_ENABLED wifi 3 rows in all — tenant btcl, day 2026-10-24 (the day's total; not summed)"));
            assertEquals(3, gate.rowsNotSummed("btcl", "ad", LocalDate.of(2026, 10, 25)));

            gate.sayTheTotals();
            assertTrue(said.records(Level.INFO).contains("KIND_NOT_ENABLED ad 3 rows in all — tenant btcl, day 2026-10-25 (the day's total; not summed)"),
                    "at stop: the running day's total: " + said.records(Level.INFO));
            assertTrue(said.records(Level.INFO).contains("KIND_NOT_ENABLED ad 3 rows in all — tenant res_44, day 2026-10-24 (the day's total; not summed)"));
        }
    }

    @Test
    void with_every_kind_enabled_nothing_is_counted_or_said() {
        KindGate gate = gate(new MovableClock("2026-10-24T03:00:00Z"), List.of("call", "ad", "wifi"));

        try (LogCapture said = LogCapture.of(KindGate.class)) {
            gate.committed("btcl", List.of(aMixedRow()));

            assertTrue(said.lines().isEmpty(), said.lines().toString());
        }
        assertEquals(0, gate.rowsNotSummed("btcl", "ad", LocalDate.of(2026, 10, 24)));
    }

    @Test
    void a_row_that_cannot_be_read_never_throws_into_the_drain_it_is_said() {
        KindGate gate = gate(new MovableClock("2026-10-24T03:00:00Z"), List.of());

        try (LogCapture said = LogCapture.of(KindGate.class)) {
            assertDoesNotThrow(() -> gate.committed("btcl", List.of("not json".getBytes(StandardCharsets.UTF_8))));

            assertEquals(1, said.warnings().size());
            assertTrue(said.warnings().get(0).startsWith("schema=btcl the kinds of a committed outbox row could not be read — its rows are not counted"),
                    said.warnings().get(0));
        }
    }
}
