package com.telcobright.summary.summarybeans.wifi.internal;

import com.telcobright.summary.bean.spi.SummaryTableSpec;
import com.telcobright.summary.summarybeans.wifi.model.WifiDestinationSummary;

/**
 * The {@code sum_wifi_dst_hr} / {@code sum_wifi_dst_day} table the WiFi destination beans SELF-PROVISION with at
 * activation, described once for every engine ({@link SummaryTableSpec}; rendered by {@code TableDdl}) — the ad
 * table's pattern: PK {@code (id, tup_starttime)} on both engines (MySQL partitions by day on the bucket, the full set
 * inside the CREATE). Every sum is BIGINT; there is no money column. Indexes on the bucket, and on (site, remote,
 * bucket) — the owner's report "top destinations per site per hour". Net-new and this service's alone: KEPT UP TO DATE.
 */
final class SumWifiDestinationDdl {

    private SumWifiDestinationDdl() {
    }

    static SummaryTableSpec table(String name) {
        return SummaryTableSpec.table(name)
                .identity("id")
                // every text's width is the builder's cut (WifiDestinationSummaryBuilder): a value is never wider than its column
                .varchar("tup_gw", WifiDestinationSummaryBuilder.GW_WIDTH)
                .varchar("tup_site", WifiDestinationSummaryBuilder.SITE_WIDTH)
                .varchar("tup_zone", WifiDestinationSummaryBuilder.ZONE_WIDTH)
                .varchar("tup_remote", WifiDestinationSummaryBuilder.REMOTE_WIDTH)
                .integer("tup_proto")
                .integer("tup_port")
                .datetime("tup_starttime")
                .bigint("bytes_up")
                .bigint("bytes_dn")
                .bigint("packets_up")
                .bigint("packets_dn")
                .bigint("flows")
                .primaryKey("id", WifiDestinationSummary.BUCKET_COLUMN)   // the partition column is in every unique key
                .index("ix_starttime", "tup_starttime")
                .index("ix_site_remote_starttime", "tup_site", "tup_remote", "tup_starttime")
                .partitionedByDayOn(WifiDestinationSummary.BUCKET_COLUMN)
                .keptUpToDate()                                           // net-new, ours alone: an older table is brought up to this
                .build();
    }
}
