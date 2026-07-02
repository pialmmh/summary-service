package com.telcobright.summary.testkit;

import com.telcobright.summary.engine.spi.SummaryStoreException;
import com.telcobright.summary.outbox.spi.OutboxRow;
import com.telcobright.summary.outbox.spi.OutboxStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory {@link OutboxStore} — seedable outbox rows + per-bean offsets, for the reader/reaper tests. */
public final class FakeOutboxStore implements OutboxStore {

    private final List<OutboxRow> rows = new ArrayList<>();
    private final Map<String, Long> offsets = new HashMap<>();
    private boolean failReads = false;

    public void seed(long id, String data) {
        rows.add(new OutboxRow(id, data));
    }

    public void seed(long id, String op, String data) {
        rows.add(new OutboxRow(id, op, data));
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
                k -> rows.stream().mapToLong(OutboxRow::id).max().orElse(0L));
    }

    @Override
    public List<OutboxRow> readAfter(String entityType, long afterId, int limit) {
        return rows.stream()
                .filter(r -> r.id() > afterId)
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
        rows.removeIf(r -> r.id() <= maxIdInclusive);
        return before - rows.size();
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
