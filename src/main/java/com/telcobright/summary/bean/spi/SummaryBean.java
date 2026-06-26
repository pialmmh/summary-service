package com.telcobright.summary.bean.spi;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * A summary bean = ONE counter/summary implementation, declared data-first. The CDR per-day/per-hour
 * summary is one bean. A bean states the event it consumes, the time windows it keeps, the dimensions it
 * groups by, and the counters it sums — the engine does the load-merge-write generically from these.
 *
 * <p>Beans are pluggable and YAML-activated; the registry can hot-start a worker for a new bean without a
 * restart. Implementations are pure declaration + extractors — they hold NO database, transaction, or Kafka
 * state (those are the engine's job), which keeps them trivially unit-testable.
 *
 * @param <E> the event type this bean consumes
 */
public interface SummaryBean<E> {

    /** Unique bean id (also the worker/consumer id). */
    String name();

    /** The Kafka topic carrying the normal (increment) event stream. */
    String topic();

    /**
     * The Kafka topic carrying correction events; a correction recomputes a window from source-of-truth and
     * OVERWRITES it. Null means this bean has no correction path.
     */
    default String correctionTopic() {
        return null;
    }

    /** Max events polled + merged + written per DB transaction. */
    default int batchSize() {
        return 1000;
    }

    /** The wall-clock zone the windows are bucketed in (e.g. Asia/Dhaka). */
    ZoneId zone();

    /** The event timestamp used to choose the window bucket. */
    Instant eventTime(E event);

    /** The windows this bean maintains (e.g. day + hour). */
    List<WindowDef> windows();

    /** The group-by dimensions (key columns) of a summary row. */
    List<DimensionDef<E>> dimensions();

    /** The additive counters of a summary row, with each event's delta. */
    List<CounterDef<E>> counters();

    /** Decode a Kafka record value into an event of this bean's type. */
    E deserialize(byte[] payload);
}
