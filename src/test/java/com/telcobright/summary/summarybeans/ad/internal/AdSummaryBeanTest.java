package com.telcobright.summary.summarybeans.ad.internal;

import com.telcobright.summary.engine.api.BatchResult;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.engine.internal.SummaryCache;
import com.telcobright.summary.engine.spi.MergeMode;
import com.telcobright.summary.summarybeans.ad.model.AdSummary;
import com.telcobright.summary.testkit.FakeSummaryStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.DAY_TABLE;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.at;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.batchJson;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.dailyBean;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.hourlyBean;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.refusedEntry;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.rollup;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.series;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.totalCharged;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.totalViews;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.twoTierEntry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ad bean over the blob seed-callflow writes (design §2.9): EVERY tier of every call becomes a row (the
 * 2-tier blob → 2 rows, the reseller's and the operator's), a refused call one failed row on the entry tenant,
 * the windows bucket on the call's start; the engine loads the involved windows once; a subtract row takes a
 * view back. The tests the call category proves, on the ad's own shapes.
 */
class AdSummaryBeanTest {

    @Test
    void the_two_tier_blob_becomes_two_rows_one_per_tier_with_the_tiers_own_tenant_partner_and_charge() {
        List<AdSummary> built = dailyBean().buildBatch(batchJson(List.of(twoTierEntry(at(2026, 9, 29, 10, 0), "ad-1"))));

        assertEquals(2, built.size(), "one row per TIER");
        AdSummary leaf = built.get(0), above = built.get(1);
        assertEquals("res_44", leaf.tup_tenant, "the advertiser's tier: the reseller's database");
        assertEquals(61, leaf.tup_partnerid, "Unilever");
        assertEquals(0, leaf.chargedamount.compareTo(new BigDecimal("0.50")), "res_44's debit");
        assertEquals("btcl", above.tup_tenant, "the tier above: the operator's database");
        assertEquals(44, above.tup_partnerid, "R1, the reseller as the operator's customer");
        assertEquals(0, above.chargedamount.compareTo(new BigDecimal("0.40")), "btcl's debit");
        for (AdSummary s : built) {
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
            assertEquals(0, s.failed);
            assertEquals(15, s.watchedsec);
            assertEquals(LocalDateTime.of(2026, 9, 29, 0, 0), s.tup_starttime);
        }
        assertTrue(!leaf.tupleKey().equals(above.tupleKey()), "the tiers key separately");
    }

    @Test
    void a_refused_call_is_one_failed_row_on_the_entry_tenant_with_no_charge() {
        List<AdSummary> built = dailyBean().buildBatch(batchJson(List.of(refusedEntry(at(2026, 9, 28, 9, 0), "ad-2"))));

        assertEquals(1, built.size(), "no tier: still one row");
        AdSummary s = built.get(0);
        assertEquals("btcl", s.tup_tenant, "the entry tenant");
        assertEquals(0, s.tup_partnerid, "no advertiser known");
        assertEquals(0, s.tup_campaignid);
        assertEquals("", s.tup_rulecode);
        assertEquals("sylhet-09", s.tup_zone);
        assertEquals("failed", s.tup_outcome);
        assertEquals(1, s.views);
        assertEquals(0, s.shown);
        assertEquals(0, s.completed);
        assertEquals(1, s.failed);
        assertEquals(0, s.chargedamount.compareTo(BigDecimal.ZERO));
    }

    @Test
    void an_admitted_but_never_shown_call_keeps_its_debits_and_counts_as_failed_not_shown() {
        List<AdSummary> built = dailyBean().buildBatch(batchJson(List.of(twoTierEntry(at(2026, 9, 29, 10, 0), "ad-3", "failed", false, 0))));

        assertEquals(2, built.size(), "the rows keep the debits (D13)");
        for (AdSummary s : built) {
            assertEquals(0, s.shown);
            assertEquals(0, s.completed);
            assertEquals(1, s.failed);
            assertEquals("failed", s.tup_outcome);
        }
        assertEquals(0, totalCharged(built).compareTo(new BigDecimal("0.90")), "0.50 + 0.40 stay charged");
    }

    @Test
    void daily_and_hourly_beans_bucket_the_same_call_on_its_start_time() {
        byte[] one = batchJson(List.of(twoTierEntry(at(2026, 9, 29, 14, 30), "ad-4")));

        AdSummary day = dailyBean().buildBatch(one).get(0);
        AdSummary hour = hourlyBean().buildBatch(one).get(0);

        assertEquals(LocalDateTime.of(2026, 9, 29, 0, 0), day.tup_starttime);
        assertEquals(LocalDateTime.of(2026, 9, 29, 14, 0), hour.tup_starttime);
        assertEquals(day.tup_starttime, dailyBean().bucketOf(day));
    }

    @Test
    void the_same_key_merges_and_the_windows_reconcile() {
        List<String> day = series(at(2026, 9, 29, 0, 0), 30, 48);        // 48 calls every 30 min, one day, two tiers each

        Collection<AdSummary> hours = rollup(hourlyBean(), day);
        Collection<AdSummary> daily = rollup(dailyBean(), day);

        assertEquals(2, daily.size(), "one row per tier for the day");
        assertEquals(48, hours.size(), "24 hour buckets × 2 tiers");
        assertEquals(96, totalViews(daily), "48 calls × 2 tiers");
        assertEquals(totalViews(daily), totalViews(hours), "hourly reconciles to daily");
        assertEquals(0, totalCharged(daily).compareTo(new BigDecimal("43.20")), "48 × (0.50 + 0.40)");
        assertEquals(0, totalCharged(daily).compareTo(totalCharged(hours)));
        AdSummary reseller = daily.stream().filter(r -> r.tup_tenant.equals("res_44")).findFirst().orElseThrow();
        assertEquals(48, reseller.views);
        assertEquals(0, reseller.chargedamount.compareTo(new BigDecimal("24.00")), "R1's tier: 48 × 0.50");
    }

    @Test
    void involved_windows_are_loaded_once_not_per_event() {
        SummaryEngine engine = new SummaryEngine();
        FakeSummaryStore store = new FakeSummaryStore();
        List<AdSummary> batch = dailyBean().buildBatch(batchJson(List.of(
                twoTierEntry(at(2026, 9, 29, 0, 30), "a"),
                twoTierEntry(at(2026, 9, 29, 14, 0), "b"),     // same day 29
                twoTierEntry(at(2026, 9, 30, 9, 15), "c"),
                refusedEntry(at(2026, 10, 1, 23, 0), "d"))));

        BatchResult result = engine.runBatch(dailyBean(), batch, store);

        assertEquals(1, store.loadCount(DAY_TABLE), "the window is loaded exactly once");
        assertEquals(3, store.bucketsLoaded(DAY_TABLE).size(), "3 distinct day buckets in one load");
        assertTrue(store.ranSqlMatching("insert into " + DAY_TABLE), "rows inserted");
        assertEquals(5, result.rowsInserted(), "day 29: 2 tiers merged over 2 calls; day 30: 2 tiers; day 1: the failed row");
    }

    @Test
    void a_subtract_row_takes_the_view_and_its_charge_back_from_the_loaded_window() {
        SummaryCache<AdSummary> cache = new SummaryCache<>(DAY_TABLE, AdSummary.INSERT_COLUMNS, AdSummary.BUCKET_COLUMN);
        List<AdSummary> two = dailyBean().buildBatch(batchJson(List.of(twoTierEntry(at(2026, 9, 29, 10, 0), "ad-5"))));
        AdSummary existing = two.get(0).cloneWithFakeId();
        existing.merge(two.get(0));                 // the reseller's tier at 2 views, 1.00
        existing.setId(100L);
        cache.populateExisting(existing);

        AdSummary delta = two.get(0);
        cache.merge(delta, MergeMode.SUBTRACT);

        AdSummary row = cache.rows().iterator().next();
        assertEquals(1, row.views, "one view taken back");
        assertEquals(0, row.chargedamount.compareTo(new BigDecimal("0.50")), "its charge too");
        assertEquals(1, delta.views, "the caller's delta is NOT negated in place");
        assertThrows(IllegalStateException.class, () -> cache.merge(two.get(1), MergeMode.SUBTRACT), "a window not loaded cannot be decremented");
    }

    @Test
    void a_malformed_entry_is_skipped_never_the_whole_drain() {
        String json = "[" + twoTierEntry(at(2026, 9, 29, 10, 0), "ok") + ",{\"Cdr\":{\"Tenant\":\"btcl\"},\"Chargeables\":[]},{\"Chargeables\":[]}]";
        List<AdSummary> built = dailyBean().buildBatch(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, built.size(), "the good entry's two tiers; the two without a StartTime are skipped");
    }

    @Test
    void names_tables_and_the_entity_type_are_the_designs() {
        assertEquals("dailyAdSummary", dailyBean().name());
        assertEquals("hourlyAdSummary", hourlyBean().name());
        assertEquals("sum_ad_day_30", dailyBean().table());
        assertEquals("sum_ad_hr_30", hourlyBean().table());
        assertEquals("ad_cdr", dailyBean().entityType(), "the outbox stream ad-sphere's terminal write fills");
        assertEquals("tup_starttime", dailyBean().bucketColumn());
        String ddl = dailyBean().tableDdl();
        assertNotNull(ddl);
        assertTrue(ddl.startsWith("CREATE TABLE IF NOT EXISTS sum_ad_day_30 ("));
        assertTrue(ddl.contains("PRIMARY KEY (id, tup_starttime)"), "the partition column is in every unique key");
        assertTrue(ddl.contains("PARTITION BY RANGE COLUMNS(tup_starttime)"));
        for (String column : AdSummary.INSERT_COLUMNS.split(",")) assertTrue(ddl.contains(column + " "), "DDL carries " + column);
    }
}
