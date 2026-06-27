package com.telcobright.summary.bean.spi;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The YAML-configured window granularities truncate a local event time to the right bucket start. */
class WindowSizeTest {

    // 2026-06-19 (Friday) 14:37 local
    private static final LocalDateTime T = LocalDateTime.of(2026, 6, 19, 14, 37, 0);

    private static LocalDateTime bucket(String token) {
        return WindowSize.parse(token).bucketStart(T);
    }

    @Test
    void minute_windows_align_from_midnight() {
        assertEquals(LocalDateTime.of(2026, 6, 19, 14, 35), bucket("5min"));
        assertEquals(LocalDateTime.of(2026, 6, 19, 14, 30), bucket("15min"));
        assertEquals(LocalDateTime.of(2026, 6, 19, 14, 0), bucket("60min"));
    }

    @Test
    void named_windows_truncate_to_their_unit() {
        assertEquals(LocalDateTime.of(2026, 6, 19, 14, 0), bucket("hourly"));
        assertEquals(LocalDateTime.of(2026, 6, 19, 0, 0), bucket("daily"));
        assertEquals(LocalDateTime.of(2026, 6, 1, 0, 0), bucket("monthly"));
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), bucket("yearly"));
    }

    @Test
    void weekly_buckets_to_the_monday_of_the_week() {
        LocalDateTime weekly = bucket("weekly");
        assertEquals(DayOfWeek.MONDAY, weekly.getDayOfWeek());
        assertEquals(LocalDateTime.of(2026, 6, 15, 0, 0), weekly);   // the Monday on/before Fri 2026-06-19
    }

    @Test
    void rejects_non_multiple_of_five_and_garbage() {
        assertThrows(IllegalArgumentException.class, () -> WindowSize.parse("7min"));
        assertThrows(IllegalArgumentException.class, () -> WindowSize.parse("fortnightly"));
    }

    @Test
    void token_round_trips_in_toString() {
        assertTrue(WindowSize.parse("15min").toString().contains("15min"));
    }
}
