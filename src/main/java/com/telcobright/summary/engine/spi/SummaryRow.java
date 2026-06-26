package com.telcobright.summary.engine.spi;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One summary row: a key tuple (dimensions + window bucket) and its additive counters.
 *
 * <p>Identity: {@code id} is null for a row built from events this batch (an INSERT — MySQL AUTO_INCREMENT
 * assigns the id), and set for a row loaded from the DB (an UPDATE/DELETE targets it by that id). Because the
 * engine only ever UPDATEs rows it loaded, every update has an id — there is no id allocator to coordinate.
 *
 * <p>Values are normalized at build time (null strings -> "", absent numerics -> 0) so a freshly built row
 * and a reloaded row produce the SAME {@link RowKey}.
 */
public final class SummaryRow {

    private Long id;
    private final LinkedHashMap<String, Object> key;
    private final LinkedHashMap<String, BigDecimal> counters;

    public SummaryRow(Long id, LinkedHashMap<String, Object> key, LinkedHashMap<String, BigDecimal> counters) {
        this.id = id;
        this.key = key;
        this.counters = counters;
    }

    public Long id() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public boolean isPersisted() {
        return id != null;
    }

    public Object keyValue(String column) {
        return key.get(column);
    }

    public BigDecimal counter(String column) {
        return counters.get(column);
    }

    public Map<String, Object> keyView() {
        return Collections.unmodifiableMap(key);
    }

    public Map<String, BigDecimal> counterView() {
        return Collections.unmodifiableMap(counters);
    }

    /** ADD: counters += delta.counters (same schema, so every column lines up). */
    public void mergeAdd(SummaryRow delta) {
        delta.counters.forEach((c, v) -> counters.merge(c, v, BigDecimal::add));
    }

    /** Negate every counter — used to turn an ADD delta into a SUBTRACT delta before merging. */
    public void negateCounters() {
        counters.replaceAll((c, v) -> v.negate());
    }

    /** OVERWRITE: replace counters with the recomputed source's values (the correction path). */
    public void overwriteCounters(SummaryRow source) {
        counters.replaceAll((c, v) -> source.counters.getOrDefault(c, BigDecimal.ZERO));
    }

    /**
     * A fresh copy with id=null, so inserting it into the cache and later merging more events onto the cached
     * copy never mutates the original delta object (the legacy CloneWithFakeId guard).
     */
    public SummaryRow copyAsNew() {
        return new SummaryRow(null, new LinkedHashMap<>(key), new LinkedHashMap<>(counters));
    }
}
