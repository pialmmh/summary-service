package com.telcobright.summary.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.beans.cdr.CdrSummary;
import com.telcobright.summary.beans.cdr.CdrSummaryBean;
import com.telcobright.summary.beans.cdr.RatedCdrEvent;
import com.telcobright.summary.registry.spi.BeanConfig;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Builders for engine tests: ready cdr-voice beans (daily / hourly, no CDI) and an SG10-style rated CDR
 * mirroring the legacy fixtures (switch 1, partner 5, one 60s connected/charged call costing 1.0, tax 0.5,
 * NER success). Start time is a wall-clock instant in the bean's zone, so day/hour buckets are predictable.
 */
public final class CdrTestSupport {

    public static final ZoneId ZONE = ZoneId.of("Asia/Dhaka");
    public static final String DAY_TABLE = "sum_voice_day_03";
    public static final String HOUR_TABLE = "sum_voice_hr_03";

    private CdrTestSupport() {
    }

    public static CdrSummaryBean dailyBean() {
        return bean("dailyCdrSummary", "daily", DAY_TABLE);
    }

    public static CdrSummaryBean hourlyBean() {
        return bean("hourlyCdrSummary", "hourly", HOUR_TABLE);
    }

    public static CdrSummaryBean bean(String name, String window, String table) {
        BeanConfig config = new BeanConfig(name, "cdr", "rated-cdr", "rated-cdr-correction", table,
                WindowSize.parse(window), 10, 1000, ZONE);
        return new CdrSummaryBean(new ObjectMapper(), config);
    }

    /** An SG10-style call at the given local wall-clock time in {@link #ZONE}, destination id 42. */
    public static RatedCdrEvent sg10Call(ZonedDateTime start) {
        return sg10Call(start, 42);
    }

    /** An SG10 call with an explicit destination (ansIdTerm), to make distinct rows in one window. */
    public static RatedCdrEvent sg10Call(ZonedDateTime start, int ansIdTerm) {
        long epoch = start.toInstant().toEpochMilli();
        return new RatedCdrEvent(
                10, epoch,
                1, 5, 0, "in", "out", "1.1.1.1", "2.2.2.2",
                epoch, 1, 1,
                new BigDecimal("60"), new BigDecimal("60"), new BigDecimal("60"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, "880",
                ansIdTerm, null, "1712", null,
                "1712", new BigDecimal("1.0"), "BDT", new BigDecimal("1.0"), new BigDecimal("0.5"),
                null, null,
                BigDecimal.ZERO, new BigDecimal("0.8"), BigDecimal.ZERO);
    }

    /** An SG11-style call (filtered out by an SG10 bean). */
    public static RatedCdrEvent sg11Call(ZonedDateTime start) {
        long epoch = start.toInstant().toEpochMilli();
        return new RatedCdrEvent(
                11, epoch,
                1, 5, 0, "in", "out", "1.1.1.1", "2.2.2.2",
                epoch, 1, 1,
                new BigDecimal("60"), new BigDecimal("60"), new BigDecimal("60"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, "880",
                null, 7, null, "1713",
                null, null, null, new BigDecimal("2.0"), new BigDecimal("0.3"),
                new BigDecimal("1.5"), new BigDecimal("5.0"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public static ZonedDateTime at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZONE);
    }

    /** A daily CdrSummary built through the real bean path (totalcalls=1), for cache/engine tests. */
    public static CdrSummary daySummary(ZonedDateTime start) {
        return daySummary(start, 42);
    }

    public static CdrSummary daySummary(ZonedDateTime start, int ansIdTerm) {
        return dailyBean().build(toJson(sg10Call(start, ansIdTerm)));
    }

    public static byte[] toJson(RatedCdrEvent event) {
        try {
            return new ObjectMapper().writeValueAsBytes(event);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
