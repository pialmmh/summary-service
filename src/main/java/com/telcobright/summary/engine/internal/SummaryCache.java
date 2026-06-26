package com.telcobright.summary.engine.internal;

import com.telcobright.summary.engine.spi.ColumnDef;
import com.telcobright.summary.engine.spi.MergeMode;
import com.telcobright.summary.engine.spi.RowKey;
import com.telcobright.summary.engine.spi.SummaryRow;
import com.telcobright.summary.engine.spi.SummaryStore;
import com.telcobright.summary.engine.spi.WindowSchema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-window change-tracking cache (ported AbstractCache + SummaryCache). Existing rows are seeded with
 * {@link #populateExisting}; events fold in with {@link #merge}; {@link #flush} writes the net changes as
 * segmented INSERT/UPDATE/DELETE through the {@link SummaryStore}. NO transaction control here.
 *
 * <p>Tracking rule (kept from the legacy): a row INSERTed this batch and then merged again stays an insert
 * with the accumulated value; only a row that was LOADED becomes an update. So every UPDATE/DELETE targets a
 * row that has a DB id.
 */
public final class SummaryCache {

    private final WindowSchema schema;
    private final String insertHeader;
    private final Map<RowKey, SummaryRow> cache = new LinkedHashMap<>();
    private final Map<RowKey, SummaryRow> inserted = new LinkedHashMap<>();
    private final Map<RowKey, SummaryRow> updated = new LinkedHashMap<>();
    private final Map<RowKey, SummaryRow> deleted = new LinkedHashMap<>();

    public SummaryCache(WindowSchema schema) {
        this.schema = schema;
        this.insertHeader = SqlRenderer.insertHeader(schema);
    }

    public WindowSchema schema() {
        return schema;
    }

    /** Seed the cache with an already-persisted row (it carries its DB id). A later merge marks it Updated. */
    public void populateExisting(SummaryRow existing) {
        if (cache.putIfAbsent(keyOf(existing), existing) != null) {
            throw new IllegalStateException("Duplicate existing row while populating cache for " + schema.table());
        }
    }

    /** Fold one delta row into its window per {@link MergeMode}. */
    public void merge(SummaryRow delta, MergeMode mode) {
        RowKey key = keyOf(delta);
        SummaryRow existing = cache.get(key);
        if (existing == null) {
            if (mode == MergeMode.SUBTRACT) {
                throw new IllegalStateException("Cannot SUBTRACT a window that was not loaded: " + schema.table());
            }
            SummaryRow fresh = delta.copyAsNew();   // id stays null -> AUTO_INCREMENT assigns it on INSERT
            cache.put(key, fresh);
            inserted.put(key, fresh);
            return;
        }
        switch (mode) {
            case ADD -> existing.mergeAdd(delta);
            case SUBTRACT -> {
                delta.negateCounters();
                existing.mergeAdd(delta);
            }
            case OVERWRITE -> existing.overwriteCounters(delta);
        }
        if (!inserted.containsKey(key)) {
            updated.putIfAbsent(key, existing);
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
        List<String> tuples = inserted.values().stream().map(r -> SqlRenderer.insertTuple(schema, r)).toList();
        SegmentedSqlWriter.writeInsertsInSegments(store, insertHeader, tuples, segmentSize);
        inserted.clear();
    }

    private void writeUpdates(SummaryStore store, int segmentSize) {
        if (updated.isEmpty()) {
            return;
        }
        List<String> statements = updated.values().stream().map(r -> SqlRenderer.updateStatement(schema, r)).toList();
        SegmentedSqlWriter.writeStatementsInSegments(store, statements, segmentSize);
        updated.clear();
    }

    private void writeDeletes(SummaryStore store, int segmentSize) {
        if (deleted.isEmpty()) {
            return;
        }
        List<String> statements = deleted.values().stream().map(r -> SqlRenderer.deleteStatement(schema, r)).toList();
        SegmentedSqlWriter.writeStatementsInSegments(store, statements, segmentSize);
        deleted.clear();
    }

    private RowKey keyOf(SummaryRow row) {
        List<Object> tokens = new ArrayList<>();
        for (ColumnDef c : schema.keyColumns()) {
            tokens.add(SqlRenderer.keyToken(row.keyValue(c.name()), c.type()));
        }
        return new RowKey(tokens);
    }

    // ---- inspection (used by tests + the batch result) ----

    public Collection<SummaryRow> rows() {
        return cache.values();
    }

    public int insertedCount() {
        return inserted.size();
    }

    public int updatedCount() {
        return updated.size();
    }
}
