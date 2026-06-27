package com.telcobright.summary.bean.spi;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * The time window a summary bean rolls events into, configured per bean in YAML. It truncates an event time to
 * the START of the window it falls in (the bucket) — that value becomes the entity's {@code tup_starttime} and
 * identifies the window. The event time is the cdr's {@code StartTime}, already wall-clock local (no zone
 * conversion), so this works on a plain {@link LocalDateTime}.
 *
 * <p>Accepted YAML tokens (case-insensitive):
 * <ul>
 *   <li>{@code "5min"}, {@code "10min"}, {@code "15min"}, … — every {@code N} minutes, {@code N} a multiple of 5.
 *       Buckets align from midnight, so any multiple is deterministic.</li>
 *   <li>{@code "hourly"}, {@code "daily"}, {@code "weekly"} (ISO week, Monday start — week 1..52/53 of the
 *       year), {@code "monthly"}, {@code "yearly"}.</li>
 * </ul>
 */
public final class WindowSize {

    private enum Unit {MINUTES, HOUR, DAY, WEEK, MONTH, YEAR}

    private final Unit unit;
    private final int minutes;   // only for Unit.MINUTES
    private final String token;

    private WindowSize(Unit unit, int minutes, String token) {
        this.unit = unit;
        this.minutes = minutes;
        this.token = token;
    }

    public static WindowSize parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("window size is required");
        }
        String token = raw.trim().toLowerCase();
        return switch (token) {
            case "hourly" -> new WindowSize(Unit.HOUR, 0, token);
            case "daily" -> new WindowSize(Unit.DAY, 0, token);
            case "weekly" -> new WindowSize(Unit.WEEK, 0, token);
            case "monthly" -> new WindowSize(Unit.MONTH, 0, token);
            case "yearly" -> new WindowSize(Unit.YEAR, 0, token);
            default -> parseMinutes(token);
        };
    }

    private static WindowSize parseMinutes(String token) {
        if (!token.endsWith("min")) {
            throw new IllegalArgumentException("unknown window size: '" + token
                    + "' (expected Nmin / hourly / daily / weekly / monthly / yearly)");
        }
        int n;
        try {
            n = Integer.parseInt(token.substring(0, token.length() - 3).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid minute window: '" + token + "'");
        }
        if (n <= 0 || n % 5 != 0) {
            throw new IllegalArgumentException("minute window must be a positive multiple of 5: '" + token + "'");
        }
        return new WindowSize(Unit.MINUTES, n, n + "min");
    }

    /** The start of the window this local event time falls in. */
    public LocalDateTime bucketStart(LocalDateTime t) {
        return switch (unit) {
            case MINUTES -> {
                int minutesIntoDay = t.getHour() * 60 + t.getMinute();
                int floored = (minutesIntoDay / minutes) * minutes;
                yield t.toLocalDate().atStartOfDay().plusMinutes(floored);
            }
            case HOUR -> t.truncatedTo(ChronoUnit.HOURS);
            case DAY -> t.toLocalDate().atStartOfDay();
            case WEEK -> t.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
            case MONTH -> t.toLocalDate().withDayOfMonth(1).atStartOfDay();
            case YEAR -> t.toLocalDate().withDayOfYear(1).atStartOfDay();
        };
    }

    @Override
    public String toString() {
        return token;
    }
}
