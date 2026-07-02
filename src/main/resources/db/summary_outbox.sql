-- =====================================================================================
-- summary outbox + per-bean bookmark — PINNED contract (mirror of billing-core
-- src/Billing.Data/Sql/summary_outbox.sql). Billing CREATES + WRITES summary_affected inside
-- the cdr-batch transaction; the summary-service READS it, advances its per-bean offset, and
-- reaps consumed rows. This copy lets summary stand up the tables for local/dev + its IT.
--
--   * data = base64( gzip( UTF-8 JSON array of {Cdr, Chargeables:[ALL legs]} ) ) — one row per ~1000-cdr
--     batch (blob v2; the consumer permanently tolerates the v1 {Cdr, Customer} shape).
--   * op = 'add' for a normal batch; a billing correction writes 'subtract' (OLD values) + 'add' (NEW
--     values) in ONE billing tx — consecutive ids, applied by the consumer strictly in id order.
--   * billing serializes batches per schema via GET_LOCK held across the commit, so ids are COMMIT-ordered.
--   * column is last_offset (OFFSET is a MySQL reserved word).
--   * Kafka carries only a PING (topic cdr_summary_ping); this table is the durable hand-off.
-- =====================================================================================

CREATE TABLE IF NOT EXISTS summary_affected (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    entity_type VARCHAR(32)  NOT NULL,                  -- 'cdr' (future: other event entities)
    op          ENUM('add','subtract') NOT NULL DEFAULT 'add',
    data        LONGTEXT     NOT NULL,                  -- base64(gzip(JSON array of {Cdr, Chargeables[]}))
    PRIMARY KEY (id),
    KEY ix_entity (entity_type, id)                     -- summary scans WHERE entity_type=? AND id>last_offset
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS summary_offset (
    entity_type VARCHAR(32)  NOT NULL,                  -- matches summary_affected.entity_type
    bean_name   VARCHAR(64)  NOT NULL,                  -- e.g. dailyCallSummary / hourlyCallSummary
    last_offset BIGINT       NOT NULL DEFAULT 0,        -- last summary_affected.id this bean finished
    PRIMARY KEY (entity_type, bean_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- summary-OWNED (billing never touches it): a poison outbox row a bean could not decode/build after
-- quarantine-after consecutive attempts is copied here and skipped (offset advances past it in the same tx),
-- so one bad blob cannot wedge the bean + block the reaper forever. Repair = fix + replay via the correction path.
CREATE TABLE IF NOT EXISTS summary_affected_dlq (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    entity_type VARCHAR(32)  NOT NULL,
    bean_name   VARCHAR(64)  NOT NULL,                  -- quarantine is PER BEAN (another bean may have consumed it fine)
    outbox_id   BIGINT       NOT NULL,                  -- the skipped summary_affected.id
    data        LONGTEXT     NOT NULL,                  -- the poison blob, preserved verbatim
    error       VARCHAR(512) NOT NULL,                  -- the decode/build failure summary
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY ix_bean (entity_type, bean_name, outbox_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
