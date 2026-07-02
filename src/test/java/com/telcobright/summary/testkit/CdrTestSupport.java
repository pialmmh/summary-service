package com.telcobright.summary.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.engine.internal.SummaryCache;
import com.telcobright.summary.engine.spi.MergeMode;
import com.telcobright.summary.summarybeans.call.model.CallSummary;
import com.telcobright.summary.summarybeans.call.internal.CallSummaryBean;
import com.telcobright.summary.summarybeans.call.model.Cdr;
import com.telcobright.summary.summarybeans.call.model.CdrBlobEntry;
import com.telcobright.summary.summarybeans.call.internal.CdrBlobMapper;
import com.telcobright.summary.summarybeans.call.model.Customer;
import com.telcobright.summary.beans.DailySummaryBuilder;
import com.telcobright.summary.beans.HourlySummaryBuilder;
import com.telcobright.summary.outbox.internal.OutboxCodec;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Builders for the outbox tests: ready call summary beans (daily / hourly via the public builder API, any
 * window) with no CDI / no config, SG10/SG11 {@code {Cdr, Customer}} blob entries mirroring the legacy fixtures (switch 1,
 * partner 5, one 60s connected/charged call costing 1.0, tax 0.5, NER success), data generators for many
 * windows / volume, and a {@link #rollup} helper that runs the real build→bucket→merge path so a test can
 * assert per-window aggregates by reading entity fields. Packing helpers gzip+base64 the way billing does.
 */
public final class CdrTestSupport {

    public static final String DAY_TABLE = "sum_voice_day_3";
    public static final String HOUR_TABLE = "sum_voice_hr_3";
    private static final int SERVICE_GROUP = 10;
    private static final String TABLE_SUFFIX = "3";
    private static final ObjectMapper MAPPER = CdrBlobMapper.create();

    private CdrTestSupport() {
    }

    // ---- beans ----

    public static CallSummaryBean dailyBean() {
        return (CallSummaryBean) DailySummaryBuilder.create(MAPPER)   // derives DAY_TABLE = sum_voice_day_3
                .serviceGroup(SERVICE_GROUP).tableSuffix(TABLE_SUFFIX).context("mediationContext").build();
    }

    public static CallSummaryBean hourlyBean() {
        return (CallSummaryBean) HourlySummaryBuilder.create(MAPPER)  // derives HOUR_TABLE = sum_voice_hr_3
                .serviceGroup(SERVICE_GROUP).tableSuffix(TABLE_SUFFIX).context("mediationContext").build();
    }

    /** A call bean fixed to ANY window (test-only) — lets one dataset be rolled up at 5min/weekly/monthly/…. */
    public static CallSummaryBean beanForWindow(String window) {
        WindowSize w = WindowSize.parse(window);
        return new CallSummaryBean(MAPPER, "test-" + window, TABLE_SUFFIX, SERVICE_GROUP, null) {
            @Override
            public WindowSize window() {
                return w;
            }
        };
    }

    public static LocalDateTime at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute, 0);
    }

    // ---- blob entries ----

    public static CdrBlobEntry sg10Entry(LocalDateTime start) {
        return sg10Entry(start, 42);
    }

    public static CdrBlobEntry sg10Entry(LocalDateTime start, int ansIdTerm) {
        return sg10Entry(start, ansIdTerm, bd("60"), bd("1.0"));
    }

    /** An SG10 call whose destination (ansIdTerm) varies the tuple key, and whose duration/cost are controllable. */
    public static CdrBlobEntry sg10Entry(LocalDateTime start, int ansIdTerm, BigDecimal durationSec, BigDecimal cost) {
        Cdr cdr = new Cdr(1, 5, 0, "in", "out", "1.1.1.1", "2.2.2.2", "880",
                start, 1, 1,
                durationSec, durationSec, durationSec, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                start, ansIdTerm, null, "1712", null, BigDecimal.ZERO, bd("0.8"), BigDecimal.ZERO);
        Customer customer = new Customer(10, "1712", bd("1.0"), "BDT", cost, bd("0.5"), null, null);
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

    // ---- data generators (deterministic, so expected aggregates are computable) ----

    /** {@code count} identical-dimension SG10 calls starting at {@code from}, each {@code stepMinutes} apart. */
    public static List<CdrBlobEntry> series(LocalDateTime from, int stepMinutes, int count) {
        List<CdrBlobEntry> out = new ArrayList<>(count);
        LocalDateTime t = from;
        for (int i = 0; i < count; i++) {
            out.add(sg10Entry(t));
            t = t.plusMinutes(stepMinutes);
        }
        return out;
    }

    /** {@code count} SG10 calls at the SAME instant but with distinct destinations → distinct tuple keys. */
    public static List<CdrBlobEntry> distinctKeysAt(LocalDateTime at, int count) {
        List<CdrBlobEntry> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(sg10Entry(at, i));
        }
        return out;
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

    // ---- rollup (the real build → bucket → merge path; read fields to assert per-window aggregates) ----

    /** Build {@code calls} through {@code bean}, fold them into a cache, and return the aggregated window rows. */
    public static Collection<CallSummary> rollup(CallSummaryBean bean, List<CdrBlobEntry> calls) {
        SummaryCache<CallSummary> cache = new SummaryCache<>(bean.table(), CallSummary.INSERT_COLUMNS, CallSummary.BUCKET_COLUMN);
        for (CallSummary built : bean.buildBatch(batchJson(calls))) {
            cache.merge(built, MergeMode.ADD);
        }
        return cache.rows();
    }

    public static long totalCalls(Collection<CallSummary> rows) {
        return rows.stream().mapToLong(r -> r.totalcalls).sum();
    }

    public static BigDecimal totalCost(Collection<CallSummary> rows) {
        return rows.stream().map(r -> r.customercost).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---- a daily CallSummary built through the real bean path (totalcalls=1), for cache/engine tests ----

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
