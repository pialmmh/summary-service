package com.telcobright.summary.summarybeans.call.internal;

import com.telcobright.summary.bean.spi.DdlPartitions;
import com.telcobright.summary.summarybeans.call.model.CallSummary;

/**
 * The canonical {@code sum_voice_*} DDL the call beans SELF-PROVISION with at activation (user directive
 * 2026-07-02): {@code CREATE TABLE IF NOT EXISTS} carrying the FULL daily partition set up front. Because the
 * table is partitioned, every unique key must include the partition column — hence PK {@code (id,
 * tup_starttime)} and the {@code uq_tuple} safety net (which already ends on the bucket). Mirrors
 * {@code src/main/resources/db/sum_voice.provisional.sql} (the human-readable reference copy).
 */
final class SumVoiceDdl {

    private SumVoiceDdl() {
    }

    static String createTableIfNotExists(String table) {
        return "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "tup_switchid INT NOT NULL DEFAULT 0,"
                + "tup_inpartnerid INT NOT NULL DEFAULT 0,"
                + "tup_outpartnerid INT NOT NULL DEFAULT 0,"
                + "tup_incomingroute VARCHAR(64) NOT NULL DEFAULT '',"
                + "tup_outgoingroute VARCHAR(64) NOT NULL DEFAULT '',"
                + "tup_customerrate DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "tup_supplierrate DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "tup_incomingip VARCHAR(64) NOT NULL DEFAULT '',"
                + "tup_outgoingip VARCHAR(64) NOT NULL DEFAULT '',"
                + "tup_countryorareacode VARCHAR(32) NOT NULL DEFAULT '',"
                + "tup_matchedprefixcustomer VARCHAR(32) NOT NULL DEFAULT '',"
                + "tup_matchedprefixsupplier VARCHAR(32) NOT NULL DEFAULT '',"
                + "tup_sourceId VARCHAR(32) NOT NULL DEFAULT '',"
                + "tup_destinationId VARCHAR(32) NOT NULL DEFAULT '',"
                + "tup_customercurrency VARCHAR(16) NOT NULL DEFAULT '',"
                + "tup_suppliercurrency VARCHAR(16) NOT NULL DEFAULT '',"
                + "tup_tax1currency VARCHAR(16) NOT NULL DEFAULT '',"
                + "tup_tax2currency VARCHAR(16) NOT NULL DEFAULT '',"
                + "tup_vatcurrency VARCHAR(16) NOT NULL DEFAULT '',"
                + "tup_starttime DATETIME NOT NULL,"
                + "totalcalls BIGINT NOT NULL DEFAULT 0,"
                + "connectedcalls BIGINT NOT NULL DEFAULT 0,"
                + "connectedcallsCC BIGINT NOT NULL DEFAULT 0,"
                + "successfulcalls BIGINT NOT NULL DEFAULT 0,"
                + "actualduration DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "roundedduration DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "duration1 DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "duration2 DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "duration3 DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "PDD DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "customercost DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "suppliercost DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "tax1 DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "tax2 DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "vat DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "intAmount1 INT NOT NULL DEFAULT 0,"
                + "intAmount2 INT NOT NULL DEFAULT 0,"
                + "longAmount1 BIGINT NOT NULL DEFAULT 0,"
                + "longAmount2 BIGINT NOT NULL DEFAULT 0,"
                + "longDecimalAmount1 DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "longDecimalAmount2 DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "intAmount3 INT NOT NULL DEFAULT 0,"
                + "longAmount3 BIGINT NOT NULL DEFAULT 0,"
                + "longDecimalAmount3 DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "decimalAmount1 DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "decimalAmount2 DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "decimalAmount3 DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (id, tup_starttime),"           // partition column must be in every unique key
                + "KEY ix_starttime (tup_starttime)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                + DdlPartitions.dailyRangeFromConfig(CallSummary.BUCKET_COLUMN);
    }
}
