package com.telcobright.summary.summarybeans.call;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.summarybeans.call.model.CallSummary;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.WifiSessionTestSupport;
import com.telcobright.summary.testkit.WifiSessionTestSupport.Session;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static com.telcobright.summary.testkit.WifiSessionTestSupport.at;
import static com.telcobright.summary.testkit.WifiSessionTestSupport.batchOf;
import static com.telcobright.summary.testkit.WifiSessionTestSupport.daily101;
import static com.telcobright.summary.testkit.WifiSessionTestSupport.hourly101;
import static com.telcobright.summary.testkit.WifiSessionTestSupport.rollup;
import static com.telcobright.summary.testkit.WifiSessionTestSupport.rowInUnit;
import static com.telcobright.summary.testkit.WifiSessionTestSupport.session;
import static com.telcobright.summary.testkit.WifiSessionTestSupport.sessionOfZeroSeconds;
import static com.telcobright.summary.testkit.WifiSessionTestSupport.sessionPaidInMoney;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ARCH-0077-S item 1: the CALL summary takes service group 101 — the domestic WiFi session summed as a domestic call
 * under {@code sum_voice_day_101} / {@code sum_voice_hr_101} through the config-instantiated call bean
 * ({@code service-group: 101}, {@code table-suffix: "101"}). The input is the outbox entry billing-core writes for a
 * session (BC-0007): the voice cdr row with {@code ServiceGroup} 101 and ONE customer chargeable from the switch's
 * settlement. The branch is the 30 branch's stamps WITHOUT the ad's route key and WITH the voice's
 * {@code ChargingStatus} early return: a session of 0 seconds is counted, not costed.
 */
class CallSummaryGroup101Test {

    private static final LocalDateTime MORNING = at(2026, 10, 10, 9, 0);

    @Test
    void the_config_instantiated_bean_of_group_101_writes_the_101_tables() {
        assertEquals("sum_voice_day_101", daily101().table());
        assertEquals("sum_voice_hr_101", hourly101().table());
        assertEquals("cdr", daily101().entityType(), "the same outbox stream as every call bean");
    }

    @Test
    void a_session_is_one_call_by_its_subscribers_partner_and_the_site_it_came_in_on() {
        CallSummary s = daily101().buildBatch(batchOf(session(MORNING))).get(0);

        assertEquals(7, s.tup_inpartnerid, "the payer: the subscriber's tree partner");
        assertEquals(0, s.tup_outpartnerid, "the wire sends no out-partner");
        assertEquals("site-mirpur-10", s.tup_incomingroute, "the site / gateway the session came in on");
        assertEquals("", s.tup_outgoingroute, "the wire sends no outgoing route");
        assertEquals(LocalDateTime.of(2026, 10, 10, 0, 0), s.tup_starttime);
        assertEquals(1, s.totalcalls, "one session");
        assertEquals(1, s.connectedcalls, "answered");
        assertEquals(1, s.successfulcalls, "ChargingStatus: 1 = the voice rule, duration > 0");
        assertEquals(0, s.actualduration.compareTo(new BigDecimal("1800")), "the session's seconds");
        assertEquals(0, s.roundedduration.compareTo(new BigDecimal("1800")));
        assertEquals(0, s.duration1.compareTo(new BigDecimal("1800")));
    }

    @Test
    void the_charge_is_the_settlements_minutes_in_their_own_unit_at_the_reference_rate_and_the_rows_empty_prefix() {
        CallSummary s = daily101().buildBatch(batchOf(session(MORNING))).get(0);

        assertEquals(0, s.tup_customerrate.compareTo(new BigDecimal("0.10")), "unitPriceOrCharge: the reference rate, as the row carries it");
        assertEquals(6, s.tup_customerrate.scale(), "a key decimal is brought to the column's 6 places");
        assertEquals("TF_min", s.tup_customercurrency, "idBilledUom: the unit rides into the summary as the row carries it — never converted");
        assertEquals(0, s.customercost.compareTo(new BigDecimal("30")), "BilledAmount: the minutes taken from the bucket");
        assertEquals("", s.tup_matchedprefixcustomer, "Cdr.MatchedPrefixCustomer: a session has none");
    }

    @Test
    void a_session_the_tier_paid_in_money_is_a_money_row() {
        CallSummary s = daily101().buildBatch(batchOf(sessionPaidInMoney(MORNING))).get(0);

        assertEquals("BDT", s.tup_customercurrency);
        assertEquals(0, s.customercost.compareTo(new BigDecimal("3.00")), "BilledAmount: the money");
    }

    @Test
    void minutes_and_money_key_apart_and_are_never_summed_into_one_row() {
        Collection<CallSummary> rows = rollup(daily101(), List.of(session(MORNING), session(MORNING.plusMinutes(40)), sessionPaidInMoney(MORNING.plusMinutes(50))));

        assertEquals(2, rows.size(), "the unit is the row's currency, a key column");
        CallSummary minutes = rowInUnit(rows, "TF_min");
        CallSummary money = rowInUnit(rows, "BDT");
        assertEquals(2, minutes.totalcalls);
        assertEquals(0, minutes.customercost.compareTo(new BigDecimal("60")), "30 + 30 minutes");
        assertEquals(1, money.totalcalls);
        assertEquals(0, money.customercost.compareTo(new BigDecimal("3.00")));
    }

    @Test
    void a_session_of_zero_seconds_is_counted_and_not_costed_the_voice_early_return() {
        // ChargingStatus 0 (no traffic), the answer time still on the record, the settlement's chargeable 0 TF_min
        CallSummary s = daily101().buildBatch(batchOf(sessionOfZeroSeconds(MORNING))).get(0);

        assertEquals(1, s.totalcalls, "counted");
        assertEquals(1, s.connectedcalls, "answered");
        assertEquals(0, s.successfulcalls, "not charged");
        assertEquals(0, s.actualduration.signum());
        assertEquals(0, s.customercost.signum(), "not costed");
        assertEquals(0, s.tup_customerrate.signum(), "no rate stamped: the early return, as groups 10 and 11 have it");
        assertEquals("", s.tup_customercurrency, "no unit stamped: no row of 0 TF_min is opened for it");
    }

    @Test
    void a_session_of_zero_seconds_keys_as_an_uncharged_call_beside_the_minutes_row() {
        Collection<CallSummary> rows = rollup(daily101(), List.of(session(MORNING), sessionOfZeroSeconds(MORNING.plusMinutes(5))));

        assertEquals(2, rows.size());
        assertEquals(1, rowInUnit(rows, "TF_min").totalcalls);
        assertEquals(0, rowInUnit(rows, "TF_min").customercost.compareTo(new BigDecimal("30")), "the costed row holds only the charged session");
        assertEquals(1, rowInUnit(rows, "").totalcalls);
        assertEquals(0, rowInUnit(rows, "").customercost.signum());
    }

    @Test
    void nothing_is_bought_and_no_tax_is_on_a_session() {
        CallSummary s = daily101().buildBatch(batchOf(session(MORNING))).get(0);

        assertEquals(0, s.suppliercost.signum());
        assertEquals(0, s.tup_supplierrate.signum());
        assertEquals("", s.tup_suppliercurrency);
        assertEquals("", s.tup_matchedprefixsupplier);
        assertEquals(0, s.tax1.signum());
        assertEquals(0, s.tax2.signum());
        assertEquals(0, s.vat.signum());
        assertEquals("", s.tup_tax1currency);
        assertEquals("", s.tup_tax2currency);
        assertEquals("", s.tup_vatcurrency);
        assertEquals("", s.tup_sourceId);
        assertEquals("", s.tup_destinationId);
        assertEquals(0, s.longDecimalAmount1.signum(), "no ans cost");
    }

    @Test
    void a_route_a_producer_sends_is_kept_whole_the_ads_rule_slash_app_key_is_the_ads_alone() {
        String device = "7/wifi/dhaka-01/dhaka-site-1/dhaka/gw-1/8801711000002/aa:bb:cc:dd:ee:02";

        CallSummary s = daily101().buildBatch(batchOf(session(MORNING).route(device))).get(0);

        assertEquals(device, s.tup_outgoingroute, "the whole text, as groups 10 and 11 keep theirs (S18 is the 30 branch's)");
    }

    @Test
    void the_package_stamps_are_taken_when_the_row_carries_them_in_the_legacy_fields_and_are_zero_otherwise() {
        // SG10's stamps: longDecimalAmount2 = the package amount from AdditionalSystemCodes, intAmount1 = the idpackage from
        // AdditionalPartyNumber. billing-core's 101 row carries the minutes in PackageAmount / IdPackageAccount instead —
        // columns the blob's Cdr model does not read (SS-0005, a finding for the architect) — so a plain row stamps 0
        CallSummary stamped = daily101().buildBatch(batchOf(session(MORNING).legacyPackageStamps("30", "5101"))).get(0);
        CallSummary plain = daily101().buildBatch(batchOf(session(MORNING))).get(0);

        assertEquals(0, stamped.longDecimalAmount2.compareTo(new BigDecimal("30")), "the package amount");
        assertEquals(5101, stamped.intAmount1, "the idpackage");
        assertEquals(0, plain.longDecimalAmount2.signum());
        assertEquals(0, plain.intAmount1);
    }

    @Test
    void the_package_stamps_are_not_taken_for_a_session_of_zero_seconds() {
        CallSummary s = daily101().buildBatch(batchOf(sessionOfZeroSeconds(MORNING).legacyPackageStamps("30", "5101"))).get(0);

        assertEquals(0, s.longDecimalAmount2.signum(), "behind the early return, as SG10 has them");
        assertEquals(0, s.intAmount1);
    }

    @Test
    void the_hourly_bean_buckets_the_session_on_its_start_hour() {
        CallSummary s = hourly101().buildBatch(batchOf(session(at(2026, 10, 10, 14, 30)))).get(0);

        assertEquals(LocalDateTime.of(2026, 10, 10, 14, 0), s.tup_starttime);
    }

    @Test
    void the_bean_of_group_101_leaves_the_views_and_the_voice_calls_alone_and_they_leave_the_sessions_alone() {
        String voice10 = CdrTestSupport.entryJson(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 10, 10, 9, 0)));
        String view30 = AdTestSupport.leafView(AdTestSupport.at(2026, 10, 10, 9, 0)).json();
        byte[] mixed = AdTestSupport.batchJson(List.of(voice10, view30, session(MORNING).json()));
        SummaryBean<CallSummary> daily30 = CallSummaries.forWindow("dailyCallSummarySg30", "daily", "30", 30, null);

        assertEquals(1, daily101().buildBatch(mixed).size(), "the one session");
        assertEquals("TF_min", daily101().buildBatch(mixed).get(0).tup_customercurrency);
        assertEquals(1, daily30.buildBatch(mixed).size(), "the group-30 bean: its one view");
        assertEquals("BDT", daily30.buildBatch(mixed).get(0).tup_customercurrency);
        assertEquals(1, CdrTestSupport.dailyBean().buildBatch(mixed).size(), "the group-10 bean: its one call");
        assertEquals(0, AdTestSupport.dailyBean().buildBatch(mixed, "btcl").size() - 1, "the ad bean: its one view, not the session");
    }

    @Test
    void ten_sessions_are_ten_deltas_merged_by_the_engine_into_one_row() {
        Session[] sessions = new Session[10];
        for (int i = 0; i < 10; i++) sessions[i] = session(MORNING.plusMinutes(31L * i));

        assertEquals(10, daily101().buildBatch(batchOf(sessions)).size(), "one delta per session");
        Collection<CallSummary> rows = rollup(daily101(), List.of(sessions));
        assertEquals(1, rows.size());
        assertEquals(10, rows.iterator().next().totalcalls);
        assertEquals(0, rows.iterator().next().customercost.compareTo(new BigDecimal("300")), "300 minutes");
    }
}
