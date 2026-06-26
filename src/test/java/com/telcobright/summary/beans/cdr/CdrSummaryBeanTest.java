package com.telcobright.summary.beans.cdr;

import com.telcobright.summary.testkit.CdrTestSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;

import static com.telcobright.summary.testkit.CdrTestSupport.toJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The bean's event->entity build: service-group filtering, per-window bucketing, persistence metadata. */
class CdrSummaryBeanTest {

    private final CdrSummaryBean daily = CdrTestSupport.dailyBean();
    private final CdrSummaryBean hourly = CdrTestSupport.hourlyBean();

    @Test
    void build_skips_events_of_another_service_group() {
        ZonedDateTime t = CdrTestSupport.at(2026, 6, 19, 14, 30);
        assertNull(daily.build(toJson(CdrTestSupport.sg11Call(t))), "SG11 event filtered by the SG10 bean");
        assertNotNull(daily.build(toJson(CdrTestSupport.sg10Call(t))));
    }

    @Test
    void daily_and_hourly_beans_bucket_the_same_event_differently() {
        ZonedDateTime t = CdrTestSupport.at(2026, 6, 19, 14, 30);

        CdrSummary day = daily.build(toJson(CdrTestSupport.sg10Call(t)));
        CdrSummary hour = hourly.build(toJson(CdrTestSupport.sg10Call(t)));

        assertEquals(LocalDateTime.of(2026, 6, 19, 0, 0), day.tup_starttime);
        assertEquals(LocalDateTime.of(2026, 6, 19, 14, 0), hour.tup_starttime);
        assertEquals(day.tup_starttime, daily.bucketOf(day));
    }

    @Test
    void exposes_its_table_columns_and_bucket() {
        assertEquals(CdrTestSupport.DAY_TABLE, daily.table());
        assertEquals(CdrSummary.INSERT_COLUMNS, daily.insertColumnsCsv());
        assertEquals("tup_starttime", daily.bucketColumn());
    }
}
