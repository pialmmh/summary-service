package com.telcobright.summary.bean.spi;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The house partition rule: the CREATE carries the full daily set up front, closed by pMAX. */
class DdlPartitionsTest {

    @Test
    void renders_one_partition_per_day_plus_the_max_catchall() {
        String clause = DdlPartitions.dailyRange("tup_starttime", LocalDate.of(2026, 1, 1), 3);

        assertTrue(clause.startsWith("\nPARTITION BY RANGE COLUMNS(tup_starttime) ("), clause);
        assertTrue(clause.contains("PARTITION p20260101 VALUES LESS THAN ('2026-01-02 00:00:00'),"));
        assertTrue(clause.contains("PARTITION p20260102 VALUES LESS THAN ('2026-01-03 00:00:00'),"));
        assertTrue(clause.contains("PARTITION p20260103 VALUES LESS THAN ('2026-01-04 00:00:00'),"));
        assertTrue(clause.endsWith("PARTITION pMAX VALUES LESS THAN (MAXVALUE)\n)"));
        assertEquals(4, clause.split("PARTITION p", -1).length - 1, "3 daily partitions + pMAX");
    }

    @Test
    void this_year_is_the_tenants_year_never_the_jvms() {
        // 31 December 19:00 UTC is 1 January 01:00 in Dhaka: a container in UTC would still start the horizon a year back
        Instant newYearsNightInDhaka = Instant.parse("2026-12-31T19:00:00Z");

        assertEquals(LocalDate.of(2027, 1, 1), DdlPartitions.defaultStart(newYearsNightInDhaka, ZoneId.of("Asia/Dhaka")));
        assertEquals(LocalDate.of(2026, 1, 1), DdlPartitions.defaultStart(newYearsNightInDhaka, ZoneId.of("UTC")), "what the JVM's own clock would say");
    }

    @Test
    void the_tenants_zone_is_the_profiles_and_dhaka_when_it_names_none() {
        Config none = new SmallRyeConfigBuilder().build();
        Config named = new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(Map.of("summary.zone", "Asia/Kathmandu"), "test", 300)).build();

        assertEquals(ZoneId.of("Asia/Dhaka"), DdlPartitions.tenantZone(none));
        assertEquals(ZoneId.of("Asia/Kathmandu"), DdlPartitions.tenantZone(named));
    }
}
