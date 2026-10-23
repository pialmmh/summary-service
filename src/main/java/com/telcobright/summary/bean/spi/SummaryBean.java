package com.telcobright.summary.bean.spi;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A summary bean = ONE purpose: build typed summary entities {@code T} from one entity's outbox stream and
 * roll them into one MySQL table over one time window. The daily call summary and the hourly call summary are
 * two beans over the SAME entity ({@code CallSummary}); a future call-quality summary is its own bean.
 *
 * <p>Each window is its own bean CLASS (e.g. {@code HourlySummary}, {@code DailySummary}), browsable under
 * {@code summarybeans/<category>/}; its {@code table} + filter + context come from {@code summary.beans.<name>}
 * in YAML, and {@code summary.enabledSummary} selects which to run. Each runs as its own parallel worker that
 * drains the outbox.
 *
 * @param <T> the summary entity this bean maintains
 */
public interface SummaryBean<T extends SummaryEntity<T>> {

    /** Unique bean id (also its worker id, its {@code summary.beans.<name>} config, and its offset bookmark). */
    String name();

    /** The outbox {@code entity_type} this bean consumes (e.g. {@code "cdr"}). */
    String entityType();

    /** The shared read-only context this bean needs (e.g. {@code "mediationContext"}), or null. */
    default String contextName() {
        return null;
    }

    /**
     * How this bean folds summaries into its windows — the per-bean setting. ALL outbox polls are
     * INCREMENTAL today (the default); {@code REPLACE} (drop the window, recreate from all its inputs) is a
     * prototype the engine does not implement yet.
     */
    default SummaryMode mode() {
        return SummaryMode.INCREMENTAL;
    }

    /** Target MySQL table for this bean's window. */
    String table();

    /**
     * The table this bean rolls into, described ONCE for every engine — the bean SELF-PROVISIONS it at activation
     * (user directive 2026-07-02): the store's edge renders the description for the engine it runs on and creates
     * the table when it is absent. Null = no self-provisioning (the table is managed elsewhere). The service's
     * database user needs CREATE on the tenant schema — the only remaining ops item.
     */
    default SummaryTableSpec tableSpec() {
        return null;
    }

    /**
     * The statements that make {@link #table()} on {@code dialect} when it is absent, in order; empty = no
     * self-provisioning. MySQL: one {@code CREATE TABLE IF NOT EXISTS} carrying the FULL partition set (house
     * rule: never create bare then ALTER). PostgreSQL: the plain table and its indexes.
     */
    default List<String> tableDdl(SqlDialect dialect) {
        SummaryTableSpec spec = tableSpec();
        return spec == null ? List.of() : TableDdl.createIfAbsent(spec, dialect);
    }

    /** The MySQL form as ONE statement (what this method always returned); null = no self-provisioning. */
    default String tableDdl() {
        SummaryTableSpec spec = tableSpec();
        return spec == null ? null : TableDdl.mysql(spec);
    }

    /** The INSERT column list (CSV, in value order, WITHOUT id — AUTO_INCREMENT assigns it). */
    String insertColumnsCsv();

    /** The datetime column holding the window bucket (e.g. {@code tup_starttime}); the load query filters on it. */
    String bucketColumn();

    /** The configured time window (5min / hourly / daily / weekly / …). */
    WindowSize window();

    /**
     * Build the summary entities for ONE decompressed outbox row — its batch of records (the JSON array of
     * {@code {Cdr, Chargeables}}) — read from the outbox of the tenant schema {@code tier} (the schema's own
     * name: {@code btcl}, {@code res_44}). Records not for this bean (e.g. a different service group) are
     * skipped; each kept record becomes one bucketed entity. A bean whose rows carry the tier (the ad summary's
     * {@code tup_tenant}) takes it from here — the blob does not name it.
     */
    List<T> buildBatch(byte[] decompressedRowJson, String tier);

    /** The same for a bean that does not need the tier (tests and embedders; the drain always names it). */
    default List<T> buildBatch(byte[] decompressedRowJson) {
        return buildBatch(decompressedRowJson, null);
    }

    /** The window bucket of an entity ({@code tup_starttime}); the distinct set is what the load query fetches. */
    LocalDateTime bucketOf(T entity);

    /** Map a loaded DB row (id + the insert columns) into an entity carrying its id. */
    T mapRow(ResultSet row) throws SQLException;
}
