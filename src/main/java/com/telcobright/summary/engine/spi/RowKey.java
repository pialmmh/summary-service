package com.telcobright.summary.engine.spi;

import java.util.List;

/**
 * The value-equality tuple key of a summary row (its key-column values, in canonical order). Two rows merge
 * iff their RowKeys are equal — this is what makes the engine group a batch's events into windows. Built
 * from already-normalized values (null strings are "", absent numerics are 0) so a loaded row and a freshly
 * built row compare equal.
 */
public record RowKey(List<Object> values) {
}
