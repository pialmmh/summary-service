package com.telcobright.summary.summarybeans.call.internal;

import com.telcobright.summary.summarybeans.call.model.CallSummary;
import com.telcobright.summary.testkit.CdrTestSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The bean's blob→entity build: service-group filtering, per-window bucketing, and C# PascalCase decoding. */
class CallSummaryBeanTest {

    private final CallSummaryBean daily = CdrTestSupport.dailyBean();
    private final CallSummaryBean hourly = CdrTestSupport.hourlyBean();

    @Test
    void build_batch_keeps_only_its_service_group() {
        LocalDateTime t = CdrTestSupport.at(2026, 6, 19, 14, 30);
        byte[] mixed = CdrTestSupport.batchJson(List.of(CdrTestSupport.sg10Entry(t), CdrTestSupport.sg11Entry(t)));

        List<CallSummary> built = daily.buildBatch(mixed);

        assertEquals(1, built.size(), "only the SG10 entry is kept by the SG10 bean");
    }

    @Test
    void daily_and_hourly_beans_bucket_the_same_record_differently() {
        byte[] one = CdrTestSupport.batchJson(List.of(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 14, 30))));

        CallSummary day = daily.buildBatch(one).get(0);
        CallSummary hour = hourly.buildBatch(one).get(0);

        assertEquals(LocalDateTime.of(2026, 6, 19, 0, 0), day.tup_starttime);
        assertEquals(LocalDateTime.of(2026, 6, 19, 14, 0), hour.tup_starttime);
        assertEquals(day.tup_starttime, daily.bucketOf(day));
    }

    @Test
    void decodes_the_legacy_v1_blob_shape_permanently() {
        // the v1 on-the-wire shape ({Cdr, Customer}) — tolerated FOREVER per dotnet ruling A3, so stray
        // pre-upgrade outbox rows can never be silently lost
        String json = "[{\"Cdr\":{\"SwitchId\":1,\"InPartnerId\":5,\"IncomingRoute\":\"in\",\"OutgoingRoute\":\"out\","
                + "\"OriginatingIP\":\"1.1.1.1\",\"TerminatingIP\":\"2.2.2.2\",\"CountryCode\":\"880\","
                + "\"ConnectTime\":\"2026-06-19T14:30:00\",\"NERSuccess\":1,\"ChargingStatus\":1,\"DurationSec\":60,"
                + "\"StartTime\":\"2026-06-19T14:30:00\",\"AnsIdTerm\":42,\"MatchedPrefixSupplier\":\"1712\"},"
                + "\"Customer\":{\"servicegroup\":10,\"Prefix\":\"1712\",\"unitPriceOrCharge\":1.0,\"idBilledUom\":\"BDT\","
                + "\"BilledAmount\":1.0,\"TaxAmount1\":0.5}}]";

        List<CallSummary> built = daily.buildBatch(json.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, built.size());
        CallSummary s = built.get(0);
        assertEquals(1, s.totalcalls);
        assertEquals(1, s.connectedcalls);
        assertEquals(0, s.customercost.compareTo(new BigDecimal("1.0")));
        assertEquals(LocalDateTime.of(2026, 6, 19, 0, 0), s.tup_starttime);
    }

    @Test
    void decodes_the_v2_blob_and_picks_the_customer_leg() {
        // blob v2 (PINNED): {Cdr, Chargeables:[ALL legs]} — the voice bean reads assignedDirection==1 and
        // must NOT take the supplier leg's numbers; the 5 restored SG10 cdr fields wire through, including
        // the two STRING-typed package fields (parsed, per dotnet ruling A4)
        String json = "[{\"Cdr\":{\"SwitchId\":1,\"InPartnerId\":5,\"CountryCode\":\"880\","
                + "\"ConnectTime\":\"2026-06-19T14:30:00\",\"NERSuccess\":1,\"ChargingStatus\":1,\"DurationSec\":60,"
                + "\"StartTime\":\"2026-06-19T14:30:00\",\"AnsIdTerm\":42,\"MatchedPrefixSupplier\":\"1712\","
                + "\"MatchedPrefixCustomer\":\"1712C\",\"ZAmount\":0.25,\"CostAnsIn\":0.3,"
                + "\"AdditionalSystemCodes\":\"12.5\",\"AdditionalPartyNumber\":\"77\"},"
                + "\"Chargeables\":["
                + "{\"servicegroup\":10,\"servicefamily\":10,\"assignedDirection\":2,\"Prefix\":\"1712S\","
                + "\"unitPriceOrCharge\":9.9,\"idBilledUom\":\"USD\",\"BilledAmount\":9.9},"
                + "{\"servicegroup\":10,\"servicefamily\":10,\"assignedDirection\":1,\"Prefix\":\"1712\","
                + "\"unitPriceOrCharge\":1.0,\"idBilledUom\":\"BDT\",\"BilledAmount\":1.0,\"TaxAmount1\":0.5}"
                + "]}]";

        List<CallSummary> built = daily.buildBatch(json.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, built.size(), "one summary per cdr, not per leg");
        CallSummary s = built.get(0);
        assertEquals(0, s.customercost.compareTo(new BigDecimal("1.0")), "customer leg's cost, NOT the supplier's 9.9");
        assertEquals("BDT", s.tup_customercurrency, "customer leg picked by direction, not list order");
        assertEquals("1712C", s.tup_matchedprefixcustomer, "from cdr.MatchedPrefixCustomer (work order §2)");
        assertEquals(0, s.vat.compareTo(new BigDecimal("0.25")), "vat = cdr.ZAmount");
        assertEquals("BDT", s.tup_vatcurrency);
        assertEquals(0, s.longDecimalAmount1.compareTo(new BigDecimal("0.3")), "anscost = cdr.CostAnsIn");
        assertEquals(0, s.longDecimalAmount2.compareTo(new BigDecimal("12.5")), "package amount parsed from STRING");
        assertEquals(77, s.intAmount1, "package id parsed from STRING");
    }

    @Test
    void a_non_charged_call_gets_only_the_pre_charge_stamps() {
        // legacy early-return: ChargingStatus != 1 -> counts/durations + country/destId/prefixsupplier only,
        // NO rate/cost/tax/currency block
        String json = "[{\"Cdr\":{\"SwitchId\":1,\"CountryCode\":\"880\",\"ChargingStatus\":0,\"DurationSec\":60,"
                + "\"StartTime\":\"2026-06-19T14:30:00\",\"AnsIdTerm\":42,\"MatchedPrefixSupplier\":\"1712\","
                + "\"MatchedPrefixCustomer\":\"1712C\",\"ZAmount\":0.25},"
                + "\"Chargeables\":[{\"servicegroup\":10,\"assignedDirection\":1,\"unitPriceOrCharge\":1.0,"
                + "\"idBilledUom\":\"BDT\",\"BilledAmount\":1.0,\"TaxAmount1\":0.5}]}]";

        List<CallSummary> built = daily.buildBatch(json.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, built.size(), "the call still counts");
        CallSummary s = built.get(0);
        assertEquals(1, s.totalcalls);
        assertEquals(0, s.successfulcalls, "ChargingStatus 0 sums as 0");
        assertEquals("880", s.tup_countryorareacode);
        assertEquals("42", s.tup_destinationId);
        assertEquals("1712", s.tup_matchedprefixsupplier);
        assertEquals(0, s.customercost.compareTo(BigDecimal.ZERO), "cost block skipped for a non-charged call");
        assertEquals("", s.tup_customercurrency);
        assertEquals("", s.tup_matchedprefixcustomer, "customer prefix is a POST-charge stamp on SG10");
        assertEquals(0, s.vat.compareTo(BigDecimal.ZERO), "vat block skipped");
        assertEquals("", s.tup_vatcurrency);
    }

    @Test
    void exposes_its_table_columns_and_bucket() {
        assertEquals(CdrTestSupport.DAY_TABLE, daily.table());
        assertEquals(CallSummary.INSERT_COLUMNS, daily.insertColumnsCsv());
        assertEquals("tup_starttime", daily.bucketColumn());
        assertEquals("cdr", daily.entityType());
    }

    @Test
    void key_decimals_are_canonicalized_to_the_columns_6dp_before_keying() {
        // MySQL stores tup_customerrate as DECIMAL(18,6): 1.5000004 and 1.4999996 both land as 1.500000.
        // The builder must canonicalize BEFORE the tuple key is taken, or a reloaded row keys differently
        // from a fresh build of the same call -> duplicate windows / uq_tuple violations.
        LocalDateTime t = CdrTestSupport.at(2026, 6, 19, 14, 30);

        Collection<CallSummary> rows = CdrTestSupport.rollup(daily,
                List.of(CdrTestSupport.sg10EntryWithRate(t, new BigDecimal("1.5000004")),
                        CdrTestSupport.sg10EntryWithRate(t, new BigDecimal("1.4999996"))));

        assertEquals(1, rows.size(), "both rates canonicalize to 1.500000 -> ONE window, not two");
        CallSummary merged = rows.iterator().next();
        assertEquals(2, merged.totalcalls);
        assertEquals(new BigDecimal("1.500000"), merged.tup_customerrate, "stored exactly as MySQL will store it");
    }
}
