package com.telcobright.summary.testkit;

import com.telcobright.summary.engine.spi.RowMapper;
import com.telcobright.summary.engine.spi.SummaryStore;
import com.telcobright.summary.engine.spi.SummaryStoreException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link SummaryStore} — the SPI fake that IS the engine test surface (no database). It records the
 * SQL the engine runs, counts load calls and the buckets each load received (to prove "load involved windows
 * once"), and can be seeded with already-persisted entities. Optionally fails writes to exercise rollback.
 */
public final class FakeSummaryStore implements SummaryStore {

    private record Seed(LocalDateTime bucket, Object entity) {
    }

    private final Map<String, List<Seed>> seeded = new HashMap<>();
    private final Map<String, Integer> loadCalls = new HashMap<>();
    private final Map<String, Collection<LocalDateTime>> lastBuckets = new HashMap<>();
    private final List<String> executedSql = new ArrayList<>();
    private boolean failWrites = false;
    private volatile String failingPrefix;

    public synchronized void seed(String table, LocalDateTime bucket, Object entity) {
        seeded.computeIfAbsent(table, t -> new ArrayList<>()).add(new Seed(bucket, entity));
    }

    public void failWrites() {
        this.failWrites = true;
    }

    /** Fail every statement that starts with {@code prefix} (a table that cannot be made); {@code null} = none. */
    public void failSqlStartingWith(String prefix) {
        this.failingPrefix = prefix;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> List<T> load(String table, String insertColumnsCsv, String bucketColumn,
                            Collection<LocalDateTime> buckets, RowMapper<T> mapper) {
        loadCalls.merge(table, 1, Integer::sum);
        lastBuckets.put(table, buckets);
        List<T> result = new ArrayList<>();
        for (Seed seed : seeded.getOrDefault(table, List.of())) {
            if (buckets.contains(seed.bucket())) {
                result.add((T) seed.entity());
            }
        }
        return result;
    }

    @Override
    public synchronized int executeNonQuery(String sql) {
        String failing = failingPrefix;
        if (failWrites || (failing != null && sql.startsWith(failing))) {
            throw new SummaryStoreException("write failed (test)", null);
        }
        executedSql.add(sql);
        return 1;
    }

    public synchronized int loadCount(String table) {
        return loadCalls.getOrDefault(table, 0);
    }

    public synchronized Collection<LocalDateTime> bucketsLoaded(String table) {
        return lastBuckets.getOrDefault(table, List.of());
    }

    public synchronized List<String> executedSql() {
        return List.copyOf(executedSql);
    }

    public synchronized boolean ranSqlMatching(String startsWith) {
        return executedSql.stream().anyMatch(s -> s.startsWith(startsWith));
    }

    public synchronized String firstSqlMatching(String startsWith) {
        return executedSql.stream().filter(s -> s.startsWith(startsWith)).findFirst().orElse(null);
    }

    /** How many statements starting with {@code startsWith} ran. */
    public synchronized long countSqlMatching(String startsWith) {
        return executedSql.stream().filter(s -> s.startsWith(startsWith)).count();
    }
}
