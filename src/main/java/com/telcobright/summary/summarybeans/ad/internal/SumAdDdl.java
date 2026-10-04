package com.telcobright.summary.summarybeans.ad.internal;

import com.telcobright.summary.bean.spi.SummaryTableSpec;
import com.telcobright.summary.summarybeans.ad.model.AdSummary;

/**
 * The {@code sum_ad_*} table the ad beans SELF-PROVISION with at activation, described once for every engine
 * ({@link SummaryTableSpec}; rendered by {@code TableDdl}). The column names are the ones ad-sphere's report roads
 * read ({@code JdbcCdrReader}); the PostgreSQL reference copy is {@code db/postgres/sum_ad.sql}.
 *
 * <p>MySQL partitions it by day on the bucket (the full set inside the CREATE), so there every unique key carries
 * the bucket — hence PK {@code (id, tup_starttime)}, kept on PostgreSQL too so the key is one on both engines.
 *
 * <p>The ad tables are net-new and this service's alone, so the description is KEPT UP TO DATE: a table made by an
 * earlier version gets what it lacks at its first use ({@code TableDdl.bringUpToDate}) — {@code tup_contentid}
 * (added 2026-10-04; the rows that are there read it as {@code ''}).
 */
final class SumAdDdl {

    private SumAdDdl() {
    }

    static SummaryTableSpec table(String name) {
        return SummaryTableSpec.table(name)
                .identity("id")
                .varchar("tup_tenant", 100)
                .integer("tup_partnerid")
                .integer("tup_campaignid")
                .varchar("tup_rulecode", 20)
                .varchar("tup_zone", 64)
                .varchar("tup_site", 64)
                .varchar("tup_app", AdSummaryBuilder.APP_WIDTH)   // the app's name is 64 at its source (ad_caller.app)
                .varchar("tup_mediakind", 16)
                .varchar("tup_outcome", 32)
                .datetime("tup_starttime")
                .bigint("views")
                .bigint("shown")
                .bigint("completed")
                .bigint("credited")
                .bigint("failed")
                .bigint("watchedsec")
                .decimal("chargedamount", 18, 6)
                .decimal("chargedunits", 18, 6)
                .varchar("tup_contentid", AdSummaryBuilder.CONTENT_WIDTH)   // the content shown; the LAST column (see AdSummary)
                .primaryKey("id", AdSummary.BUCKET_COLUMN)        // the partition column is in every unique key
                .index("ix_starttime", "tup_starttime")
                .index("ix_tenant_partner", "tup_tenant", "tup_partnerid", "tup_starttime")
                .partitionedByDayOn(AdSummary.BUCKET_COLUMN)
                .keptUpToDate()                                   // net-new, ours alone: an older table is brought up to this
                .build();
    }
}
