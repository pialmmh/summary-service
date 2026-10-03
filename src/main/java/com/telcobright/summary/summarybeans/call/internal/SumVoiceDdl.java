package com.telcobright.summary.summarybeans.call.internal;

import com.telcobright.summary.bean.spi.SummaryTableSpec;
import com.telcobright.summary.summarybeans.call.model.CallSummary;

/**
 * The {@code sum_voice_*} table the call beans SELF-PROVISION with at activation (user directive 2026-07-02),
 * described once for every engine ({@link SummaryTableSpec}; rendered by {@code TableDdl}). On MySQL it is
 * partitioned by day with the FULL set inside the CREATE, so every unique key carries the partition column —
 * hence PK {@code (id, tup_starttime)}. Mirrors {@code src/main/resources/db/sum_voice.provisional.sql} (the
 * human-readable MySQL reference copy).
 */
final class SumVoiceDdl {

    private SumVoiceDdl() {
    }

    static SummaryTableSpec table(String name) {
        return SummaryTableSpec.table(name)
                .identity("id")
                .integer("tup_switchid")
                .integer("tup_inpartnerid")
                .integer("tup_outpartnerid")
                .varchar("tup_incomingroute", 64)
                .varchar("tup_outgoingroute", 64)
                .decimal("tup_customerrate", 18, 6)
                .decimal("tup_supplierrate", 18, 6)
                .varchar("tup_incomingip", 64)
                .varchar("tup_outgoingip", 64)
                .varchar("tup_countryorareacode", 32)
                .varchar("tup_matchedprefixcustomer", 32)
                .varchar("tup_matchedprefixsupplier", 32)
                .varchar("tup_sourceId", 32)
                .varchar("tup_destinationId", 32)
                .varchar("tup_customercurrency", 16)
                .varchar("tup_suppliercurrency", 16)
                .varchar("tup_tax1currency", 16)
                .varchar("tup_tax2currency", 16)
                .varchar("tup_vatcurrency", 16)
                .datetime("tup_starttime")
                .bigint("totalcalls")
                .bigint("connectedcalls")
                .bigint("connectedcallsCC")
                .bigint("successfulcalls")
                .decimal("actualduration", 18, 6)
                .decimal("roundedduration", 18, 6)
                .decimal("duration1", 18, 6)
                .decimal("duration2", 18, 6)
                .decimal("duration3", 18, 6)
                .decimal("PDD", 18, 6)
                .decimal("customercost", 18, 6)
                .decimal("suppliercost", 18, 6)
                .decimal("tax1", 18, 6)
                .decimal("tax2", 18, 6)
                .decimal("vat", 18, 6)
                .integer("intAmount1")
                .integer("intAmount2")
                .bigint("longAmount1")
                .bigint("longAmount2")
                .decimal("longDecimalAmount1", 18, 6)
                .decimal("longDecimalAmount2", 18, 6)
                .integer("intAmount3")
                .bigint("longAmount3")
                .decimal("longDecimalAmount3", 18, 6)
                .decimal("decimalAmount1", 18, 6)
                .decimal("decimalAmount2", 18, 6)
                .decimal("decimalAmount3", 18, 6)
                .primaryKey("id", CallSummary.BUCKET_COLUMN)      // the partition column is in every unique key
                .index("ix_starttime", "tup_starttime")
                .partitionedByDayOn(CallSummary.BUCKET_COLUMN)
                .build();
    }
}
