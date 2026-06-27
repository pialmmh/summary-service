package com.telcobright.summary.outbox.spi;

import java.util.Collection;
import java.util.List;

/**
 * The outbox + per-bean bookmark DAO over the batch's transaction-bound connection. A bean's drain reads its
 * {@code last_offset}, reads the next outbox rows, and (after the engine writes its summaries) advances the
 * offset — all in ONE transaction (exactly-once). The reaper uses {@link #minOffset}/{@link #deleteUpTo} to
 * trim consumed rows. No commit/rollback here — the unit of work owns the transaction.
 */
public interface OutboxStore {

    /** This bean's last processed {@code summary_affected.id}; 0 if it has never run. */
    long readOffset(String entityType, String beanName);

    /** Up to {@code limit} outbox rows after {@code afterId}, ascending by id. */
    List<OutboxRow> readAfter(String entityType, long afterId, int limit);

    /** Upsert this bean's bookmark to {@code newOffset}. */
    void advanceOffset(String entityType, String beanName, long newOffset);

    /** The minimum {@code last_offset} across the given beans (the reaper's safe-to-delete watermark); 0 if none. */
    long minOffset(String entityType, Collection<String> beanNames);

    /** Delete outbox rows with {@code id <= maxIdInclusive}; returns the number deleted. */
    int deleteUpTo(String entityType, long maxIdInclusive);
}
