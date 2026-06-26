package com.telcobright.summary.testkit;

import com.telcobright.summary.engine.spi.SummaryRow;
import com.telcobright.summary.engine.spi.SummaryStore;
import com.telcobright.summary.engine.spi.SummaryStoreException;
import com.telcobright.summary.engine.spi.WindowSchema;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link SummaryStore} — the SPI fake that IS the engine test surface (no database). It records the
 * SQL the engine runs, counts load calls and the buckets each load received (to prove "load involved windows
 * once"), and can be seeded with already-persisted rows. Optionally fails writes to exercise rollback.
 */
public final class FakeSummaryStore implements SummaryStore {

    private final Map<String, List<SummaryRow>> seeded = new HashMap<>();
    private final Map<String, Integer> loadCalls = new HashMap<>();
    private final Map<String, Collection<LocalDateTime>> lastBuckets = new HashMap<>();
    private final List<String> executedSql = new ArrayList<>();
    private boolean failWrites = false;

    public void seed(String table, SummaryRow row) {
        seeded.computeIfAbsent(table, t -> new ArrayList<>()).add(row);
    }

    public void failWrites() {
        this.failWrites = true;
    }

    @Override
    public List<SummaryRow> load(WindowSchema schema, Collection<LocalDateTime> buckets) {
        loadCalls.merge(schema.table(), 1, Integer::sum);
        lastBuckets.put(schema.table(), buckets);
        List<SummaryRow> result = new ArrayList<>();
        for (SummaryRow row : seeded.getOrDefault(schema.table(), List.of())) {
            if (buckets.contains((LocalDateTime) row.keyValue(schema.bucketColumn()))) {
                result.add(row);
            }
        }
        return result;
    }

    @Override
    public int executeNonQuery(String sql) {
        if (failWrites) {
            throw new SummaryStoreException("write failed (test)", null);
        }
        executedSql.add(sql);
        return 1;
    }

    public int loadCount(String table) {
        return loadCalls.getOrDefault(table, 0);
    }

    public Collection<LocalDateTime> bucketsLoaded(String table) {
        return lastBuckets.getOrDefault(table, List.of());
    }

    public List<String> executedSql() {
        return executedSql;
    }

    public boolean ranSqlMatching(String startsWith) {
        return executedSql.stream().anyMatch(s -> s.startsWith(startsWith));
    }

    public String firstSqlMatching(String startsWith) {
        return executedSql.stream().filter(s -> s.startsWith(startsWith)).findFirst().orElse(null);
    }
}
