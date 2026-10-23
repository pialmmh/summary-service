package com.telcobright.summary.summarybeans.chargeable;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.beans.DailyChargeableSummaryBuilder;
import com.telcobright.summary.engine.internal.SummaryCache;
import com.telcobright.summary.engine.spi.MergeMode;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.View;
import com.telcobright.summary.summarybeans.call.internal.CdrBlobMapper;
import com.telcobright.summary.summarybeans.chargeable.model.ChargeableSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.at;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.batchOf;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.leafView;
import static com.telcobright.summary.summarybeans.ad.internal.AdTestSupport.refusedView;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Brief S4: "the chargeable beans as they are" — they roll up every chargeable of every service group, so the one
 * customer chargeable billing-core builds for an ad view lands in {@code sum_chargeable_*} with no change here.
 * This holds it on the group-30 input: the leg's own group, family, direction, unit and prefix are its key (the
 * unit is IN the key — money and a package's units are separate rows), its billed amount and quantity its measures.
 */
class ChargeableSummaryGroup30Test {

    private static final LocalDateTime MORNING = at(2026, 9, 29, 10, 0);

    private static SummaryBean<ChargeableSummary> daily() {
        return DailyChargeableSummaryBuilder.create(CdrBlobMapper.create()).build();
    }

    @Test
    void an_ad_views_chargeable_is_a_row_of_group_30_in_the_customer_direction() {
        ChargeableSummary s = daily().buildBatch(batchOf(leafView(MORNING))).get(0);

        assertEquals(30, s.tup_servicegroup);
        assertEquals(30, s.tup_servicefamily);
        assertEquals(1, s.tup_assigneddirection, "the customer direction: the payer's leg");
        assertEquals("BDT", s.tup_billeduom);
        assertEquals("10", s.tup_prefix);
        assertEquals(LocalDateTime.of(2026, 9, 29, 0, 0), s.tup_transactiontime, "the leg's transactionTime = the cdr's StartTime");
        assertEquals(1, s.totalcount);
        assertEquals(0, s.BilledAmount.compareTo(new BigDecimal("0.50")));
        assertEquals(0, s.Quantity.compareTo(new BigDecimal("15")), "the seconds watched");
    }

    @Test
    void money_and_a_packages_units_are_separate_rows_because_the_unit_is_in_the_key() {
        Collection<ChargeableSummary> rows = rollup(List.of(leafView(MORNING), leafView(MORNING.plusMinutes(1)),
                leafView(MORNING.plusMinutes(2)).uom("TF_s").charge("15"), refusedView(MORNING.plusMinutes(3))));

        assertEquals(2, rows.size(), "BDT and TF_s; the refused view's zero chargeable carries BDT and opens no row of its own");
        ChargeableSummary money = rows.stream().filter(r -> r.tup_billeduom.equals("BDT")).findFirst().orElseThrow();
        ChargeableSummary units = rows.stream().filter(r -> r.tup_billeduom.equals("TF_s")).findFirst().orElseThrow();
        assertEquals(3, money.totalcount, "two paid views and the refused one");
        assertEquals(0, money.BilledAmount.compareTo(new BigDecimal("1.00")));
        assertEquals(1, units.totalcount);
        assertEquals(0, units.BilledAmount.compareTo(new BigDecimal("15")));
    }

    private static Collection<ChargeableSummary> rollup(List<View> views) {
        SummaryBean<ChargeableSummary> bean = daily();
        SummaryCache<ChargeableSummary> cache = new SummaryCache<>(bean.table(), ChargeableSummary.INSERT_COLUMNS, ChargeableSummary.BUCKET_COLUMN);
        for (ChargeableSummary built : bean.buildBatch(batchOf(views.toArray(View[]::new)))) {
            cache.merge(built, MergeMode.ADD);
        }
        return cache.rows();
    }
}
