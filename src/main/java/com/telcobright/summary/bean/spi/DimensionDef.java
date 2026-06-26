package com.telcobright.summary.bean.spi;

import java.util.function.Function;

/**
 * One group-by dimension of a summary row — part of its key (switch / partner / route / prefix / …).
 * The {@code extractor} pulls the dimension value out of an event; together with the window's bucket
 * column the dimensions form the tuple key the engine merges on.
 *
 * @param column     the summary table column (e.g. "tup_switchid")
 * @param type       SQL rendering family
 * @param extractor  event -> dimension value (may return null; rendered per type)
 * @param <E>        the event type
 */
public record DimensionDef<E>(String column, ColumnType type, Function<E, Object> extractor) {
}
