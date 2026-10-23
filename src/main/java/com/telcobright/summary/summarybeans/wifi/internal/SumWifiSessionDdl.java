package com.telcobright.summary.summarybeans.wifi.internal;

import com.telcobright.summary.bean.spi.SummaryTableSpec;
import com.telcobright.summary.summarybeans.wifi.model.WifiSessionSummary;

/**
 * The {@code sum_wifi_hr_101} / {@code sum_wifi_day_101} table the WiFi session beans SELF-PROVISION with at
 * activation, described once for every engine ({@link SummaryTableSpec}; rendered by {@code TableDdl}) — the ad
 * table's pattern ({@code SumAdDdl}): MySQL partitions it by day on the bucket (the full set inside the CREATE), so
 * every unique key carries the bucket — hence PK {@code (id, tup_starttime)}, kept on PostgreSQL too so the key is
 * one on both engines. The sums are BIGINT, the money DECIMAL(18,6), the derived average DECIMAL(18,6). Indexes on the
 * bucket, and on (site, bucket) — the owner's report "average session duration per site per day".
 *
 * <p>The table is net-new and this service's alone, so the description is KEPT UP TO DATE: a table made by an earlier
 * version gets what it lacks at its first use ({@code TableDdl.bringUpToDate}). {@code tup_gw} (SS-0007, the BRAS) is
 * such a column: a table 3829e18 made gets {@code VARCHAR(64) NOT NULL DEFAULT ''} at the new version's first use, and the
 * rows it holds read {@code ''}. It has a default for that reason — a column without one cannot be given to rows that are
 * there. No index names it, and this version adds none: a new index WOULD reach an older table on PostgreSQL (its
 * {@code CREATE INDEX IF NOT EXISTS} runs at every first use) and not on MySQL (the indexes are inside the CREATE).
 */
final class SumWifiSessionDdl {

    private SumWifiSessionDdl() {
    }

    static SummaryTableSpec table(String name) {
        return SummaryTableSpec.table(name)
                .identity("id")
                // every text's width is the builder's cut (WifiSessionSummaryBuilder): a value is never wider than its column
                .integer("tup_switchid")
                .integer("tup_inpartnerid")
                .varchar("tup_gw", WifiSessionSummaryBuilder.GW_WIDTH)       // SS-0007: the BRAS — an older table gets it, default ''
                .varchar("tup_site", WifiSessionSummaryBuilder.SITE_WIDTH)
                .varchar("tup_zone", WifiSessionSummaryBuilder.ZONE_WIDTH)
                .varchar("tup_granttier", WifiSessionSummaryBuilder.GRANTTIER_WIDTH)
                .integer("tup_category")
                .varchar("tup_packagecode", WifiSessionSummaryBuilder.PACKAGECODE_WIDTH)
                .varchar("tup_customercurrency", WifiSessionSummaryBuilder.CURRENCY_WIDTH)
                .varchar("tup_causefamily", WifiSessionSummaryBuilder.CAUSEFAMILY_WIDTH)
                .datetime("tup_starttime")
                .bigint("sessions")
                .bigint("sessions_connected")
                .bigint("total_duration_sec")
                .decimal("avg_session_sec", 18, 6)                  // DERIVED: total_duration_sec / sessions_connected
                .bigint("bytes_up")
                .bigint("bytes_dn")
                .decimal("customercost", 18, 6)
                .primaryKey("id", WifiSessionSummary.BUCKET_COLUMN) // the partition column is in every unique key
                .index("ix_starttime", "tup_starttime")
                .index("ix_site_starttime", "tup_site", "tup_starttime")
                .partitionedByDayOn(WifiSessionSummary.BUCKET_COLUMN)
                .keptUpToDate()                                     // net-new, ours alone: an older table is brought up to this
                .build();
    }
}
