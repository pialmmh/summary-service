package com.telcobright.summary.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.beans.cdr.Cdr;
import com.telcobright.summary.beans.cdr.CdrBlobEntry;
import com.telcobright.summary.beans.cdr.CdrBlobMapper;
import com.telcobright.summary.beans.cdr.CdrSummary;
import com.telcobright.summary.beans.cdr.CdrSummaryBean;
import com.telcobright.summary.beans.cdr.Customer;
import com.telcobright.summary.outbox.internal.OutboxCodec;
import com.telcobright.summary.registry.spi.BeanConfig;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Builders for the outbox tests: ready cdr beans (daily / hourly), SG10/SG11 {@code {Cdr, Customer}} blob
 * entries mirroring the legacy fixtures (switch 1, partner 5, one 60s connected/charged call costing 1.0, tax
 * 0.5, NER success), and helpers to pack a batch the way billing does (JSON → gzip → base64).
 */
public final class CdrTestSupport {

    public static final String DAY_TABLE = "sum_voice_day_03";
    public static final String HOUR_TABLE = "sum_voice_hr_03";
    private static final ObjectMapper MAPPER = CdrBlobMapper.create();

    private CdrTestSupport() {
    }

    public static CdrSummaryBean dailyBean() {
        return bean("dailyCdrSummary", "daily", DAY_TABLE);
    }

    public static CdrSummaryBean hourlyBean() {
        return bean("hourlyCdrSummary", "hourly", HOUR_TABLE);
    }

    public static CdrSummaryBean bean(String name, String window, String table) {
        BeanConfig config = new BeanConfig(name, "cdr", WindowSize.parse(window), table, 10, "mediationContext");
        return new CdrSummaryBean(MAPPER, config);
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

    /** A daily CdrSummary built through the real bean path (totalcalls=1), for cache/engine tests. */
    public static CdrSummary daySummary(LocalDateTime start) {
        return daySummary(start, 42);
    }

    public static CdrSummary daySummary(LocalDateTime start, int ansIdTerm) {
        return dailyBean().buildBatch(batchJson(List.of(sg10Entry(start, ansIdTerm)))).get(0);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
