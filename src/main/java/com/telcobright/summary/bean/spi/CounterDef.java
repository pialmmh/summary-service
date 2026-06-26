package com.telcobright.summary.bean.spi;

import java.util.function.Function;

/**
 * One additive counter of a summary row (totalcalls / duration / cost / tax / …). The {@code delta} is this
 * single event's contribution to the counter; the engine sums deltas across the batch into the window, and
 * negates them for the decrement/correction path. A null delta counts as zero.
 *
 * @param column  the summary table column (e.g. "totalcalls", "customercost")
 * @param type    SQL rendering family (INT/LONG render whole, DECIMAL renders fractional)
 * @param delta   event -> this event's contribution to the counter
 * @param <E>     the event type
 */
public record CounterDef<E>(String column, ColumnType type, Function<E, Number> delta) {
}
