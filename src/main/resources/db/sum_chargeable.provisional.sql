-- =====================================================================================
-- sum_chargeable_* — the CHARGEABLE summary schema (net-new, work order 2026-07-02 §4; architect-specified,
-- no legacy counterpart). One table per window, FIXED names (no suffix sets): sum_chargeable_day / _hr.
--
-- Key = (servicegroup, servicefamily, assigneddirection, productid, billeduom, prefix) + the window bucket
-- tup_transactiontime — customer (revenue) and supplier (cost) legs stay SEPARATE rows.
-- Amounts are DECIMAL(20,8): billing money math rounds HALF_EVEN at 8dp (dotnet A4).
--
-- Engine contract: id AUTO_INCREMENT (in the PK with the partition column); UPDATE/DELETE target
-- id AND tup_transactiontime (partition pruning); the load filters WHERE tup_transactiontime IN (...).
--
-- Partitioning: RANGE COLUMNS(tup_transactiontime), DAILY, full set created up front per house rule (dotnet
-- A5). The beans SELF-PROVISION this table at activation (CREATE TABLE IF NOT EXISTS with the partition set);
-- this file is the canonical reference DDL. pMAX shown here — the runtime create carries the real daily set.
-- =====================================================================================

CREATE TABLE IF NOT EXISTS sum_chargeable_day (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    tup_servicegroup     INT           NOT NULL DEFAULT 0,
    tup_servicefamily    INT           NOT NULL DEFAULT 0,
    tup_assigneddirection TINYINT      NOT NULL DEFAULT 0,
    tup_productid        BIGINT        NOT NULL DEFAULT 0,
    tup_billeduom        VARCHAR(32)   NOT NULL DEFAULT '',
    tup_prefix           VARCHAR(32)   NOT NULL DEFAULT '',
    tup_transactiontime  DATETIME      NOT NULL,
    totalcount           BIGINT        NOT NULL DEFAULT 0,
    BilledAmount         DECIMAL(20,8) NOT NULL DEFAULT 0,
    Quantity             DECIMAL(20,8) NOT NULL DEFAULT 0,
    TaxAmount1           DECIMAL(20,8) NOT NULL DEFAULT 0,
    TaxAmount2           DECIMAL(20,8) NOT NULL DEFAULT 0,
    TaxAmount3           DECIMAL(20,8) NOT NULL DEFAULT 0,
    VatAmount1           DECIMAL(20,8) NOT NULL DEFAULT 0,
    VatAmount2           DECIMAL(20,8) NOT NULL DEFAULT 0,
    VatAmount3           DECIMAL(20,8) NOT NULL DEFAULT 0,
    OtherAmount1         DECIMAL(20,8) NOT NULL DEFAULT 0,
    OtherAmount2         DECIMAL(20,8) NOT NULL DEFAULT 0,
    OtherAmount3         DECIMAL(20,8) NOT NULL DEFAULT 0,
    OtherDecAmount1      DECIMAL(20,8) NOT NULL DEFAULT 0,
    OtherDecAmount2      DECIMAL(20,8) NOT NULL DEFAULT 0,
    OtherDecAmount3      DECIMAL(20,8) NOT NULL DEFAULT 0,
    PRIMARY KEY (id, tup_transactiontime),
    KEY ix_transactiontime (tup_transactiontime)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
PARTITION BY RANGE COLUMNS(tup_transactiontime) (
    PARTITION pMAX VALUES LESS THAN (MAXVALUE)   -- reference only; runtime self-provision carries the daily set
);

CREATE TABLE IF NOT EXISTS sum_chargeable_hr LIKE sum_chargeable_day;
