package com.telcobright.summary.summarybeans.ad.internal;

import com.telcobright.summary.bean.spi.DdlPartitions;
import com.telcobright.summary.summarybeans.ad.model.AdSummary;

/**
 * The canonical {@code sum_ad_*} DDL the ad beans SELF-PROVISION with at activation: {@code CREATE TABLE IF NOT
 * EXISTS} carrying the FULL daily partition set up front (house rule: never create bare then ALTER). The table is
 * partitioned, so every unique key includes the partition column — PK {@code (id, tup_starttime)}. The same
 * column names ad-sphere's report roads read ({@code JdbcCdrReader}).
 */
final class SumAdDdl {

    private SumAdDdl() {
    }

    static String createTableIfNotExists(String table) {
        return "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "tup_tenant VARCHAR(100) NOT NULL DEFAULT '',"
                + "tup_partnerid INT NOT NULL DEFAULT 0,"
                + "tup_campaignid INT NOT NULL DEFAULT 0,"
                + "tup_rulecode VARCHAR(20) NOT NULL DEFAULT '',"
                + "tup_zone VARCHAR(64) NOT NULL DEFAULT '',"
                + "tup_site VARCHAR(64) NOT NULL DEFAULT '',"
                + "tup_app VARCHAR(64) NOT NULL DEFAULT '',"            // the app's name is 64 at its source (ad_caller.app)
                + "tup_mediakind VARCHAR(16) NOT NULL DEFAULT '',"
                + "tup_outcome VARCHAR(32) NOT NULL DEFAULT '',"
                + "tup_starttime DATETIME NOT NULL,"
                + "views BIGINT NOT NULL DEFAULT 0,"
                + "shown BIGINT NOT NULL DEFAULT 0,"
                + "completed BIGINT NOT NULL DEFAULT 0,"
                + "credited BIGINT NOT NULL DEFAULT 0,"
                + "failed BIGINT NOT NULL DEFAULT 0,"
                + "watchedsec BIGINT NOT NULL DEFAULT 0,"
                + "chargedamount DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "chargedunits DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (id, tup_starttime),"           // partition column must be in every unique key
                + "KEY ix_starttime (tup_starttime),"
                + "KEY ix_tenant_partner (tup_tenant, tup_partnerid, tup_starttime)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                + DdlPartitions.dailyRangeFromConfig(AdSummary.BUCKET_COLUMN);
    }
}
