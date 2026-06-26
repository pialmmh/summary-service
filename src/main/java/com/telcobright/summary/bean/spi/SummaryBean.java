package com.telcobright.summary.bean.spi;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

/**
 * A summary bean = ONE purpose: build a typed summary entity {@code T} from an event stream and roll it into
 * one MySQL table over one configured time window. The CDR daily summary and the CDR hourly summary are two
 * beans over the SAME entity ({@code CdrSummary}); a future call-quality summary is a bean over its own entity.
 *
 * <p>Beans are config-driven instances (window + table + topic + filter from YAML), produced per
 * {@code enabledSummary} entry; the registry hot-starts one worker per enabled bean with no restart.
 *
 * @param <T> the summary entity this bean maintains
 */
public interface SummaryBean<T extends SummaryEntity<T>> {

    /** Unique bean id (also its worker id + its {@code summary.beans.<name>} config section). */
    String name();

    /** The Kafka topic carrying the normal (increment) event stream. */
    String topic();

    /** The correction topic (recompute + overwrite a window); null if this bean has no correction path. */
    default String correctionTopic() {
        return null;
    }

    /** Max events polled + merged + written per DB transaction. */
    default int batchSize() {
        return 1000;
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
     * Decode one Kafka record value into a bucketed summary entity, or return {@code null} if this event is not
     * for this bean (e.g. a different service group on a shared topic) so the worker skips it.
     */
    T build(byte[] payload);

    /** The window bucket of an entity ({@code tup_starttime}); the distinct set is what the load query fetches. */
    LocalDateTime bucketOf(T entity);

    /** Map a loaded DB row (id + the insert columns) into an entity carrying its id. */
    T mapRow(ResultSet row) throws SQLException;
}
