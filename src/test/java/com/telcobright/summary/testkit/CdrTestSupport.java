package com.telcobright.summary.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.summarybeans.call.CallSummary;
import com.telcobright.summary.summarybeans.call.CallSummaryBean;
import com.telcobright.summary.summarybeans.call.Cdr;
import com.telcobright.summary.summarybeans.call.CdrBlobEntry;
import com.telcobright.summary.summarybeans.call.CdrBlobMapper;
import com.telcobright.summary.summarybeans.call.Customer;
import com.telcobright.summary.summarybeans.call.DailySummary;
import com.telcobright.summary.summarybeans.call.HourlySummary;
import com.telcobright.summary.outbox.internal.OutboxCodec;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Builders for the outbox tests: ready call summary beans (daily / hourly) wired explicitly (no CDI / no
 * config), SG10/SG11 {@code {Cdr, Customer}} blob entries mirroring the legacy fixtures (switch 1, partner 5,
 * one 60s connected/charged call costing 1.0, tax 0.5, NER success), and helpers to pack a batch the way
 * billing does (JSON → gzip → base64).
 */
public final class CdrTestSupport {

    public static final String DAY_TABLE = "sum_voice_day_03";
    public static final String HOUR_TABLE = "sum_voice_hr_03";
    private static final int SERVICE_GROUP = 10;
    private static final ObjectMapper MAPPER = CdrBlobMapper.create();

    private CdrTestSupport() {
    }

    public static CallSummaryBean dailyBean() {
        return new DailySummary(MAPPER, DAY_TABLE, SERVICE_GROUP, "mediationContext");
    }

    public static CallSummaryBean hourlyBean() {
        return new HourlySummary(MAPPER, HOUR_TABLE, SERVICE_GROUP, "mediationContext");
    }

    public static LocalDateTime at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute, 0);
    }

    // ---- blob entries ----

    public static CdrBlobEntry sg10Entry(LocalDateTime start) {
        return sg10Entry(start, 42);
    }

    public static CdrBlobEntry sg10Entry(LocalDateTime start, int ansIdTerm) {
        Cdr cdr = new Cdr(1, 5, 0, "in", "out", "1.1.1.1", "2.2.2.2", "880",
                start, 1, 1,
                bd("60"), bd("60"), bd("60"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                start, ansIdTerm, null, "1712", null, BigDecimal.ZERO, bd("0.8"), BigDecimal.ZERO);
        Customer customer = new Customer(10, "1712", bd("1.0"), "BDT", bd("1.0"), bd("0.5"), null, null);
        return new CdrBlobEntry(cdr, customer);
    }

    public static CdrBlobEntry sg11Entry(LocalDateTime start) {
        Cdr cdr = new Cdr(1, 5, 0, "in", "out", "1.1.1.1", "2.2.2.2", "880",
                start, 1, 1,
                bd("60"), bd("60"), bd("60"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                start, null, 7, null, "1713", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        Customer customer = new Customer(11, null, null, null, bd("2.0"), bd("0.3"), bd("1.5"), bd("5.0"));
        return new CdrBlobEntry(cdr, customer);
    }

    // ---- packing ----

    /** The decompressed JSON bytes of a batch (what bean.buildBatch consumes). */
    public static byte[] batchJson(List<CdrBlobEntry> entries) {
        try {
            return MAPPER.writeValueAsBytes(entries);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /** The encoded outbox `data` value (base64(gzip(json))) — what billing writes. */
    public static String encodedBatch(List<CdrBlobEntry> entries) {
        return OutboxCodec.encode(batchJson(entries));
    }

    /** A daily CallSummary built through the real bean path (totalcalls=1), for cache/engine tests. */
    public static CallSummary daySummary(LocalDateTime start) {
        return daySummary(start, 42);
    }

    public static CallSummary daySummary(LocalDateTime start, int ansIdTerm) {
        return dailyBean().buildBatch(batchJson(List.of(sg10Entry(start, ansIdTerm)))).get(0);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
