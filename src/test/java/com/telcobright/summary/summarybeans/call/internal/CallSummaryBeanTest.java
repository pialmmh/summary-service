package com.telcobright.summary.summarybeans.call.internal;

import com.telcobright.summary.summarybeans.call.model.CallSummary;
import com.telcobright.summary.summarybeans.call.model.CdrBlobEntry;
import com.telcobright.summary.summarybeans.call.model.Customer;
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
    void decodes_the_csharp_pascalcase_blob() {
        // exactly the PINNED on-the-wire shape: C# PascalCase keys, ISO datetimes, nulls omitted
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
        CdrBlobEntry base = CdrTestSupport.sg10Entry(t);
        Customer rateA = new Customer(10, "1712", new BigDecimal("1.5000004"), "BDT",
                new BigDecimal("1.0"), new BigDecimal("0.5"), null, null);
        Customer rateB = new Customer(10, "1712", new BigDecimal("1.4999996"), "BDT",
                new BigDecimal("1.0"), new BigDecimal("0.5"), null, null);

        Collection<CallSummary> rows = CdrTestSupport.rollup(daily,
                List.of(new CdrBlobEntry(base.cdr(), rateA), new CdrBlobEntry(base.cdr(), rateB)));

        assertEquals(1, rows.size(), "both rates canonicalize to 1.500000 -> ONE window, not two");
        CallSummary merged = rows.iterator().next();
        assertEquals(2, merged.totalcalls);
        assertEquals(new BigDecimal("1.500000"), merged.tup_customerrate, "stored exactly as MySQL will store it");
    }
}
