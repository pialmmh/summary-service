package com.telcobright.summary.engine.api;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.WindowDef;
import com.telcobright.summary.engine.internal.RowFactory;
import com.telcobright.summary.engine.internal.SummaryCache;
import com.telcobright.summary.engine.internal.WindowSchemaFactory;
import com.telcobright.summary.engine.internal.WindowsInvolved;
import com.telcobright.summary.engine.spi.MergeMode;
import com.telcobright.summary.engine.spi.SummaryStore;
import com.telcobright.summary.engine.spi.WindowSchema;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The bean-agnostic load-merge-write pipeline. For one bean's batch it builds each window once
 * (windows-involved -> ONE load -> merge every event), then writes the net change of every window through the
 * store. It performs NO transaction control — the caller ({@code BatchRunner}) owns the single commit, so a
 * failure anywhere rolls the whole batch back.
 *
 * <p>RULE ONE logging: per-batch detail is DEBUG-gated (batches are hot); failures surface to the worker.
 */
@ApplicationScoped
public class SummaryEngine {

    /** Rows per multi-row extended INSERT (legacy SegmentSizeForDbWrite). */
    public static final int DEFAULT_SEGMENT_SIZE = 1000;

    private static final Logger LOG = Logger.getLogger(SummaryEngine.class);

    public <E> BatchResult runBatch(SummaryBean<E> bean, List<E> events, SummaryStore store) {
        return runBatch(bean, events, store, DEFAULT_SEGMENT_SIZE);
    }

    /**
     * Load -> merge -> write the whole batch for one bean. Caller owns the transaction; any exception leaves
     * the batch for the caller to roll back.
     */
    public <E> BatchResult runBatch(SummaryBean<E> bean, List<E> events, SummaryStore store, int segmentSize) {
        if (events.isEmpty()) {
            return BatchResult.empty(bean.name());
        }
        List<SummaryCache> windows = new ArrayList<>();
        for (WindowDef window : bean.windows()) {
            windows.add(buildWindow(bean, window, events, store));
        }
        int inserts = windows.stream().mapToInt(SummaryCache::insertedCount).sum();
        int updates = windows.stream().mapToInt(SummaryCache::updatedCount).sum();
        for (SummaryCache window : windows) {
            window.flush(store, segmentSize);
        }
        BatchResult result = new BatchResult(bean.name(), events.size(), inserts, updates);
        if (LOG.isDebugEnabled()) {
            LOG.debugf("bean=%s batch: events=%d inserts=%d updates=%d", result.bean(),
                    result.eventsProcessed(), result.rowsInserted(), result.rowsUpdated());
        }
        return result;
    }

    /** Build one window's cache: load all involved buckets ONCE, then merge every event into it. */
    private <E> SummaryCache buildWindow(SummaryBean<E> bean, WindowDef window, List<E> events, SummaryStore store) {
        WindowSchema schema = WindowSchemaFactory.build(bean, window);
        SummaryCache cache = new SummaryCache(schema);
        Set<LocalDateTime> buckets = WindowsInvolved.of(bean, window, events);
        store.load(schema, buckets).forEach(cache::populateExisting);
        for (E event : events) {
            cache.merge(RowFactory.delta(bean, window, event), MergeMode.ADD);
        }
        return cache;
    }
}
