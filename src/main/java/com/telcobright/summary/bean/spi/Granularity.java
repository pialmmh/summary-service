package com.telcobright.summary.bean.spi;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * A time-window granularity. It knows how to truncate an event instant to the START of the window
 * it falls in (the "bucket"), expressed as wall-clock {@link LocalDateTime} in the bean's zone — that
 * value is the summary row's bucket key (e.g. {@code tup_starttime}).
 *
 * <p>DAY and HOUR are the reference windows; the rest exist so a bean can declare 15-minute / weekly /
 * monthly counters with no engine change.
 */
public enum Granularity {
    FIFTEEN_MIN,
    HOUR,
    DAY,
    WEEK,
    MONTH;

    /** The start of the window this instant falls in, as wall-clock time in {@code zone}. */
    public LocalDateTime bucketStart(Instant eventTime, ZoneId zone) {
        LocalDateTime t = LocalDateTime.ofInstant(eventTime, zone);
        return switch (this) {
            case FIFTEEN_MIN -> t.truncatedTo(ChronoUnit.HOURS).plusMinutes((t.getMinute() / 15) * 15L);
            case HOUR -> t.truncatedTo(ChronoUnit.HOURS);
            case DAY -> t.toLocalDate().atStartOfDay();
            case WEEK -> t.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
            case MONTH -> t.toLocalDate().withDayOfMonth(1).atStartOfDay();
        };
    }
}
