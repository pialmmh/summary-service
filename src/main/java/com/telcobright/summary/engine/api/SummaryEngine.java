package com.telcobright.summary.engine.api;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryEntity;
import com.telcobright.summary.engine.internal.SummaryCache;
import com.telcobright.summary.engine.spi.MergeMode;
import com.telcobright.summary.engine.spi.SummaryStore;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The bean-agnostic load-merge-write pipeline, generic over the summary ENTITY {@code T}. For one bean's batch
 * of already-built entities it computes the distinct window buckets, loads those windows ONCE, merges every
 * entity into the cache, then writes the net change. It performs NO transaction control — the caller
 * ({@code OutboxReader.drainOnce}) owns the single commit, so a failure anywhere rolls the whole batch back.
 *
 * <p>RULE ONE logging: per-batch detail is DEBUG-gated; failures surface to the worker.
 */
@ApplicationScoped
public class SummaryEngine {

    /** Rows per multi-row extended INSERT (legacy SegmentSizeForDbWrite). */
    public static final int DEFAULT_SEGMENT_SIZE = 1000;

    private static final Logger LOG = Logger.getLogger(SummaryEngine.class);

    public <T extends SummaryEntity<T>> BatchResult runBatch(SummaryBean<T> bean, List<T> entities, SummaryStore store) {
        return runBatch(bean, entities, store, DEFAULT_SEGMENT_SIZE);
    }

    /** Load -> merge -> write the whole batch for one bean. Caller owns the transaction. */
    public <T extends SummaryEntity<T>> BatchResult runBatch(SummaryBean<T> bean, List<T> entities,
                                                             SummaryStore store, int segmentSize) {
        if (entities.isEmpty()) {
            return BatchResult.empty(bean.name());
        }
        Set<LocalDateTime> bucketsInvolved = new LinkedHashSet<>();
        for (T entity : entities) {
            bucketsInvolved.add(bean.bucketOf(entity));
        }
        SummaryCache<T> cache = new SummaryCache<>(bean.table(), bean.insertColumnsCsv(), bean.bucketColumn());
        store.load(bean.table(), bean.insertColumnsCsv(), bean.bucketColumn(), bucketsInvolved, bean::mapRow)
                .forEach(cache::populateExisting);
        for (T entity : entities) {
            cache.merge(entity, MergeMode.ADD);
        }
        int inserts = cache.insertedCount();
        int updates = cache.updatedCount();
        cache.flush(store, segmentSize);

        BatchResult result = new BatchResult(bean.name(), entities.size(), inserts, updates);
        if (LOG.isDebugEnabled()) {
            LOG.debugf("bean=%s table=%s batch: events=%d inserts=%d updates=%d", result.bean(), bean.table(),
                    result.eventsProcessed(), result.rowsInserted(), result.rowsUpdated());
        }
        return result;
    }
}
