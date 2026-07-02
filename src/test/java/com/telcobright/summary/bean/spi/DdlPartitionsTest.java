package com.telcobright.summary.bean.spi;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

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
}
