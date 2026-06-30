-- =====================================================================================
-- sum_voice_* — PROVISIONAL summary schema for the cdr entity (all 47 data columns).
--
-- Faithful to billing-core's AbstractCdrSummary column set + order (see CallSummary.INSERT_COLUMNS).
-- Table name is DERIVED: sum_voice_<window>_<table-suffix>; the suffix (config per bean) selects one of the
-- PRE-PROVISIONED sets — sum_voice_{day,hr}_{1,2,3} — created up front WITH all partitions (1000-partition
-- tables are far too slow to create on the fly). The bean only POINTS at an existing table; it never creates it.
-- Set the suffix to match your real tables (e.g. "3" -> sum_voice_day_3, or "03" to match billing's legacy _03).
--
-- Engine contract this schema must satisfy:
--   * id BIGINT AUTO_INCREMENT PRIMARY KEY — the engine omits id on INSERT and UPDATEs by id.
--   * an index on tup_starttime — the load query filters WHERE tup_starttime IN (...).
--   * key columns NOT NULL DEFAULT '' / 0 — the engine renders absent strings as '' and matches the
--     reloaded row on that; the UNIQUE KEY over the 20-column GetTupleKey tuple is an optional safety net
--     (the engine de-dups in memory and updates by id). Drop it if the key length is a problem.
--
-- Production note: align partitioning (e.g. RANGE on tup_starttime, all partitions created up front per
-- house rule) with billing-core's real tables at cutover.
-- =====================================================================================

CREATE DATABASE IF NOT EXISTS tcbl_summary CHARACTER SET utf8mb4;
USE tcbl_summary;

CREATE TABLE IF NOT EXISTS sum_voice_day_3 (
    id                          BIGINT        NOT NULL AUTO_INCREMENT,
    tup_switchid                INT           NOT NULL DEFAULT 0,
    tup_inpartnerid             INT           NOT NULL DEFAULT 0,
    tup_outpartnerid            INT           NOT NULL DEFAULT 0,
    tup_incomingroute           VARCHAR(64)   NOT NULL DEFAULT '',
    tup_outgoingroute           VARCHAR(64)   NOT NULL DEFAULT '',
    tup_customerrate            DECIMAL(18,6) NOT NULL DEFAULT 0,
    tup_supplierrate            DECIMAL(18,6) NOT NULL DEFAULT 0,
    tup_incomingip              VARCHAR(64)   NOT NULL DEFAULT '',
    tup_outgoingip              VARCHAR(64)   NOT NULL DEFAULT '',
    tup_countryorareacode       VARCHAR(32)   NOT NULL DEFAULT '',
    tup_matchedprefixcustomer   VARCHAR(32)   NOT NULL DEFAULT '',
    tup_matchedprefixsupplier   VARCHAR(32)   NOT NULL DEFAULT '',
    tup_sourceId                VARCHAR(32)   NOT NULL DEFAULT '',
    tup_destinationId           VARCHAR(32)   NOT NULL DEFAULT '',
    tup_customercurrency        VARCHAR(16)   NOT NULL DEFAULT '',
    tup_suppliercurrency        VARCHAR(16)   NOT NULL DEFAULT '',
    tup_tax1currency            VARCHAR(16)   NOT NULL DEFAULT '',
    tup_tax2currency            VARCHAR(16)   NOT NULL DEFAULT '',
    tup_vatcurrency             VARCHAR(16)   NOT NULL DEFAULT '',
    tup_starttime               DATETIME      NOT NULL,
    totalcalls                  BIGINT        NOT NULL DEFAULT 0,
    connectedcalls              BIGINT        NOT NULL DEFAULT 0,
    connectedcallsCC            BIGINT        NOT NULL DEFAULT 0,
    successfulcalls             BIGINT        NOT NULL DEFAULT 0,
    actualduration              DECIMAL(18,6) NOT NULL DEFAULT 0,
    roundedduration             DECIMAL(18,6) NOT NULL DEFAULT 0,
    duration1                   DECIMAL(18,6) NOT NULL DEFAULT 0,
    duration2                   DECIMAL(18,6) NOT NULL DEFAULT 0,
    duration3                   DECIMAL(18,6) NOT NULL DEFAULT 0,
    PDD                         DECIMAL(18,6) NOT NULL DEFAULT 0,
    customercost                DECIMAL(18,6) NOT NULL DEFAULT 0,
    suppliercost                DECIMAL(18,6) NOT NULL DEFAULT 0,
    tax1                        DECIMAL(18,6) NOT NULL DEFAULT 0,
    tax2                        DECIMAL(18,6) NOT NULL DEFAULT 0,
    vat                         DECIMAL(18,6) NOT NULL DEFAULT 0,
    intAmount1                  INT           NOT NULL DEFAULT 0,
    intAmount2                  INT           NOT NULL DEFAULT 0,
    longAmount1                 BIGINT        NOT NULL DEFAULT 0,
    longAmount2                 BIGINT        NOT NULL DEFAULT 0,
    longDecimalAmount1          DECIMAL(18,6) NOT NULL DEFAULT 0,
    longDecimalAmount2          DECIMAL(18,6) NOT NULL DEFAULT 0,
    intAmount3                  INT           NOT NULL DEFAULT 0,
    longAmount3                 BIGINT        NOT NULL DEFAULT 0,
    longDecimalAmount3          DECIMAL(18,6) NOT NULL DEFAULT 0,
    decimalAmount1              DECIMAL(18,6) NOT NULL DEFAULT 0,
    decimalAmount2              DECIMAL(18,6) NOT NULL DEFAULT 0,
    decimalAmount3              DECIMAL(18,6) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_starttime (tup_starttime),
    UNIQUE KEY uq_tuple (
        tup_switchid, tup_inpartnerid, tup_outpartnerid, tup_incomingroute, tup_outgoingroute,
        tup_customerrate, tup_supplierrate, tup_incomingip, tup_outgoingip, tup_countryorareacode,
        tup_matchedprefixcustomer, tup_matchedprefixsupplier, tup_sourceId, tup_destinationId,
        tup_tax1currency, tup_tax2currency, tup_vatcurrency, tup_starttime,
        tup_customercurrency, tup_suppliercurrency)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- the 3 pre-provisioned sets × {day, hr} — identical shape, different bucket + suffix (created up front, partitioned)
CREATE TABLE IF NOT EXISTS sum_voice_hr_3  LIKE sum_voice_day_3;
CREATE TABLE IF NOT EXISTS sum_voice_day_1 LIKE sum_voice_day_3;
CREATE TABLE IF NOT EXISTS sum_voice_hr_1  LIKE sum_voice_day_3;
CREATE TABLE IF NOT EXISTS sum_voice_day_2 LIKE sum_voice_day_3;
CREATE TABLE IF NOT EXISTS sum_voice_hr_2  LIKE sum_voice_day_3;
