package com.telcobright.summary.summarybeans.chargeable.internal;

import com.telcobright.summary.bean.spi.SummaryTableSpec;
import com.telcobright.summary.summarybeans.chargeable.model.ChargeableSummary;

/**
 * The {@code sum_chargeable_*} table the chargeable beans SELF-PROVISION with at activation (user directive
 * 2026-07-02), described once for every engine ({@link SummaryTableSpec}; rendered by {@code TableDdl}). Mirrors
 * {@code src/main/resources/db/sum_chargeable.provisional.sql} (the human-readable MySQL reference copy).
 */
final class SumChargeableDdl {

    private SumChargeableDdl() {
    }

    static SummaryTableSpec table(String name) {
        return SummaryTableSpec.table(name)
                .identity("id")
                .integer("tup_servicegroup")
                .integer("tup_servicefamily")
                .tinyInt("tup_assigneddirection")
                .bigint("tup_productid")
                .varchar("tup_billeduom", 32)
                .varchar("tup_prefix", 32)
                .datetime("tup_transactiontime")
                .bigint("totalcount")
                .decimal("BilledAmount", 20, 8)
                .decimal("Quantity", 20, 8)
                .decimal("TaxAmount1", 20, 8)
                .decimal("TaxAmount2", 20, 8)
                .decimal("TaxAmount3", 20, 8)
                .decimal("VatAmount1", 20, 8)
                .decimal("VatAmount2", 20, 8)
                .decimal("VatAmount3", 20, 8)
                .decimal("OtherAmount1", 20, 8)
                .decimal("OtherAmount2", 20, 8)
                .decimal("OtherAmount3", 20, 8)
                .decimal("OtherDecAmount1", 20, 8)
                .decimal("OtherDecAmount2", 20, 8)
                .decimal("OtherDecAmount3", 20, 8)
                .primaryKey("id", ChargeableSummary.BUCKET_COLUMN)   // the partition column is in every unique key
                .index("ix_transactiontime", "tup_transactiontime")
                .partitionedByDayOn(ChargeableSummary.BUCKET_COLUMN)
                .build();
    }
}
