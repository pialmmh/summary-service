package com.telcobright.summary.outbox.internal;

import java.util.List;

/**
 * The service-level infra tables the summary side ensures at startup (user directive 2026-07-02, same spirit
 * as bean table self-provisioning): its OWN {@code summary_offset} + {@code summary_affected_dlq}, and a
 * local/dev convenience copy of billing's {@code summary_affected} (billing creates + writes the real one —
 * IF NOT EXISTS makes this a no-op wherever billing already ran). Mirrors
 * {@code src/main/resources/db/summary_outbox.sql}.
 */
public final class OutboxInfraDdl {

    private OutboxInfraDdl() {
    }

    public static List<String> createStatements() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS summary_affected ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT,"
                        + "entity_type VARCHAR(32) NOT NULL,"
                        + "op ENUM('add','subtract') NOT NULL DEFAULT 'add',"
                        + "data LONGTEXT NOT NULL,"
                        + "PRIMARY KEY (id),"
                        + "KEY ix_entity (entity_type, id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS summary_offset ("
                        + "entity_type VARCHAR(32) NOT NULL,"
                        + "bean_name VARCHAR(64) NOT NULL,"
                        + "last_offset BIGINT NOT NULL DEFAULT 0,"
                        + "PRIMARY KEY (entity_type, bean_name)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS summary_affected_dlq ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT,"
                        + "entity_type VARCHAR(32) NOT NULL,"
                        + "bean_name VARCHAR(64) NOT NULL,"
                        + "outbox_id BIGINT NOT NULL,"
                        + "data LONGTEXT NOT NULL,"
                        + "error VARCHAR(512) NOT NULL,"
                        + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "PRIMARY KEY (id),"
                        + "KEY ix_bean (entity_type, bean_name, outbox_id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
}
