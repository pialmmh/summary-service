package com.telcobright.summary.bean.spi;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A summary bean = ONE purpose: build typed summary entities {@code T} from one entity's outbox stream and
 * roll them into one MySQL table over one configured time window. The CDR daily summary and the CDR hourly
 * summary are two beans over the SAME entity ({@code CdrSummary}); a future call-quality summary is its own bean.
 *
 * <p>Beans are config-driven instances (window + table + filter + context from YAML), produced per
 * {@code enabledSummary} entry; each runs as its own parallel worker that drains the outbox.
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

    /** Target MySQL table for this bean's window. */
    String table();

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
