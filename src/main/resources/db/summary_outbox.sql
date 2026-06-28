-- =====================================================================================
-- summary outbox + per-bean bookmark — PINNED contract (mirror of billing-core
-- src/Billing.Data/Sql/summary_outbox.sql). Billing CREATES + WRITES summary_affected inside
-- the cdr-batch transaction; the summary-service READS it, advances its per-bean offset, and
-- reaps consumed rows. This copy lets summary stand up the tables for local/dev + its IT.
--
--   * data = base64( gzip( UTF-8 JSON array of {Cdr, Customer} ) ) — one row per ~1000-cdr batch.
--   * column is last_offset (OFFSET is a MySQL reserved word).
--   * Kafka carries only a PING (topic cdr_summary_ping); this table is the durable hand-off.
-- =====================================================================================

CREATE TABLE IF NOT EXISTS summary_affected (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    entity_type VARCHAR(32)  NOT NULL,                  -- 'cdr' (future: other event entities)
    data        LONGTEXT     NOT NULL,                  -- base64(gzip(JSON array of {Cdr, Customer}))
    PRIMARY KEY (id),
    KEY ix_entity (entity_type, id)                     -- summary scans WHERE entity_type=? AND id>last_offset
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS summary_offset (
    entity_type VARCHAR(32)  NOT NULL,                  -- matches summary_affected.entity_type
    bean_name   VARCHAR(64)  NOT NULL,                  -- e.g. dailyCallSummary / hourlyCallSummary
    last_offset BIGINT       NOT NULL DEFAULT 0,        -- last summary_affected.id this bean finished
    PRIMARY KEY (entity_type, bean_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
