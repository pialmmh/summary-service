package com.telcobright.summary.summarybeans.ad.internal;

import com.telcobright.summary.engine.api.BatchResult;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.engine.internal.SummaryCache;
import com.telcobright.summary.engine.spi.MergeMode;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.View;
import com.telcobright.summary.summarybeans.ad.model.AdSummary;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.FakeSummaryStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.DAY_TABLE;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.LEAF;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.ROOT;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.at;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.batchOf;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.dailyBean;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.hourlyBean;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.leafView;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.refusedView;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.rollup;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.rootView;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.series;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.totalCharged;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.totalViews;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ad bean on the CALL's own outbox stream (brief S1): an ad view is a {@code cdr} entry of service group 30,
 * one per tier, each in its own tier's outbox. The bean keeps group 30, takes the dimensions from the {@code Cdr}
 * and from the JSON in its {@code AdditionalMetaData}, the charge from the customer chargeable, and the tier
 * from the schema the drain serves. The rows are the ones stream X's tests expected for the same view — now one
 * per tier SCHEMA instead of one per leg of a single root entry.
 */
class AdSummaryBeanTest {

    private static final LocalDateTime MORNING = at(2026, 9, 29, 10, 0);

    // ---- the view, tier by tier ----

    @Test
    void each_tiers_record_becomes_one_row_in_its_own_schema_with_the_tiers_own_payer_and_charge() {
        List<AdSummary> inLeaf = dailyBean().buildBatch(batchOf(leafView(MORNING)), LEAF);
        List<AdSummary> inRoot = dailyBean().buildBatch(batchOf(rootView(MORNING)), ROOT);

        assertEquals(1, inLeaf.size(), "one record in the reseller's outbox: one row");
        assertEquals(1, inRoot.size(), "one record in the operator's outbox: one row");
        AdSummary leaf = inLeaf.get(0), above = inRoot.get(0);
        assertEquals("res_44", leaf.tup_tenant, "the advertiser's tier: the reseller's schema");
        assertEquals(61, leaf.tup_partnerid, "Unilever pays here");
        assertEquals(0, leaf.chargedamount.compareTo(new BigDecimal("0.50")), "res_44's charge");
        assertEquals("btcl", above.tup_tenant, "the tier above: the operator's schema");
        assertEquals(44, above.tup_partnerid, "R1, the reseller as the operator's customer");
        assertEquals(0, above.chargedamount.compareTo(new BigDecimal("0.40")), "btcl's charge");
        for (AdSummary s : List.of(leaf, above)) {
            assertEquals(5, s.tup_campaignid);
            assertEquals("1001", s.tup_rulecode);
            assertEquals("dhaka-01", s.tup_zone);
            assertEquals("dhaka-site-1", s.tup_site);
            assertEquals("wifi", s.tup_app);
            assertEquals("video", s.tup_mediakind);
            assertEquals("done", s.tup_outcome);
            assertEquals(1, s.views);
            assertEquals(1, s.shown);
            assertEquals(1, s.completed);
            assertEquals(1, s.credited, "the free session followed the view");
            assertEquals(0, s.failed);
            assertEquals(15, s.watchedsec);
            assertEquals(LocalDateTime.of(2026, 9, 29, 0, 0), s.tup_starttime);
        }
        assertNotEquals(leaf.tupleKey(), above.tupleKey(), "the tiers key separately");
    }

    @Test
    void a_refused_view_is_one_failed_row_on_the_entry_tenant_with_no_charge() {
        List<AdSummary> built = dailyBean().buildBatch(batchOf(refusedView(at(2026, 9, 28, 9, 0))), ROOT);

        assertEquals(1, built.size(), "nobody admitted: still one record, one row");
        AdSummary s = built.get(0);
        assertEquals("btcl", s.tup_tenant, "the entry tenant");
        assertEquals(1, s.tup_partnerid, "no payer was known: the tenant's own operator partner (a record always names one)");
        assertEquals(0, s.tup_campaignid, "no campaign was picked");
        assertEquals("sylhet-09", s.tup_rulecode, "no rule matched: the switch writes the zone as the called number");
        assertEquals("sylhet-09", s.tup_zone);
        assertEquals("", s.tup_mediakind);
        assertEquals("failed", s.tup_outcome);
        assertEquals(1, s.views);
        assertEquals(0, s.shown);
        assertEquals(0, s.completed);
        assertEquals(1, s.failed);
        assertEquals(0, s.chargedamount.compareTo(BigDecimal.ZERO), "a chargeable of zero, in BDT: money 0");
        assertEquals(0, s.chargedunits.compareTo(BigDecimal.ZERO), "and no units");
    }

    @Test
    void an_admitted_but_never_shown_view_keeps_its_charge_and_counts_as_failed_not_shown() {
        List<AdSummary> leaf = dailyBean().buildBatch(batchOf(leafView(MORNING).admittedNeverShown()), LEAF);
        List<AdSummary> root = dailyBean().buildBatch(batchOf(rootView(MORNING).admittedNeverShown()), ROOT);

        for (AdSummary s : List.of(leaf.get(0), root.get(0))) {
            assertEquals(0, s.shown);
            assertEquals(0, s.completed);
            assertEquals(0, s.credited);
            assertEquals(1, s.failed);
            assertEquals("failed", s.tup_outcome);
        }
        assertEquals(0, leaf.get(0).chargedamount.add(root.get(0).chargedamount).compareTo(new BigDecimal("0.90")),
                "0.50 + 0.40 stay charged: an admitted view is charged, shown or not (no return policy)");
    }

    @Test
    void a_view_watched_to_the_end_but_never_claimed_counts_completed_not_credited() {
        AdSummary s = dailyBean().buildBatch(batchOf(leafView(MORNING).credited(false)), LEAF).get(0);

        assertEquals(1, s.shown);
        assertEquals(1, s.completed, "the required seconds were watched");
        assertEquals(0, s.credited, "the record says credited=false");
        assertEquals(0, s.failed);
    }

    // ---- the rules of S1, one by one ----

    @Test
    void only_service_group_30_is_an_ad_view_every_other_group_is_left_to_its_own_beans() {
        // the outbox is the call's own stream: an operator that also carries voice has groups 10 and 11 in the same rows
        String voice10 = CdrTestSupport.entryJson(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 9, 29, 10, 0)));
        String voice11 = CdrTestSupport.entryJson(CdrTestSupport.sg11Entry(CdrTestSupport.at(2026, 9, 29, 10, 0)));
        byte[] mixed = AdTestSupport.batchJson(List.of(voice10, leafView(MORNING).json(), voice11,
                leafView(MORNING).serviceGroup(10).json(), leafView(MORNING).serviceGroup(11).json(),
                leafView(MORNING).serviceGroup(0).json(), leafView(MORNING).serviceGroup(null).json()));

        List<AdSummary> built = dailyBean().buildBatch(mixed, LEAF);

        assertEquals(1, built.size(), "the one entry whose Cdr.ServiceGroup is 30 — whatever group its chargeable names");
        assertEquals(1, built.get(0).views);
    }

    @Test
    void the_tier_is_the_schema_the_drain_serves_never_read_from_the_blob() {
        // the record itself says "btcl > res_44" (its ResellerHierarchy); the row carries the schema it was drained for
        AdSummary asLeaf = dailyBean().buildBatch(batchOf(leafView(MORNING)), "res_44").get(0);
        AdSummary asNested = dailyBean().buildBatch(batchOf(leafView(MORNING)), "res_44_7").get(0);

        assertEquals("res_44", asLeaf.tup_tenant);
        assertEquals("res_44_7", asNested.tup_tenant, "tup_tenant is always the schema's own name");
    }

    @Test
    void a_build_without_a_tier_is_refused_in_words() {
        byte[] one = batchOf(leafView(MORNING));

        IllegalStateException noTier = assertThrows(IllegalStateException.class, () -> dailyBean().buildBatch(one, null));
        assertThrows(IllegalStateException.class, () -> dailyBean().buildBatch(one, " "));
        assertThrows(IllegalStateException.class, () -> dailyBean().buildBatch(one), "the tier-less form names no tier");
        assertTrue(noTier.getMessage().contains("needs the tier"), noTier.getMessage());
    }

    @Test
    void the_outcome_is_done_only_for_a_normal_clearing() {
        assertEquals("done", outcomeOf(leafView(MORNING).cause("NORMAL_CLEARING")));
        assertEquals("done", outcomeOf(leafView(MORNING).cause(" normal_clearing ")), "the word, however it is cased");
        assertEquals("failed", outcomeOf(leafView(MORNING).cause("NO_RULE")));
        assertEquals("failed", outcomeOf(leafView(MORNING).cause("INSUFFICIENT_BALANCE")));
        assertEquals("failed", outcomeOf(leafView(MORNING).cause("ABANDONED")));
        assertEquals("failed", outcomeOf(leafView(MORNING).cause(null)), "no cause on the record is not a normal clearing");
    }

    @Test
    void failed_counts_every_view_that_did_not_clear_normally_whatever_was_watched() {
        AdSummary abandoned = dailyBean().buildBatch(batchOf(leafView(MORNING).cause("ABANDONED").watched("4").completed(false).credited(false)), LEAF).get(0);

        assertEquals(1, abandoned.failed);
        assertEquals(1, abandoned.shown, "it had an answer time");
        assertEquals(4, abandoned.watchedsec);
        assertEquals(0, abandoned.completed);
    }

    @Test
    void shown_is_an_answer_time_on_the_record() {
        AdSummary shown = dailyBean().buildBatch(batchOf(leafView(MORNING)), LEAF).get(0);
        AdSummary notShown = dailyBean().buildBatch(batchOf(leafView(MORNING).neverShown()), LEAF).get(0);

        assertEquals(1, shown.shown);
        assertEquals(0, notShown.shown, "no AnswerTime: never shown, though the cause says a normal clearing");
        assertEquals("done", notShown.tup_outcome, "the outcome is the cause's, not the answer time's");
    }

    @Test
    void completed_and_credited_are_the_meta_datas_not_derived_from_the_outcome() {
        AdSummary neither = dailyBean().buildBatch(batchOf(leafView(MORNING).completed(false).credited(false)), LEAF).get(0);
        AdSummary both = dailyBean().buildBatch(batchOf(leafView(MORNING).cause("ABANDONED")), LEAF).get(0);

        assertEquals(0, neither.completed, "shown and cleared normally, but the facts say not completed");
        assertEquals(0, neither.credited);
        assertEquals(1, both.completed, "the facts say completed: counted, whatever the cause");
        assertEquals(1, both.credited);
    }

    @Test
    void the_charged_amount_is_the_customer_chargeables_billed_amount_when_its_unit_is_money() {
        AdSummary charged = dailyBean().buildBatch(batchOf(leafView(MORNING).charge("0.75")), LEAF).get(0);
        AdSummary noLeg = dailyBean().buildBatch(batchOf(leafView(MORNING).noChargeable()), LEAF).get(0);

        assertEquals(0, charged.chargedamount.compareTo(new BigDecimal("0.75")), "a leg in BDT is money");
        assertEquals(0, charged.chargedunits.compareTo(BigDecimal.ZERO), "and is not counted as units too");
        assertEquals(0, noLeg.chargedamount.compareTo(BigDecimal.ZERO), "an entry without a chargeable still counts the view, charge 0");
        assertEquals(0, noLeg.chargedunits.compareTo(BigDecimal.ZERO));
        assertEquals(1, noLeg.views);
    }

    @Test
    void a_tier_that_pays_from_a_package_is_counted_in_units_never_in_money() {
        // the settle step charged 10 seconds of a package (TF_s), or one view of one (OTH_ea): BilledAmount holds the units
        AdSummary seconds = dailyBean().buildBatch(batchOf(leafView(MORNING).uom("TF_s").charge("10")), LEAF).get(0);
        AdSummary each = dailyBean().buildBatch(batchOf(leafView(MORNING).uom("OTH_ea").charge("1")), LEAF).get(0);

        assertEquals(0, seconds.chargedamount.compareTo(BigDecimal.ZERO), "units are never money");
        assertEquals(0, seconds.chargedunits.compareTo(new BigDecimal("10")));
        assertEquals(0, each.chargedamount.compareTo(BigDecimal.ZERO));
        assertEquals(0, each.chargedunits.compareTo(BigDecimal.ONE));
    }

    @Test
    void money_and_units_of_the_same_key_stay_two_measures_in_one_row() {
        // two views of one key in one tier: one account pays 0.50 BDT, another pays 10 units of its package
        Collection<AdSummary> rows = rollup(dailyBean(), LEAF, List.of(leafView(MORNING), leafView(MORNING.plusMinutes(5)).uom("TF_s").charge("10")));

        assertEquals(1, rows.size(), "the unit is not in the key: one row");
        AdSummary row = rows.iterator().next();
        assertEquals(2, row.views);
        assertEquals(0, row.chargedamount.compareTo(new BigDecimal("0.50")), "the money alone — not 10.50");
        assertEquals(0, row.chargedunits.compareTo(new BigDecimal("10")), "the units alone");
    }

    @Test
    void money_is_the_unit_bdt_however_it_is_written_and_a_leg_that_names_no_unit_is_not_money() {
        AdSummary lower = dailyBean().buildBatch(batchOf(leafView(MORNING).uom(" bdt ")), LEAF).get(0);
        AdSummary unnamed = dailyBean().buildBatch(batchOf(leafView(MORNING).uom(null)), LEAF).get(0);

        assertEquals(0, lower.chargedamount.compareTo(new BigDecimal("0.50")));
        assertEquals(0, lower.chargedunits.compareTo(BigDecimal.ZERO));
        assertEquals(0, unnamed.chargedamount.compareTo(BigDecimal.ZERO), "no unit on the leg: never taken for money");
        assertEquals(0, unnamed.chargedunits.compareTo(new BigDecimal("0.50")));
    }

    @Test
    void the_customer_leg_is_picked_by_its_direction_not_by_its_place() {
        String supplierFirst = leafView(MORNING).json().replace("\"Chargeables\":[",
                "\"Chargeables\":[{\"transactionTime\":[2026,9,29,10,0],\"assignedDirection\":2,\"servicegroup\":30,\"servicefamily\":30,"
                        + "\"ProductId\":0,\"idBilledUom\":\"BDT\",\"BilledAmount\":9.99,\"Quantity\":1},");

        AdSummary s = dailyBean().buildBatch(AdTestSupport.batchJson(List.of(supplierFirst)), LEAF).get(0);

        assertEquals(0, s.chargedamount.compareTo(new BigDecimal("0.50")), "the leg whose assignedDirection is 1");
    }

    @Test
    void watched_seconds_are_the_records_duration_to_the_whole_second() {
        assertEquals(10, watchedOf("10"));
        assertEquals(10, watchedOf("10.4"));
        assertEquals(11, watchedOf("10.5"), "half up");
        assertEquals(0, watchedOf("0"));
    }

    @Test
    void the_partner_the_rule_code_and_the_media_kind_come_from_the_cdr() {
        AdSummary s = dailyBean().buildBatch(batchOf(leafView(MORNING).partner(77).rule("7001").media("image")), LEAF).get(0);

        assertEquals(77, s.tup_partnerid, "InPartnerId");
        assertEquals("7001", s.tup_rulecode, "OriginatingCalledNumber");
        assertEquals("image", s.tup_mediakind, "Codec");
    }

    @Test
    void the_campaign_the_zone_the_site_and_the_app_come_from_the_meta_data() {
        AdSummary s = dailyBean().buildBatch(batchOf(leafView(MORNING).campaign(12).zone("dhaka-north").site("mirpur-10").app("captive")), LEAF).get(0);

        assertEquals(12, s.tup_campaignid);
        assertEquals("dhaka-north", s.tup_zone);
        assertEquals("mirpur-10", s.tup_site);
        assertEquals("captive", s.tup_app);
    }

    @Test
    void a_forty_character_app_name_keeps_its_own_rows() {
        // S3: the app's name is 64 wide at its source; at 32 these two (equal in their first 32 characters) merged
        String shared = "wifi-captive-portal-dhaka-north-";                   // 32 characters
        String retail = shared + "retail-1", campus = shared + "campus-1";    // 40 each
        assertEquals(32, shared.length());
        assertEquals(40, retail.length());

        Collection<AdSummary> rows = rollup(dailyBean(), LEAF, List.of(leafView(MORNING).app(retail), leafView(MORNING.plusMinutes(1)).app(campus),
                leafView(MORNING.plusMinutes(2)).app(retail)));

        assertEquals(2, rows.size(), "each app keeps its own row");
        AdSummary ofRetail = rows.stream().filter(r -> r.tup_app.equals(retail)).findFirst().orElseThrow();
        AdSummary ofCampus = rows.stream().filter(r -> r.tup_app.equals(campus)).findFirst().orElseThrow();
        assertEquals(2, ofRetail.views);
        assertEquals(1, ofCampus.views);
        assertNotEquals(ofRetail.tupleKey(), ofCampus.tupleKey());
    }

    @Test
    void an_app_name_past_the_column_is_cut_to_64_so_a_fresh_build_keys_as_its_reloaded_row() {
        String tooLong = "a".repeat(63) + "bcdefgh";                           // 70 characters

        AdSummary s = dailyBean().buildBatch(batchOf(leafView(MORNING).app(tooLong)), LEAF).get(0);

        assertEquals(64, s.tup_app.length(), "what the VARCHAR(64) column will hold");
        assertEquals("a".repeat(63) + "b", s.tup_app);
        assertTrue(dailyBean().tableDdl().contains("tup_app VARCHAR(64) "), "and the column is that wide");
    }

    @Test
    void a_campaign_id_reads_as_a_number_or_as_digits_in_a_string() {
        assertEquals(12, campaignOf(leafView(MORNING).campaign(12)));
        assertEquals(12, campaignOf(leafView(MORNING).campaign("12")));
        assertEquals(0, campaignOf(leafView(MORNING).campaign("cola-eid")), "not a number: no campaign, never a failed drain");
        assertEquals(0, campaignOf(leafView(MORNING).campaign(null)));
    }

    @Test
    void meta_data_that_is_not_a_json_object_still_counts_the_view_without_its_facts() {
        for (View view : List.of(leafView(MORNING).meta("{not json"), leafView(MORNING).meta("[1,2]"),
                leafView(MORNING).meta("7"), leafView(MORNING).meta(" "), leafView(MORNING).noMeta())) {
            List<AdSummary> built = dailyBean().buildBatch(batchOf(view), LEAF);

            assertEquals(1, built.size(), "the view is never lost over its meta data");
            AdSummary s = built.get(0);
            assertEquals(1, s.views);
            assertEquals(0, s.tup_campaignid);
            assertEquals("", s.tup_zone);
            assertEquals("", s.tup_app);
            assertEquals(0, s.completed);
            assertEquals(0, s.credited);
            assertEquals(61, s.tup_partnerid, "the cdr's own fields are still read");
            assertEquals(0, s.chargedamount.compareTo(new BigDecimal("0.50")), "and its charge");
        }
    }

    @Test
    void a_fact_of_the_wrong_type_reads_as_absent() {
        AdSummary s = dailyBean().buildBatch(batchOf(leafView(MORNING)
                .meta("{\"campaignId\":{\"id\":5},\"zone\":[\"a\"],\"site\":null,\"app\":7,\"completed\":\"true\",\"credited\":1}")), LEAF).get(0);

        assertEquals(0, s.tup_campaignid);
        assertEquals("", s.tup_zone);
        assertEquals("", s.tup_site);
        assertEquals("7", s.tup_app, "a number where a name is expected is read as its text");
        assertEquals(1, s.completed, "the word true is true");
        assertEquals(0, s.credited, "1 is not the boolean true");
    }

    @Test
    void billing_cores_date_arrays_and_iso_text_both_decode_to_the_same_bucket() {
        AdSummary fromArray = hourlyBean().buildBatch(batchOf(leafView(at(2026, 9, 29, 14, 30))), LEAF).get(0);
        AdSummary fromText = hourlyBean().buildBatch(batchOf(leafView(at(2026, 9, 29, 14, 30)).isoDates()), LEAF).get(0);

        assertEquals(LocalDateTime.of(2026, 9, 29, 14, 0), fromArray.tup_starttime);
        assertEquals(fromArray.tupleKey(), fromText.tupleKey());
        assertEquals(1, fromText.shown, "the answer time decodes in both forms");
    }

    // ---- the engine's rules, on the ad's shapes ----

    @Test
    void daily_and_hourly_beans_bucket_the_same_view_on_its_start_time() {
        byte[] one = batchOf(leafView(at(2026, 9, 29, 14, 30)));

        AdSummary day = dailyBean().buildBatch(one, LEAF).get(0);
        AdSummary hour = hourlyBean().buildBatch(one, LEAF).get(0);

        assertEquals(LocalDateTime.of(2026, 9, 29, 0, 0), day.tup_starttime);
        assertEquals(LocalDateTime.of(2026, 9, 29, 14, 0), hour.tup_starttime);
        assertEquals(day.tup_starttime, dailyBean().bucketOf(day));
    }

    @Test
    void the_same_key_merges_and_the_windows_reconcile() {
        List<View> day = series(at(2026, 9, 29, 0, 0), 30, 48);        // 48 views every 30 min, one day, the reseller's tier

        Collection<AdSummary> hours = rollup(hourlyBean(), LEAF, day);
        Collection<AdSummary> daily = rollup(dailyBean(), LEAF, day);

        assertEquals(1, daily.size(), "one row for the day in the tier's schema");
        assertEquals(24, hours.size(), "24 hour buckets");
        assertEquals(48, totalViews(daily));
        assertEquals(totalViews(daily), totalViews(hours), "hourly reconciles to daily");
        assertEquals(0, totalCharged(daily).compareTo(new BigDecimal("24.00")), "R1's tier: 48 × 0.50");
        assertEquals(0, totalCharged(daily).compareTo(totalCharged(hours)));
        assertEquals(48 * 15, daily.iterator().next().watchedsec);
    }

    @Test
    void involved_windows_are_loaded_once_not_per_event() {
        SummaryEngine engine = new SummaryEngine();
        FakeSummaryStore store = new FakeSummaryStore();
        List<AdSummary> batch = dailyBean().buildBatch(batchOf(
                rootView(at(2026, 9, 29, 0, 30)),
                rootView(at(2026, 9, 29, 14, 0)),      // same day 29: merges
                rootView(at(2026, 9, 30, 9, 15)),
                refusedView(at(2026, 10, 1, 23, 0))), ROOT);

        BatchResult result = engine.runBatch(dailyBean(), batch, store);

        assertEquals(1, store.loadCount(DAY_TABLE), "the window is loaded exactly once");
        assertEquals(3, store.bucketsLoaded(DAY_TABLE).size(), "3 distinct day buckets in one load");
        assertTrue(store.ranSqlMatching("insert into " + DAY_TABLE), "rows inserted");
        assertEquals(3, result.rowsInserted(), "day 29: 2 views merged; day 30: one; day 1: the failed row");
    }

    @Test
    void a_subtract_row_takes_the_view_and_its_charge_back_from_the_loaded_window() {
        SummaryCache<AdSummary> cache = new SummaryCache<>(DAY_TABLE, AdSummary.INSERT_COLUMNS, AdSummary.BUCKET_COLUMN);
        AdSummary delta = dailyBean().buildBatch(batchOf(leafView(MORNING)), LEAF).get(0);
        AdSummary existing = delta.cloneWithFakeId();
        existing.merge(delta);                      // the reseller's tier at 2 views, 1.00
        existing.chargedunits = new BigDecimal("7"); // and 7 units from views paid out of a package
        existing.setId(100L);
        cache.populateExisting(existing);

        cache.merge(delta, MergeMode.SUBTRACT);

        AdSummary row = cache.rows().iterator().next();
        assertEquals(1, row.views, "one view taken back");
        assertEquals(0, row.chargedamount.compareTo(new BigDecimal("0.50")), "its charge too");
        assertEquals(0, row.chargedunits.compareTo(new BigDecimal("7")), "the view was paid in money: the units stay");
        assertEquals(1, delta.views, "the caller's delta is NOT negated in place");
        AdSummary neverLoaded = dailyBean().buildBatch(batchOf(leafView(MORNING).zone("other-zone")), LEAF).get(0);
        assertThrows(IllegalStateException.class, () -> cache.merge(neverLoaded, MergeMode.SUBTRACT), "a window not loaded cannot be decremented");
    }

    @Test
    void a_malformed_entry_is_skipped_never_the_whole_drain() {
        String json = "[" + leafView(MORNING).json() + ",{\"Cdr\":{\"ServiceGroup\":30,\"InPartnerId\":61},\"Chargeables\":[]},{\"Chargeables\":[]},null]";

        List<AdSummary> built = dailyBean().buildBatch(json.getBytes(StandardCharsets.UTF_8), LEAF);

        assertEquals(1, built.size(), "the good view; the ad view without a StartTime, the entry without a Cdr and the null are skipped");
    }

    @Test
    void a_blob_that_is_not_json_fails_the_build_so_the_drain_can_quarantine_the_row() {
        assertThrows(IllegalArgumentException.class, () -> dailyBean().buildBatch("not json".getBytes(StandardCharsets.UTF_8), LEAF));
    }

    @Test
    void names_tables_and_the_entity_type_are_the_designs() {
        assertEquals("dailyAdSummary", dailyBean().name());
        assertEquals("hourlyAdSummary", hourlyBean().name());
        assertEquals("sum_ad_day_30", dailyBean().table());
        assertEquals("sum_ad_hr_30", hourlyBean().table());
        assertEquals("cdr", dailyBean().entityType(), "the call's own outbox stream: billing-core writes the view as a cdr row");
        assertEquals("cdr", hourlyBean().entityType());
        assertEquals("tup_starttime", dailyBean().bucketColumn());
        String ddl = dailyBean().tableDdl();
        assertNotNull(ddl);
        assertTrue(ddl.startsWith("CREATE TABLE IF NOT EXISTS sum_ad_day_30 ("));
        assertTrue(ddl.contains("PRIMARY KEY (id, tup_starttime)"), "the partition column is in every unique key");
        assertTrue(ddl.contains("PARTITION BY RANGE COLUMNS(tup_starttime)"));
        for (String column : AdSummary.INSERT_COLUMNS.split(",")) assertTrue(ddl.contains(column + " "), "DDL carries " + column);
    }

    private static String outcomeOf(View view) {
        return dailyBean().buildBatch(batchOf(view), LEAF).get(0).tup_outcome;
    }

    private static long watchedOf(String durationSec) {
        return dailyBean().buildBatch(batchOf(leafView(MORNING).watched(durationSec)), LEAF).get(0).watchedsec;
    }

    private static int campaignOf(View view) {
        return dailyBean().buildBatch(batchOf(view), LEAF).get(0).tup_campaignid;
    }
}
