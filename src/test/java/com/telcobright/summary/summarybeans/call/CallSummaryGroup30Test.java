package com.telcobright.summary.summarybeans.call;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.engine.internal.SummaryCache;
import com.telcobright.summary.engine.spi.MergeMode;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.View;
import com.telcobright.summary.summarybeans.call.model.CallSummary;
import com.telcobright.summary.testkit.CdrTestSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.at;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.batchOf;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.leafView;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.refusedView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Brief S4: the CALL summary takes service group 30 — {@code sum_voice_day_30} / {@code sum_voice_hr_30} through
 * the config-instantiated call bean ({@code service-group: 30}, {@code table-suffix: "30"}): views by advertiser,
 * by the campaign's route, by zone. The input is the SAME outbox entry the ad beans read. The brief said "no
 * code"; the builder had no branch for 30 and threw — the branch is here, and it is the architect's ruling
 * (answer to SS-0001, decide 2): the customer-direction stamp from the one pre-rated chargeable, and NO
 * {@code ChargingStatus} early return — an admitted view is charged whether it was shown or not.
 */
class CallSummaryGroup30Test {

    private static final LocalDateTime MORNING = at(2026, 9, 29, 10, 0);

    private static SummaryBean<CallSummary> daily30() {
        return CallSummaries.forWindow("dailyCallSummarySg30", "daily", "30", 30, null);
    }

    private static SummaryBean<CallSummary> hourly30() {
        return CallSummaries.forWindow("hourlyCallSummarySg30", "hourly", "30", 30, null);
    }

    @Test
    void the_config_instantiated_bean_of_group_30_writes_the_30_tables() {
        assertEquals("sum_voice_day_30", daily30().table());
        assertEquals("sum_voice_hr_30", hourly30().table());
        assertEquals("cdr", daily30().entityType(), "the same outbox stream as every call bean");
    }

    @Test
    void an_ad_view_is_one_call_by_its_payer_its_campaigns_route_and_its_zone() {
        CallSummary s = daily30().buildBatch(batchOf(leafView(MORNING))).get(0);

        assertEquals(61, s.tup_inpartnerid, "the payer");
        assertEquals(9, s.tup_outpartnerid, "the network division");
        assertEquals("lux-soap", s.tup_incomingroute, "the campaign's route");
        assertEquals("dhaka-01", s.tup_outgoingroute, "a row from before ARCH-0055 (the zone alone): the whole text, nothing invented (S18)");
        assertEquals("10.20.0.1", s.tup_incomingip, "the gateway — never the device");
        assertEquals("10.10.188.40", s.tup_outgoingip, "the media server");
        assertEquals(LocalDateTime.of(2026, 9, 29, 0, 0), s.tup_starttime);
        assertEquals(1, s.totalcalls, "one view");
        assertEquals(1, s.connectedcalls, "connected = shown");
        assertEquals(1, s.successfulcalls, "ChargingStatus: 1 = shown");
        assertEquals(0, s.actualduration.compareTo(new BigDecimal("15")), "the seconds watched");
        assertEquals(0, s.roundedduration.compareTo(new BigDecimal("15")));
        assertEquals(0, s.duration1.compareTo(new BigDecimal("15")));
    }

    @Test
    void the_charge_is_the_customer_chargeables_rate_unit_amount_and_the_cdrs_prefix() {
        CallSummary s = daily30().buildBatch(batchOf(leafView(MORNING))).get(0);

        assertEquals(0, s.tup_customerrate.compareTo(new BigDecimal("0.50")), "unitPriceOrCharge");
        assertEquals(6, s.tup_customerrate.scale(), "a key decimal is brought to the column's 6 places");
        assertEquals("BDT", s.tup_customercurrency, "idBilledUom");
        assertEquals(0, s.customercost.compareTo(new BigDecimal("0.50")), "BilledAmount");
        assertEquals("10", s.tup_matchedprefixcustomer, "Cdr.MatchedPrefixCustomer");
    }

    @Test
    void nothing_is_bought_and_no_tax_is_on_an_ad_view() {
        CallSummary s = daily30().buildBatch(batchOf(leafView(MORNING))).get(0);

        assertEquals(0, s.suppliercost.signum());
        assertEquals(0, s.tup_supplierrate.signum());
        assertEquals("", s.tup_suppliercurrency);
        assertEquals("", s.tup_matchedprefixsupplier);
        assertEquals(0, s.tax1.signum());
        assertEquals(0, s.tax2.signum());
        assertEquals(0, s.vat.signum());
        assertEquals("", s.tup_tax1currency);
        assertEquals("", s.tup_vatcurrency);
        assertEquals("", s.tup_sourceId);
        assertEquals("", s.tup_destinationId);
    }

    @Test
    void an_admitted_view_that_was_never_shown_keeps_its_charge_there_is_no_charging_status_early_return() {
        // ChargingStatus 0 (not shown), 0 seconds — and 0.50 charged all the same: the chargeable is the truth
        CallSummary s = daily30().buildBatch(batchOf(leafView(MORNING).admittedNeverShown())).get(0);

        assertEquals(0, s.connectedcalls, "never shown");
        assertEquals(0, s.successfulcalls);
        assertEquals(0, s.actualduration.signum());
        assertEquals(0, s.customercost.compareTo(new BigDecimal("0.50")), "the charge is NOT dropped as groups 10 and 11 drop an uncharged call's");
        assertEquals(0, s.tup_customerrate.compareTo(new BigDecimal("0.50")));
        assertEquals("BDT", s.tup_customercurrency);
    }

    @Test
    void a_refused_view_counts_as_one_call_with_a_cost_of_zero() {
        CallSummary s = daily30().buildBatch(batchOf(refusedView(MORNING))).get(0);

        assertEquals(1, s.totalcalls);
        assertEquals(0, s.connectedcalls);
        assertEquals(0, s.customercost.signum());
        assertEquals("BDT", s.tup_customercurrency, "its zero chargeable carries BDT");
    }

    @Test
    void a_tier_that_pays_in_a_packages_units_keys_apart_from_the_money_rows() {
        Collection<CallSummary> rows = rollup(daily30(), List.of(leafView(MORNING), leafView(MORNING.plusMinutes(5)),
                leafView(MORNING.plusMinutes(9)).uom("TF_s").charge("15")));

        assertEquals(2, rows.size(), "the unit is the row's currency, a key column: units and money are never one row");
        CallSummary money = rows.stream().filter(r -> r.tup_customercurrency.equals("BDT")).findFirst().orElseThrow();
        CallSummary units = rows.stream().filter(r -> r.tup_customercurrency.equals("TF_s")).findFirst().orElseThrow();
        assertEquals(2, money.totalcalls);
        assertEquals(0, money.customercost.compareTo(new BigDecimal("1.00")));
        assertEquals(1, units.totalcalls);
        assertEquals(0, units.customercost.compareTo(new BigDecimal("15")));
    }

    // ---- S18: the group-30 key of the ad's route is <ruleId>/<app> — never the device (SS-0003 §2, §2a) ----

    /** The route as the ad service writes it since ARCH-0055: the rule, then app, zone, site, district, gw, msisdn, mac. */
    private static final String PHONE_A = "7/wifi/dhaka-01/dhaka-site-1/dhaka/gw-1/8801711000001/aa:bb:cc:dd:ee:01";
    private static final String PHONE_B = "7/wifi/dhaka-01/dhaka-site-1/dhaka/gw-1/8801711000002/aa:bb:cc:dd:ee:02";

    @Test
    void two_devices_of_the_same_rule_and_app_summarise_into_ONE_row_keyed_ruleId_slash_app() {
        Collection<CallSummary> rows = rollup(daily30(), List.of(leafView(MORNING).route(PHONE_A), leafView(MORNING.plusMinutes(3)).route(PHONE_B)));

        assertEquals(1, rows.size(), "one row per rule and app per window — never one per phone");
        CallSummary row = rows.iterator().next();
        assertEquals("7/wifi", row.tup_outgoingroute, "the route's first two parts, as the row carries them");
        assertEquals(2, row.totalcalls);
    }

    @Test
    void a_route_longer_than_64_characters_is_not_cut_inside_a_part_the_key_is_whole_parts() {
        String app = "a".repeat(40);
        String longRoute = "12345/" + app + "/" + "z".repeat(30) + "/" + "s".repeat(30) + "/dhaka/gw-1/8801711000001/aa:bb:cc:dd:ee:01";
        assertTrue(longRoute.length() > 64, "longer than the key's old width");

        CallSummary s = daily30().buildBatch(batchOf(leafView(MORNING).route(longRoute))).get(0);

        assertEquals("12345/" + app, s.tup_outgoingroute, "whole parts, whatever the route's length");
    }

    @Test
    void no_rule_matched_keys_as_zero_slash_app() {
        CallSummary s = daily30().buildBatch(batchOf(leafView(MORNING).route("0/wifi//////"))).get(0);

        assertEquals("0/wifi", s.tup_outgoingroute);
    }

    @Test
    void an_encoded_app_stays_one_part_of_the_key_as_the_row_carries_it() {
        // the ad service percent-encodes a value's slash: an app named a/b is ONE part, still encoded in the key (ARCH-0057)
        CallSummary s = daily30().buildBatch(batchOf(leafView(MORNING).route("7/a%2Fb/dhaka-01/////"))).get(0);

        assertEquals("7/a%2Fb", s.tup_outgoingroute);
    }

    @Test
    void a_route_not_of_the_fixed_shape_keeps_the_whole_text_as_its_key_nothing_is_invented() {
        assertEquals("7/wifi/dhaka-01", daily30().buildBatch(batchOf(leafView(MORNING).route("7/wifi/dhaka-01"))).get(0).tup_outgoingroute,
                "three parts, not eight: not a route of the fixed shape");
        assertEquals("x/wifi/a/b/c/d/e/f", daily30().buildBatch(batchOf(leafView(MORNING).route("x/wifi/a/b/c/d/e/f"))).get(0).tup_outgoingroute,
                "eight parts but no rule id in front: not one either");
        assertEquals("", daily30().buildBatch(batchOf(leafView(MORNING).route(""))).get(0).tup_outgoingroute, "an empty route stays empty");
    }

    @Test
    void a_group_10_records_route_is_still_the_whole_string() {
        SummaryBean<CallSummary> daily10 = CallSummaries.forWindow("dailyCallSummary", "daily", "10", 10, null);

        CallSummary s = daily10.buildBatch(batchOf(leafView(MORNING).serviceGroup(10).route(PHONE_A))).get(0);

        assertEquals(PHONE_A, s.tup_outgoingroute, "groups 10, 11 (and 15) are untouched by S18");
    }

    @Test
    void the_bean_of_group_30_leaves_the_voice_calls_alone_and_the_voice_beans_leave_the_views_alone() {
        String voice10 = CdrTestSupport.entryJson(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 9, 29, 10, 0)));
        String voice11 = CdrTestSupport.entryJson(CdrTestSupport.sg11Entry(CdrTestSupport.at(2026, 9, 29, 10, 0)));
        byte[] mixed = AdTestSupport.batchJson(List.of(voice10, leafView(MORNING).json(), voice11));

        assertEquals(1, daily30().buildBatch(mixed).size(), "the one view");
        assertEquals(1, CdrTestSupport.dailyBean().buildBatch(mixed).size(), "the group-10 bean: its one call");
        assertEquals(10, daily30().buildBatch(batchOf(series(10))).size(), "one delta per view, merged by the engine");
    }

    @Test
    void the_hourly_bean_buckets_the_view_on_its_start_hour() {
        CallSummary s = hourly30().buildBatch(batchOf(leafView(at(2026, 9, 29, 14, 30)))).get(0);

        assertEquals(LocalDateTime.of(2026, 9, 29, 14, 0), s.tup_starttime);
    }

    @Test
    void a_service_group_with_no_mapping_still_fails_the_build() {
        // 10, 11 and 30 are mapped; any other group reaching its own bean is a config fault and must stay loud
        SummaryBean<CallSummary> group12 = CallSummaries.forWindow("dailyCallSummarySg12", "daily", "12", 12, null);
        String entry = leafView(MORNING).json().replace("\"servicegroup\":30", "\"servicegroup\":12");

        assertThrows(IllegalArgumentException.class, () -> group12.buildBatch(entry2batch(entry)));
    }

    private static byte[] entry2batch(String entry) {
        return ("[" + entry + "]").getBytes(StandardCharsets.UTF_8);
    }

    private static View[] series(int count) {
        List<View> views = new ArrayList<>();
        for (int i = 0; i < count; i++) views.add(leafView(MORNING.plusMinutes(i)));
        return views.toArray(View[]::new);
    }

    private static Collection<CallSummary> rollup(SummaryBean<CallSummary> bean, List<View> views) {
        SummaryCache<CallSummary> cache = new SummaryCache<>(bean.table(), CallSummary.INSERT_COLUMNS, CallSummary.BUCKET_COLUMN);
        for (CallSummary built : bean.buildBatch(batchOf(views.toArray(View[]::new)))) {
            cache.merge(built, MergeMode.ADD);
        }
        return cache.rows();
    }
}
