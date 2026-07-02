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
     * The full {@code CREATE TABLE IF NOT EXISTS} DDL for {@link #table()} — the bean SELF-PROVISIONS its
     * table at activation (user directive 2026-07-02). Partitioned tables carry their FULL partition set
     * inside this CREATE (house rule: never create bare then ALTER). Null = no self-provisioning (the table
     * is managed elsewhere). The service's MySQL user needs CREATE (and ALTER for future partition ranges)
     * on the tenant schema — the only remaining ops item.
     */
    default String tableDdl() {
        return null;
    }

    /** The INSERT column list (CSV, in value order, WITHOUT id — AUTO_INCREMENT assigns it). */
    String insertColumnsCsv();

    /** The datetime column holding the window bucket (e.g. {@code tup_starttime}); the load query filters on it. */
    String bucketColumn();

    /** The configured time window (5min / hourly / daily / weekly / …). */
    WindowSize window();

    /**
     * Build the summary entities for ONE decompressed outbox row — its batch of records (the JSON array of
     * {@code {Cdr, Customer}}). Records not for this bean (e.g. a different service group) are skipped; each
     * kept record becomes one bucketed entity.
     */
    List<T> buildBatch(byte[] decompressedRowJson);

    /** The window bucket of an entity ({@code tup_starttime}); the distinct set is what the load query fetches. */
    LocalDateTime bucketOf(T entity);

    /** Map a loaded DB row (id + the insert columns) into an entity carrying its id. */
    T mapRow(ResultSet row) throws SQLException;
}
