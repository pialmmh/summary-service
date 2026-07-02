package com.telcobright.summary.summarybeans.chargeable.internal;

import com.telcobright.summary.bean.spi.DdlPartitions;
import com.telcobright.summary.summarybeans.chargeable.model.ChargeableSummary;

/**
 * The canonical {@code sum_chargeable_*} DDL the chargeable beans SELF-PROVISION with at activation (user
 * directive 2026-07-02): {@code CREATE TABLE IF NOT EXISTS} carrying the FULL daily partition set up front.
 * Mirrors {@code src/main/resources/db/sum_chargeable.provisional.sql} (the human-readable reference copy).
 */
final class SumChargeableDdl {

    private SumChargeableDdl() {
    }

    static String createTableIfNotExists(String table) {
        return "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "tup_servicegroup INT NOT NULL DEFAULT 0,"
                + "tup_servicefamily INT NOT NULL DEFAULT 0,"
                + "tup_assigneddirection TINYINT NOT NULL DEFAULT 0,"
                + "tup_productid BIGINT NOT NULL DEFAULT 0,"
                + "tup_billeduom VARCHAR(32) NOT NULL DEFAULT '',"
                + "tup_prefix VARCHAR(32) NOT NULL DEFAULT '',"
                + "tup_transactiontime DATETIME NOT NULL,"
                + "totalcount BIGINT NOT NULL DEFAULT 0,"
                + "BilledAmount DECIMAL(20,8) NOT NULL DEFAULT 0,"
                + "Quantity DECIMAL(20,8) NOT NULL DEFAULT 0,"
                + "TaxAmount1 DECIMAL(20,8) NOT NULL DEFAULT 0,"
                + "TaxAmount2 DECIMAL(20,8) NOT NULL DEFAULT 0,"
                + "TaxAmount3 DECIMAL(20,8) NOT NULL DEFAULT 0,"
                + "VatAmount1 DECIMAL(20,8) NOT NULL DEFAULT 0,"
                + "VatAmount2 DECIMAL(20,8) NOT NULL DEFAULT 0,"
                + "VatAmount3 DECIMAL(20,8) NOT NULL DEFAULT 0,"
                + "OtherAmount1 DECIMAL(20,8) NOT NULL DEFAULT 0,"
                + "OtherAmount2 DECIMAL(20,8) NOT NULL DEFAULT 0,"
                + "OtherAmount3 DECIMAL(20,8) NOT NULL DEFAULT 0,"
                + "OtherDecAmount1 DECIMAL(20,8) NOT NULL DEFAULT 0,"
                + "OtherDecAmount2 DECIMAL(20,8) NOT NULL DEFAULT 0,"
                + "OtherDecAmount3 DECIMAL(20,8) NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (id, tup_transactiontime),"     // partition column must be in every unique key
                + "KEY ix_transactiontime (tup_transactiontime)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                + DdlPartitions.dailyRangeFromConfig(ChargeableSummary.BUCKET_COLUMN);
    }
}
