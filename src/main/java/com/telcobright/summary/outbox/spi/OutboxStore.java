package com.telcobright.summary.outbox.spi;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The outbox + per-bean bookmark DAO over the batch's transaction-bound connection. A bean's drain reads its
 * {@code last_offset}, reads the next outbox rows, and (after the engine writes its summaries) advances the
 * offset — all in ONE transaction (exactly-once). The reaper uses {@link #minOffset}/{@link #deleteUpTo} to
 * trim consumed rows. No commit/rollback here — the unit of work owns the transaction.
 */
public interface OutboxStore {

    /** This bean's last processed {@code summary_affected.id}; 0 if it has never run. */
    long readOffset(String entityType, String beanName);

    /**
     * Seed this bean's bookmark at the outbox HEAD (current max id) if it has no row yet — the head-init rule:
     * a bean enabled AFTER the system has run must start from NOW, not from whatever arbitrary residue the
     * reaper hasn't deleted yet (a partial backfill would masquerade as complete windows). No-op if a row exists.
     */
    void initOffsetAtHead(String entityType, String beanName);

    /**
     * Is the outbox table ({@code summary_affected}) in this schema? It is billing-core's: on PostgreSQL the
     * summary side never creates it, so a tier schema billing-core has not served yet has none — nothing to
     * drain and nothing to reap there, and that is not a failure.
     */
    boolean outboxExists();

    /** The beans that have a bookmark for this entity in this schema. Empty = the schema was never served. */
    Set<String> bookmarkedBeans(String entityType);

    /** Give this bean the bookmark {@code offset} unless it has one (never moves an existing bookmark). */
    void seedOffsetIfAbsent(String entityType, String beanName, long offset);

    /** Up to {@code limit} outbox rows after {@code afterId}, ascending by id. */
    List<OutboxRow> readAfter(String entityType, long afterId, int limit);

    /** Upsert this bean's bookmark to {@code newOffset}. */
    void advanceOffset(String entityType, String beanName, long newOffset);

    /** The minimum {@code last_offset} across the given beans (the reaper's safe-to-delete watermark); 0 if none. */
    long minOffset(String entityType, Collection<String> beanNames);

    /** Delete outbox rows with {@code id <= maxIdInclusive}; returns the number deleted. */
    int deleteUpTo(String entityType, long maxIdInclusive);

    /**
     * Copy a poison outbox row into {@code summary_affected_dlq} for this bean (same transaction as the offset
     * advance that skips it) — the quarantine record an operator repairs from.
     */
    void deadLetter(String entityType, String beanName, OutboxRow row, String error);
}
