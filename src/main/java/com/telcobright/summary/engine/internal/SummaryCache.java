package com.telcobright.summary.engine.internal;

import com.telcobright.summary.bean.spi.SummaryEntity;
import com.telcobright.summary.bean.spi.SummaryKey;
import com.telcobright.summary.engine.spi.MergeMode;
import com.telcobright.summary.engine.spi.SummaryStore;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-window change-tracking cache (ported AbstractCache + SummaryCache), now typed over the summary
 * entity {@code T}. Existing rows are seeded with {@link #populateExisting}; events fold in with {@link #merge}
 * using the entity's own merge/multiply/clone; {@link #flush} writes the net change as segmented
 * INSERT/UPDATE/DELETE through the {@link SummaryStore}. NO transaction control here.
 *
 * <p>Tracking rule (kept from legacy): a row INSERTed this batch and merged again stays an insert with the
 * accumulated value; only a LOADED row becomes an update — so every UPDATE/DELETE targets a row with a DB id.
 */
public final class SummaryCache<T extends SummaryEntity<T>> {

    private final String table;
    private final String insertHeader;
    private final Map<SummaryKey, T> cache = new LinkedHashMap<>();
    private final Map<SummaryKey, T> inserted = new LinkedHashMap<>();
    private final Map<SummaryKey, T> updated = new LinkedHashMap<>();
    private final Map<SummaryKey, T> deleted = new LinkedHashMap<>();

    public SummaryCache(String table, String insertColumnsCsv) {
        this.table = table;
        this.insertHeader = "insert into " + table + " (" + insertColumnsCsv + ") values ";
    }

    /** Seed with an already-persisted row (it carries its DB id). A later merge marks it Updated. */
    public void populateExisting(T existing) {
        if (cache.putIfAbsent(existing.tupleKey(), existing) != null) {
            throw new IllegalStateException("duplicate existing row while populating cache for " + table);
        }
    }

    /** Fold one delta entity into its window per {@link MergeMode}. */
    public void merge(T delta, MergeMode mode) {
        SummaryKey key = delta.tupleKey();
        T existing = cache.get(key);
        if (existing == null) {
            if (mode == MergeMode.SUBTRACT) {
                throw new IllegalStateException("cannot SUBTRACT a window that was not loaded: " + table);
            }
            T fresh = delta.cloneWithFakeId();   // id stays null -> AUTO_INCREMENT assigns it on INSERT
            cache.put(key, fresh);
            inserted.put(key, fresh);
            return;
        }
        switch (mode) {
            case ADD -> existing.merge(delta);
            case SUBTRACT -> {
                delta.multiply(-1);
                existing.merge(delta);
            }
            case OVERWRITE -> {
                T replacement = delta.cloneWithFakeId();
                replacement.setId(existing.id());   // keep the loaded id; replace the counters wholesale
                cache.put(key, replacement);
            }
        }
        if (!inserted.containsKey(key)) {
            updated.put(key, cache.get(key));
        }
    }

    /** Write deletes, then inserts, then updates — each segmented (legacy WriteAllChanges order). */
    public void flush(SummaryStore store, int segmentSize) {
        writeDeletes(store, segmentSize);
        writeInserts(store, segmentSize);
        writeUpdates(store, segmentSize);
    }

    private void writeInserts(SummaryStore store, int segmentSize) {
        if (inserted.isEmpty()) {
            return;
        }
        List<String> tuples = inserted.values().stream().map(SummaryEntity::insertValues).toList();
        SegmentedSqlWriter.writeInsertsInSegments(store, insertHeader, tuples, segmentSize);
        inserted.clear();
    }

    private void writeUpdates(SummaryStore store, int segmentSize) {
        if (updated.isEmpty()) {
            return;
        }
        List<String> statements = updated.values().stream()
                .map(e -> "update " + table + " set " + e.updateAssignments() + " where id=" + e.id()).toList();
        SegmentedSqlWriter.writeStatementsInSegments(store, statements, segmentSize);
        updated.clear();
    }

    private void writeDeletes(SummaryStore store, int segmentSize) {
        if (deleted.isEmpty()) {
            return;
        }
        List<String> statements = deleted.values().stream()
                .map(e -> "delete from " + table + " where id=" + e.id()).toList();
        SegmentedSqlWriter.writeStatementsInSegments(store, statements, segmentSize);
        deleted.clear();
    }

    // ---- inspection (tests + batch result) ----

    public Collection<T> rows() {
        return cache.values();
    }

    public int insertedCount() {
        return inserted.size();
    }

    public int updatedCount() {
        return updated.size();
    }
}
