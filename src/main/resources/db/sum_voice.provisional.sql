-- =====================================================================================
-- sum_voice_* — PROVISIONAL summary schema for the cdr-voice bean.
--
-- This is the architect's placeholder so the pipeline + the opt-in MySQL integration test
-- have tables to write. RECONCILE with billing-core's real sum_voice_day_03 / sum_voice_hr_03
-- DDL once dotnet pins it (handoff posted on the summary-service channel). Column names here
-- MUST match CdrVoiceSummaryBean's dimension/counter column names.
--
-- Engine contract this schema must satisfy:
--   * id BIGINT AUTO_INCREMENT PRIMARY KEY — the engine omits id on INSERT and UPDATEs by id.
--   * an index on tup_starttime — the load query filters WHERE tup_starttime IN (...).
--   * string key columns NOT NULL DEFAULT '' — the engine renders absent strings as '' and
--     matches the reloaded row on that.
--   * the UNIQUE KEY on the full tuple is an OPTIONAL safety net against duplicate windows;
--     the engine itself de-dups in memory and updates by id. Drop it if the key length is a
--     problem in a given deployment.
--
-- Production note: align partitioning (e.g. RANGE on tup_starttime by month, all partitions
-- created up front per house rule) with billing-core's real tables at cutover.
-- =====================================================================================

CREATE DATABASE IF NOT EXISTS tcbl_summary CHARACTER SET utf8mb4;
USE tcbl_summary;

-- one template, instantiated for the day table and the hour table
-- (day bucket = tup_starttime at 00:00:00; hour bucket = tup_starttime at HH:00:00)

CREATE TABLE IF NOT EXISTS sum_voice_day_03 (
    id                          BIGINT       NOT NULL AUTO_INCREMENT,
    tup_switchid                INT          NOT NULL DEFAULT 0,
    tup_inpartnerid             INT          NOT NULL DEFAULT 0,
    tup_outpartnerid            INT          NOT NULL DEFAULT 0,
    tup_incomingroute           VARCHAR(64)  NOT NULL DEFAULT '',
    tup_outgoingroute           VARCHAR(64)  NOT NULL DEFAULT '',
    tup_customerrate            DECIMAL(18,6) NOT NULL DEFAULT 0,
    tup_supplierrate            DECIMAL(18,6) NOT NULL DEFAULT 0,
    tup_incomingip              VARCHAR(64)  NOT NULL DEFAULT '',
    tup_outgoingip              VARCHAR(64)  NOT NULL DEFAULT '',
    tup_countryorareacode       VARCHAR(32)  NOT NULL DEFAULT '',
    tup_matchedprefixcustomer   VARCHAR(32)  NOT NULL DEFAULT '',
    tup_matchedprefixsupplier   VARCHAR(32)  NOT NULL DEFAULT '',
    tup_sourceId                VARCHAR(32)  NOT NULL DEFAULT '',
    tup_destinationId           VARCHAR(32)  NOT NULL DEFAULT '',
    tup_customercurrency        VARCHAR(16)  NOT NULL DEFAULT '',
    tup_suppliercurrency        VARCHAR(16)  NOT NULL DEFAULT '',
    tup_starttime               DATETIME     NOT NULL,
    totalcalls                  BIGINT       NOT NULL DEFAULT 0,
    connectedcalls              BIGINT       NOT NULL DEFAULT 0,
    successfulcalls             BIGINT       NOT NULL DEFAULT 0,
    actualduration              DECIMAL(18,6) NOT NULL DEFAULT 0,
    roundedduration             DECIMAL(18,6) NOT NULL DEFAULT 0,
    duration1                   DECIMAL(18,6) NOT NULL DEFAULT 0,
    customercost                DECIMAL(18,6) NOT NULL DEFAULT 0,
    suppliercost                DECIMAL(18,6) NOT NULL DEFAULT 0,
    tax1                        DECIMAL(18,6) NOT NULL DEFAULT 0,
    tax2                        DECIMAL(18,6) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_starttime (tup_starttime),
    UNIQUE KEY uq_tuple (
        tup_starttime, tup_switchid, tup_inpartnerid, tup_outpartnerid,
        tup_incomingroute, tup_outgoingroute, tup_customerrate, tup_supplierrate,
        tup_incomingip, tup_outgoingip, tup_countryorareacode,
        tup_matchedprefixcustomer, tup_matchedprefixsupplier,
        tup_sourceId, tup_destinationId, tup_customercurrency, tup_suppliercurrency)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sum_voice_hr_03 LIKE sum_voice_day_03;
