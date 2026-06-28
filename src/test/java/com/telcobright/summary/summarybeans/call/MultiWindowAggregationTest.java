package com.telcobright.summary.summarybeans.call;

import com.telcobright.summary.testkit.CdrTestSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import static com.telcobright.summary.testkit.CdrTestSupport.at;
import static com.telcobright.summary.testkit.CdrTestSupport.beanForWindow;
import static com.telcobright.summary.testkit.CdrTestSupport.dailyBean;
import static com.telcobright.summary.testkit.CdrTestSupport.distinctKeysAt;
import static com.telcobright.summary.testkit.CdrTestSupport.hourlyBean;
import static com.telcobright.summary.testkit.CdrTestSupport.rollup;
import static com.telcobright.summary.testkit.CdrTestSupport.series;
import static com.telcobright.summary.testkit.CdrTestSupport.sg10Entry;
import static com.telcobright.summary.testkit.CdrTestSupport.totalCalls;
import static com.telcobright.summary.testkit.CdrTestSupport.totalCost;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The same call dataset rolled up at every supported window — 5min / hourly / daily / weekly / monthly / yearly
 * — with enough sample data to exercise many buckets. Each window must bucket the calls correctly, and the
 * grand total must RECONCILE across windows (Σ hourly == Σ daily == … == the number of calls). Distinct
 * dimensions inside one window stay separate rows; identical ones merge.
 */
class MultiWindowAggregationTest {

    @Test
    void daily_rolls_each_day_into_one_row_with_correct_totals() {
        // 72 hourly calls = 3 full days (June 19, 20, 21), 24 calls each
        Collection<CallSummary> days = rollup(dailyBean(), series(at(2026, 6, 19, 0, 0), 60, 72));

        assertEquals(3, days.size(), "one row per day");
        assertEquals(72, totalCalls(days));
        for (CallSummary d : days) {
            assertEquals(24, d.totalcalls, "24 calls a day");
            assertEquals(0, d.customercost.compareTo(new BigDecimal("24.0")), "24 x 1.0 cost");
            assertEquals(0, d.actualduration.compareTo(new BigDecimal("1440")), "24 x 60s duration");
        }
    }

    @Test
    void hourly_splits_a_day_into_24_buckets_and_reconciles_to_daily() {
        // 48 calls every 30 min across June 19 -> 2 per hour, all in one day
        List<CdrBlobEntry> day = series(at(2026, 6, 19, 0, 0), 30, 48);

        Collection<CallSummary> hours = rollup(hourlyBean(), day);
        Collection<CallSummary> daily = rollup(dailyBean(), day);

        assertEquals(24, hours.size(), "24 hour buckets");
        hours.forEach(h -> assertEquals(2, h.totalcalls, "2 calls per hour"));
        assertEquals(1, daily.size(), "one day bucket");
        assertEquals(48, totalCalls(daily));
        assertEquals(totalCalls(daily), totalCalls(hours), "hourly reconciles to daily");
        assertEquals(0, totalCost(daily).compareTo(totalCost(hours)), "cost reconciles too");
    }

    @Test
    void five_minute_window_buckets_within_an_hour() {
        // 12 calls every 5 min from 10:00 -> 12 distinct 5-min buckets in hour 10
        List<CdrBlobEntry> hour = series(at(2026, 6, 19, 10, 0), 5, 12);

        Collection<CallSummary> fiveMin = rollup(beanForWindow("5min", "sum_voice_5min_03"), hour);
        Collection<CallSummary> hourly = rollup(hourlyBean(), hour);

        assertEquals(12, fiveMin.size(), "12 five-minute buckets");
        fiveMin.forEach(b -> assertEquals(1, b.totalcalls));
        assertEquals(1, hourly.size());
        assertEquals(12, totalCalls(hourly));
        assertEquals(totalCalls(fiveMin), totalCalls(hourly), "5-min reconciles to hourly");
    }

    @Test
    void weekly_groups_by_iso_week_and_monthly_collapses_the_month() {
        // three dates > 7 days apart -> three distinct ISO weeks, but all in June
        List<CdrBlobEntry> june = List.of(
                sg10Entry(at(2026, 6, 1, 9, 0)),
                sg10Entry(at(2026, 6, 10, 9, 0)),
                sg10Entry(at(2026, 6, 20, 9, 0)));

        Collection<CallSummary> weeks = rollup(beanForWindow("weekly", "sum_voice_week_03"), june);
        Collection<CallSummary> months = rollup(beanForWindow("monthly", "sum_voice_month_03"), june);

        assertEquals(3, weeks.size(), "three distinct weeks");
        assertEquals(1, months.size(), "all of June in one month bucket");
        assertEquals(3, totalCalls(months));
        assertEquals(totalCalls(weeks), totalCalls(months), "weekly reconciles to monthly");
    }

    @Test
    void monthly_and_yearly_windows_bucket_across_the_calendar() {
        List<CdrBlobEntry> spread = List.of(
                sg10Entry(at(2025, 12, 15, 9, 0)),
                sg10Entry(at(2026, 1, 15, 9, 0)),
                sg10Entry(at(2026, 2, 15, 9, 0)),
                sg10Entry(at(2026, 3, 15, 9, 0)));

        Collection<CallSummary> months = rollup(beanForWindow("monthly", "sum_voice_month_03"), spread);
        Collection<CallSummary> years = rollup(beanForWindow("yearly", "sum_voice_year_03"), spread);

        assertEquals(4, months.size(), "Dec-2025, Jan/Feb/Mar-2026");
        assertEquals(2, years.size(), "2025 and 2026");
        assertEquals(4, totalCalls(years));
        long[] perYear = years.stream().mapToLong(r -> r.totalcalls).sorted().toArray();
        assertArrayEquals(new long[]{1, 3}, perYear, "2025 has 1 call, 2026 has 3");
    }

    @Test
    void same_window_keeps_distinct_dimensions_apart_but_merges_identical_ones() {
        // five calls in the same hour, different destinations -> five rows
        Collection<CallSummary> distinct = rollup(hourlyBean(), distinctKeysAt(at(2026, 6, 19, 10, 0), 5));
        assertEquals(5, distinct.size(), "distinct destinations -> distinct rows");
        distinct.forEach(r -> assertEquals(1, r.totalcalls));

        // five calls in the same hour, identical dimensions -> one merged row
        Collection<CallSummary> merged = rollup(hourlyBean(), series(at(2026, 6, 19, 11, 0), 1, 5));
        assertEquals(1, merged.size(), "identical dimensions in one window -> one row");
        assertEquals(5, merged.iterator().next().totalcalls);
    }
}
