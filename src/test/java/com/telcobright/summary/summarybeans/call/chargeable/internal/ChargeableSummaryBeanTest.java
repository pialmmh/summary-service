package com.telcobright.summary.summarybeans.chargeable.internal;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.beans.DailyChargeableSummaryBuilder;
import com.telcobright.summary.beans.HourlyChargeableSummaryBuilder;
import com.telcobright.summary.summarybeans.call.internal.CdrBlobMapper;
import com.telcobright.summary.summarybeans.chargeable.model.ChargeableSummary;
import com.telcobright.summary.testkit.CdrTestSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The chargeable bean: EVERY leg of EVERY entry becomes a row — no SG filter, no direction filter (§4). */
class ChargeableSummaryBeanTest {

    private final SummaryBean<ChargeableSummary> daily =
            DailyChargeableSummaryBuilder.create(CdrBlobMapper.create()).context("mediationContext").build();
    private final SummaryBean<ChargeableSummary> hourly =
            HourlyChargeableSummaryBuilder.create(CdrBlobMapper.create()).build();

    @Test
    void every_leg_of_every_entry_becomes_a_row_regardless_of_sg_or_direction() {
        LocalDateTime t = CdrTestSupport.at(2026, 6, 19, 14, 30);
        // sg10Entry carries customer + supplier legs; sg11Entry one customer leg -> 3 rows total
        byte[] mixed = CdrTestSupport.batchJson(List.of(CdrTestSupport.sg10Entry(t), CdrTestSupport.sg11Entry(t)));

        List<ChargeableSummary> built = daily.buildBatch(mixed);

        assertEquals(3, built.size(), "2 SG10 legs + 1 SG11 leg — nothing filtered");
        assertTrue(built.stream().anyMatch(s -> s.tup_assigneddirection == 2),
                "the supplier leg is kept as its OWN row (direction is a key column)");
        assertTrue(built.stream().anyMatch(s -> s.tup_servicegroup == 11), "SG11 is kept too");
    }

    @Test
    void customer_and_supplier_legs_key_separately_and_carry_their_own_amounts() {
        LocalDateTime t = CdrTestSupport.at(2026, 6, 19, 14, 30);
        List<ChargeableSummary> built = daily.buildBatch(CdrTestSupport.batchJson(List.of(CdrTestSupport.sg10Entry(t))));

        ChargeableSummary customer = built.stream().filter(s -> s.tup_assigneddirection == 1).findFirst().orElseThrow();
        ChargeableSummary supplier = built.stream().filter(s -> s.tup_assigneddirection == 2).findFirst().orElseThrow();

        assertEquals(0, customer.BilledAmount.compareTo(new BigDecimal("1.0")), "customer revenue");
        assertEquals(0, supplier.BilledAmount.compareTo(new BigDecimal("0.8")), "supplier cost");
        assertEquals(1, customer.totalcount);
        assertTrue(!customer.tupleKey().equals(supplier.tupleKey()), "direction separates the keys");
    }

    @Test
    void a_v1_blob_entry_still_yields_its_single_customer_leg() {
        LocalDateTime t = CdrTestSupport.at(2026, 6, 19, 14, 30);
        List<ChargeableSummary> built = daily.buildBatch(CdrTestSupport.batchJson(List.of(CdrTestSupport.sg10EntryV1(t))));

        assertEquals(1, built.size(), "the v1 shape has one leg — dual-decode keeps it");
        assertEquals(1, built.get(0).tup_assigneddirection);
    }

    @Test
    void daily_and_hourly_bucket_on_the_legs_own_transaction_time() {
        LocalDateTime t = CdrTestSupport.at(2026, 6, 19, 14, 30);
        byte[] one = CdrTestSupport.batchJson(List.of(CdrTestSupport.sg11Entry(t)));

        assertEquals(LocalDateTime.of(2026, 6, 19, 0, 0), daily.buildBatch(one).get(0).tup_transactiontime);
        assertEquals(LocalDateTime.of(2026, 6, 19, 14, 0), hourly.buildBatch(one).get(0).tup_transactiontime);
    }

    @Test
    void tables_are_fixed_per_window_with_no_suffix() {
        assertEquals("sum_chargeable_day", daily.table());
        assertEquals("sum_chargeable_hr", hourly.table());
        assertEquals("cdr", daily.entityType(), "same outbox stream as the call category");
        assertEquals("dailyChargeableSummary", daily.name());
        assertEquals("tup_transactiontime", daily.bucketColumn());
    }
}
