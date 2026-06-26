package com.telcobright.summary.bean.spi;

import java.util.List;

/**
 * The value-equality tuple key of a summary entity — its dimension values plus the window bucket, each
 * rendered to a CANONICAL string token (ints as-is, decimals with trailing zeros stripped, datetime as
 * {@code yyyy-MM-dd HH:mm:ss}). Two entities merge iff their keys are equal. Building the key from canonical
 * strings makes a freshly built entity and one reloaded from MySQL compare equal regardless of JDBC's
 * Integer-vs-Long or decimal scale (1.5 vs 1.500000).
 *
 * <p>This is the {@code TKey} of the legacy {@code ISummary<TEntity,TKey>}, fixed to a token tuple — every
 * summary keys on a tuple, so a single concrete key type keeps the engine generic over just the entity.
 */
public record SummaryKey(List<String> tokens) {

    public static SummaryKey of(String... tokens) {
        return new SummaryKey(List.of(tokens));
    }
}
