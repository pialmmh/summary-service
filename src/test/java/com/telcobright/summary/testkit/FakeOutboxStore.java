package com.telcobright.summary.testkit;

import com.telcobright.summary.engine.spi.SummaryStoreException;
import com.telcobright.summary.outbox.spi.OutboxRow;
import com.telcobright.summary.outbox.spi.OutboxStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory {@link OutboxStore} — seedable outbox rows + per-bean offsets, for the reader/reaper tests. A row seeded
 * {@link #seedOf OF an entity} is that entity's alone, as in the real table ({@code entity_type}); a row seeded without
 * one answers every entity (what the tests of one entity always seeded).
 */
public final class FakeOutboxStore implements OutboxStore {

    private final List<OutboxRow> rows = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The entity of each row seeded OF one, by its id; a row not in here answers every entity. */
    private final Map<Long, String> entityOf = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> offsets = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean failReads = false;

    public void seed(long id, String data) {
        rows.add(new OutboxRow(id, data));
    }

    public void seed(long id, String op, String data) {
        rows.add(new OutboxRow(id, op, data));
    }

    /** A row of {@code entityType} only — billing-core's cdr and its flow ingest's wifi_flow share one table. */
    public void seedOf(String entityType, long id, String data) {
        entityOf.put(id, entityType);
        rows.add(new OutboxRow(id, data));
    }

    /** Rows {@code fromId}..{@code toId} of {@code entityType}, added at once (a backlog of many rows). */
    public void seedOf(String entityType, long fromId, long toId, String data) {
        List<OutboxRow> backlog = new ArrayList<>();
        for (long id = fromId; id <= toId; id++) {
            entityOf.put(id, entityType);
            backlog.add(new OutboxRow(id, data));
        }
        rows.addAll(backlog);
    }

    /** The ids of the rows {@code entityType} reads, in order. */
    public List<Long> idsOf(String entityType) {
        return rows.stream().filter(r -> isOf(r, entityType)).map(OutboxRow::id).sorted().toList();
    }

    private boolean isOf(OutboxRow row, String entityType) {
        String entity = entityOf.get(row.id());
        return entity == null || entity.equals(entityType);
    }

    public void failReads() {
        this.failReads = true;
    }

    @Override
    public long readOffset(String entityType, String beanName) {
        if (failReads) {
            throw new SummaryStoreException("readOffset failed (test)", null);
        }
        return offsets.getOrDefault(key(entityType, beanName), 0L);
    }

    @Override
    public void initOffsetAtHead(String entityType, String beanName) {
        offsets.computeIfAbsent(key(entityType, beanName),
                k -> rows.stream().filter(r -> isOf(r, entityType)).mapToLong(OutboxRow::id).max().orElse(0L));
    }

    /** billing-core has made its outbox in this schema (the default); false = a tier it has not served yet. */
    public boolean outboxPresent = true;

    @Override
    public boolean outboxExists() {
        return outboxPresent;
    }

    @Override
    public Set<String> bookmarkedBeans(String entityType) {
        Set<String> beans = new LinkedHashSet<>();
        for (String key : offsets.keySet()) {
            if (key.startsWith(entityType + "|")) beans.add(key.substring(entityType.length() + 1));
        }
        return beans;
    }

    @Override
    public void seedOffsetIfAbsent(String entityType, String beanName, long offset) {
        offsets.putIfAbsent(key(entityType, beanName), offset);
    }

    public boolean hasBookmark(String entityType, String beanName) {
        return offsets.containsKey(key(entityType, beanName));
    }

    @Override
    public List<OutboxRow> readAfter(String entityType, long afterId, int limit) {
        if (!outboxPresent) {
            throw new SummaryStoreException("relation \"summary_affected\" does not exist (test)", null);
        }
        return rows.stream()
                .filter(r -> r.id() > afterId && isOf(r, entityType))
                .sorted(Comparator.comparingLong(OutboxRow::id))
                .limit(limit)
                .toList();
    }

    @Override
    public void advanceOffset(String entityType, String beanName, long newOffset) {
        offsets.put(key(entityType, beanName), newOffset);
    }

    @Override
    public long minOffset(String entityType, Collection<String> beanNames) {
        long min = Long.MAX_VALUE;
        for (String bean : beanNames) {
            Long off = offsets.get(key(entityType, bean));
            if (off == null) {
                return 0L;   // a bean with no offset yet -> nothing safe to delete
            }
            min = Math.min(min, off);
        }
        return min == Long.MAX_VALUE ? 0L : min;
    }

    @Override
    public int deleteUpTo(String entityType, long maxIdInclusive) {
        int before = rows.size();
        rows.removeIf(r -> r.id() <= maxIdInclusive && isOf(r, entityType));
        return before - rows.size();
    }

    /** The reaper's bounded batch: at most {@code limit} rows of the entity, the lowest ids first; each one recorded. */
    @Override
    public int deleteUpTo(String entityType, long maxIdInclusive, int limit) {
        java.util.Set<Long> batch = new java.util.HashSet<>(rows.stream().filter(r -> r.id() <= maxIdInclusive && isOf(r, entityType))
                .map(OutboxRow::id).sorted().limit(Math.max(limit, 0)).toList());
        rows.removeIf(r -> batch.contains(r.id()));
        boundedDeletes.add(new BoundedDelete(entityType, limit, batch.size()));
        return batch.size();
    }

    @Override
    public long countUpTo(String entityType, long maxIdInclusive) {
        return rows.stream().filter(r -> r.id() <= maxIdInclusive && isOf(r, entityType)).count();
    }

    /** One bounded batch as the reaper asked for it: the entity, the limit, the rows it deleted. */
    public record BoundedDelete(String entityType, int limit, int deleted) {
    }

    private final List<BoundedDelete> boundedDeletes = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** The bounded batches of {@code entityType}, in order — each one its own transaction of the reader. */
    public List<BoundedDelete> boundedDeletesOf(String entityType) {
        return boundedDeletes.stream().filter(d -> d.entityType().equals(entityType)).toList();
    }

    public int rowCount() {
        return rows.size();
    }

    @Override
    public void deadLetter(String entityType, String beanName, OutboxRow row, String error) {
        deadLetters.add(new DeadLetter(entityType, beanName, row.id(), row.data(), error));
    }

    public List<DeadLetter> deadLetters() {
        return deadLetters;
    }

    public record DeadLetter(String entityType, String beanName, long outboxId, String data, String error) {
    }

    private final List<DeadLetter> deadLetters = new ArrayList<>();

    private static String key(String entityType, String beanName) {
        return entityType + "|" + beanName;
    }
}
