package com.telcobright.summary.outbox.internal;

import com.telcobright.summary.bean.spi.SqlDialect;
import com.telcobright.summary.bean.spi.SummaryTableSpec;
import com.telcobright.summary.bean.spi.TableDdl;

import java.util.ArrayList;
import java.util.List;

/**
 * The service-level infra tables the summary side ensures in each tenant schema it serves (user directive
 * 2026-07-02, same spirit as bean table self-provisioning): its OWN {@code summary_offset} (the per-bean
 * bookmark) and {@code summary_affected_dlq} (a poison row's copy).
 *
 * <p>The outbox itself, {@code summary_affected}, is billing-core's. On MySQL a local/dev convenience copy is
 * ensured too, as before ({@code IF NOT EXISTS}: a no-op wherever billing already ran). On PostgreSQL it is NEVER
 * made here: whoever creates a table there OWNS it, and a tier schema where summary-service came first would hold
 * an outbox billing-core cannot write (ad-is-a-call §3: billing-core creates and owns it; agreed on both sides,
 * SS-0001 F6). A schema with no outbox yet is one billing-core has not served yet. Mirrors
 * {@code src/main/resources/db/summary_outbox.sql}.
 */
public final class OutboxInfraDdl {

    public static final String OUTBOX_TABLE = "summary_affected";
    public static final String OFFSET_TABLE = "summary_offset";
    public static final String DEAD_LETTER_TABLE = "summary_affected_dlq";

    /** MySQL only: the dev copy of billing's outbox. */
    private static final String MYSQL_DEV_OUTBOX = "CREATE TABLE IF NOT EXISTS summary_affected ("
            + "id BIGINT NOT NULL AUTO_INCREMENT,"
            + "entity_type VARCHAR(32) NOT NULL,"
            + "op ENUM('add','subtract') NOT NULL DEFAULT 'add',"
            + "data LONGTEXT NOT NULL,"
            + "PRIMARY KEY (id),"
            + "KEY ix_entity (entity_type, id)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private OutboxInfraDdl() {
    }

    /** The MySQL statements (the form before PostgreSQL existed; kept for embedders). */
    public static List<String> createStatements() {
        return createStatements(SqlDialect.MYSQL);
    }

    /** The statements that make the infra tables on {@code dialect} when they are absent, in order. */
    public static List<String> createStatements(SqlDialect dialect) {
        List<String> statements = new ArrayList<>();
        if (dialect == SqlDialect.MYSQL) {
            statements.add(MYSQL_DEV_OUTBOX);
        }
        statements.addAll(TableDdl.createIfAbsent(offsetTable(), dialect));
        statements.addAll(TableDdl.createIfAbsent(deadLetterTable(), dialect));
        return statements;
    }

    /** The per-bean bookmark: the id of the last outbox row a bean has summed. */
    static SummaryTableSpec offsetTable() {
        return SummaryTableSpec.table(OFFSET_TABLE)
                .varcharRequired("entity_type", 32)
                .varcharRequired("bean_name", 64)
                .bigint("last_offset")
                .primaryKey("entity_type", "bean_name")
                .build();
    }

    /** A poison outbox row, kept per bean with the reason, for the repair. */
    static SummaryTableSpec deadLetterTable() {
        return SummaryTableSpec.table(DEAD_LETTER_TABLE)
                .identity("id")
                .varcharRequired("entity_type", 32)
                .varcharRequired("bean_name", 64)
                .bigintRequired("outbox_id")
                .longText("data")
                .varcharRequired("error", 512)
                .createdAt("created_at")
                .primaryKey("id")
                .index("ix_bean", "entity_type", "bean_name", "outbox_id")
                .build();
    }
}
