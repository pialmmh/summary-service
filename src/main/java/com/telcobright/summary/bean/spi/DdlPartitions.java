package com.telcobright.summary.bean.spi;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Renders the {@code PARTITION BY RANGE COLUMNS(<bucket>)} clause for a summary table's self-provisioning
 * DDL — the house rule: a partitioned table is CREATED with its FULL daily partition set up front (never
 * create-bare-then-ALTER; a future extension adds all new partitions in ONE ALTER). Horizon comes from
 * {@code summary.ddl.partition-start} (ISO date, default Jan 1 of the current year in the TENANT's zone) and
 * {@code summary.ddl.partition-days} (default 730), closed by a {@code pMAX} catch-all.
 */
public final class DdlPartitions {

    private static final DateTimeFormatter PARTITION_NAME = DateTimeFormatter.BASIC_ISO_DATE;   // p20260101

    private DdlPartitions() {
    }

    /** The tenant's zone when the profile names none ({@code summary.zone}): every tenant today keeps Dhaka's wall clock. */
    public static final String DEFAULT_ZONE = "Asia/Dhaka";

    /** The clause with the horizon taken from config (what the beans' {@code tableDdl()} uses). */
    public static String dailyRangeFromConfig(String bucketColumn) {
        Config config = ConfigProvider.getConfig();
        LocalDate start = config.getOptionalValue("summary.ddl.partition-start", String.class).map(LocalDate::parse)
                .orElseGet(() -> defaultStart(Instant.now(), tenantZone(config)));
        int days = config.getOptionalValue("summary.ddl.partition-days", Integer.class).orElse(730);
        return dailyRange(bucketColumn, start, days);
    }

    /**
     * The zone whose wall clock the tenant's times are written in ({@code summary.zone}, default
     * {@value #DEFAULT_ZONE}). A window is cut on the cdr's own wall-clock time, so no zone is needed there; this
     * is for the one question the service asks a clock itself — "which year is it?" — and it is the TENANT's
     * year, never the JVM's (a container runs in UTC: on the tenant's 1 January it is still last year there).
     */
    public static ZoneId tenantZone(Config config) {
        return ZoneId.of(config.getOptionalValue("summary.zone", String.class).orElse(DEFAULT_ZONE));
    }

    /** The first partition's day when the profile names none: 1 January of the year it is NOW in the tenant's zone. */
    public static LocalDate defaultStart(Instant now, ZoneId tenantZone) {
        return LocalDate.of(now.atZone(tenantZone).getYear(), 1, 1);
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
