package com.telcobright.summary.bean.spi;

import org.eclipse.microprofile.config.ConfigProvider;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Renders the {@code PARTITION BY RANGE COLUMNS(<bucket>)} clause for a summary table's self-provisioning
 * DDL — the house rule: a partitioned table is CREATED with its FULL daily partition set up front (never
 * create-bare-then-ALTER; a future extension adds all new partitions in ONE ALTER). Horizon comes from
 * {@code summary.ddl.partition-start} (ISO date, default Jan 1 of the current year) and
 * {@code summary.ddl.partition-days} (default 730), closed by a {@code pMAX} catch-all.
 */
public final class DdlPartitions {

    private static final DateTimeFormatter PARTITION_NAME = DateTimeFormatter.BASIC_ISO_DATE;   // p20260101

    private DdlPartitions() {
    }

    /** The clause with the horizon taken from config (what the beans' {@code tableDdl()} uses). */
    public static String dailyRangeFromConfig(String bucketColumn) {
        LocalDate start = LocalDate.parse(ConfigProvider.getConfig()
                .getOptionalValue("summary.ddl.partition-start", String.class)
                .orElse(LocalDate.now().getYear() + "-01-01"));
        int days = ConfigProvider.getConfig()
                .getOptionalValue("summary.ddl.partition-days", Integer.class).orElse(730);
        return dailyRange(bucketColumn, start, days);
    }

    /** One partition per day for {@code days} days from {@code start}, plus a {@code pMAX} catch-all. */
    public static String dailyRange(String bucketColumn, LocalDate start, int days) {
        StringBuilder clause = new StringBuilder("\nPARTITION BY RANGE COLUMNS(").append(bucketColumn).append(") (");
        LocalDate day = start;
        for (int i = 0; i < days; i++) {
            LocalDate next = day.plusDays(1);
            clause.append("\n  PARTITION p").append(PARTITION_NAME.format(day))
                    .append(" VALUES LESS THAN ('").append(next).append(" 00:00:00'),");
            day = next;
        }
        clause.append("\n  PARTITION pMAX VALUES LESS THAN (MAXVALUE)\n)");
        return clause.toString();
    }
}
