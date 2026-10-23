package com.telcobright.summary.summarybeans.call.chargeable.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.engine.internal.SummaryCache;
import com.telcobright.summary.engine.spi.MergeMode;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.summarybeans.call.chargeable.DailyChargeableSummary;
import com.telcobright.summary.summarybeans.call.chargeable.model.ChargeableSummary;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.WifiSessionTestSupport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;

import static com.telcobright.summary.testkit.CdrTestSupport.at;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SS-0008 item 2: the call kind's chargeable roll-up reads every leg of every record — the one bean whose input crosses the
 * kinds. Told the tenant's kinds, it sums only the records of their groups, and every one of those exactly as before.
 */
class ChargeableReadsTheEnabledKindsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A batch of every kind: a voice call of 10 (two legs), one of 11, two ad views, a WiFi session, a record of group 4 (two legs). */
    private static byte[] everyKind() {
        return ("[" + String.join(",",
                CdrTestSupport.entryJson(CdrTestSupport.sg10Entry(at(2026, 10, 10, 9, 0))),
                CdrTestSupport.entryJson(CdrTestSupport.sg11Entry(at(2026, 10, 10, 9, 5))),
                AdTestSupport.leafView(at(2026, 10, 10, 21, 14)).json(),
                AdTestSupport.rootView(at(2026, 10, 10, 21, 14)).json(),
                WifiSessionTestSupport.session(at(2026, 10, 10, 9, 2)).json(),
                CdrTestSupport.entryJson(new com.telcobright.summary.summarybeans.call.model.CdrBlobEntry(
                        CdrTestSupport.sg10Entry(at(2026, 10, 10, 9, 9)).cdr(), List.of(
                        new com.telcobright.summary.summarybeans.call.model.Chargeable(4, 4, 1, 1L, "BDT", "1714", at(2026, 10, 10, 9, 9),
                                new java.math.BigDecimal("1"), new java.math.BigDecimal("2"), new java.math.BigDecimal("1"), null,
                                null, null, null, null, null, null, null, null, null, null, null),
                        new com.telcobright.summary.summarybeans.call.model.Chargeable(4, 4, 2, 2L, "BDT", "1714S", at(2026, 10, 10, 9, 9),
                                new java.math.BigDecimal("1"), new java.math.BigDecimal("1"), new java.math.BigDecimal("1"), null,
                                null, null, null, null, null, null, null, null, null, null, null))))
        ) + "]").getBytes(StandardCharsets.UTF_8);
    }

    /** Each window row as "<group>|<its values>", sorted. */
    private static Set<String> rollup(ChargeableSummaryBean bean) {
        SummaryCache<ChargeableSummary> cache = new SummaryCache<>(bean.table(), ChargeableSummary.INSERT_COLUMNS, ChargeableSummary.BUCKET_COLUMN);
        for (ChargeableSummary built : bean.buildBatch(everyKind(), "btcl")) {
            cache.merge(built, MergeMode.ADD);
        }
        return cache.rows().stream().map(row -> row.tup_servicegroup + "|" + row.insertValues()).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<Integer> groups(Set<String> rows) {
        return rows.stream().map(row -> Integer.parseInt(row.substring(0, row.indexOf('|')))).collect(Collectors.toCollection(TreeSet::new));
    }

    private static ChargeableSummaryBean told(IntPredicate groups) {
        ChargeableSummaryBean bean = new DailyChargeableSummary(MAPPER, null);
        bean.readOnly(groups);
        return bean;
    }

    @Test
    void untold_every_record_of_every_group_is_summed_as_before_kinds() {
        assertEquals(Set.of(4, 10, 11, 30, 101), groups(rollup(new DailyChargeableSummary(MAPPER, null))));
    }

    @Test
    void told_the_call_kinds_groups_the_views_and_the_session_are_not_summed_and_every_other_row_is_exactly_as_before() {
        Set<String> before = rollup(new DailyChargeableSummary(MAPPER, null));
        Set<String> callTenant = rollup(told(group -> group != 30 && group != 101));

        assertEquals(Set.of(4, 10, 11), groups(callTenant));
        assertEquals(before.stream().filter(row -> !row.startsWith("30|") && !row.startsWith("101|")).collect(Collectors.toCollection(TreeSet::new)), callTenant,
                "the rows of 10, 11 and 4 are the rows of before, value for value");
    }

    @Test
    void told_the_ad_alone_only_the_views_are_summed() {
        assertEquals(Set.of(30), groups(rollup(told(group -> group == 30))));
    }
}
