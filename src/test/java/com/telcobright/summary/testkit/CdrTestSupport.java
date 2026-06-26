package com.telcobright.summary.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.beans.cdr.CdrVoiceSummaryBean;
import com.telcobright.summary.beans.cdr.RatedCdrEvent;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Builders for engine tests: a ready cdr-voice bean (no CDI) and an SG10-style rated CDR mirroring the .NET
 * reference fixtures (switch 1, partner 5, one 60s connected/charged call costing 1.0 with 0.5 tax). The
 * start time is a wall-clock instant in the bean's zone, so day/hour buckets are predictable.
 */
public final class CdrTestSupport {

    public static final ZoneId ZONE = ZoneId.of("Asia/Dhaka");
    public static final String DAY_TABLE = "sum_voice_day_03";
    public static final String HOUR_TABLE = "sum_voice_hr_03";

    private CdrTestSupport() {
    }

    public static CdrVoiceSummaryBean bean() {
        return new CdrVoiceSummaryBean(new ObjectMapper(), "rated-cdr", "rated-cdr-correction", 1000,
                DAY_TABLE, HOUR_TABLE, ZONE.getId());
    }

    /** An SG10-style call at the given local wall-clock time in {@link #ZONE}. */
    public static RatedCdrEvent sg10Call(ZonedDateTime start) {
        return new RatedCdrEvent(
                start.toInstant().toEpochMilli(),
                1, 5, 0, "in", "out",
                new BigDecimal("1.0"), new BigDecimal("0.8"),
                "1.1.1.1", "2.2.2.2",
                "880", "1712", "1712", "0", "42", "BDT", "BDT",
                1, true,
                new BigDecimal("60"), new BigDecimal("60"), new BigDecimal("60"),
                new BigDecimal("1.0"), BigDecimal.ZERO,
                new BigDecimal("0.5"), BigDecimal.ZERO);
    }

    public static ZonedDateTime at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZONE);
    }
}
