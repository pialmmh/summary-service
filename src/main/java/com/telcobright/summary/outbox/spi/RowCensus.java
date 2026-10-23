package com.telcobright.summary.outbox.spi;

import com.telcobright.summary.bean.spi.SummaryBean;

import java.util.List;

/**
 * Hears the outbox rows a drain step COMMITTED (SS-0008) — for a count that must be made once per row, whatever number of
 * beans read it: the kinds' {@code KIND_NOT_ENABLED}. ONE bean per tier is the counting one; its drains are exactly once per
 * row (its bookmark moves with its summaries in one transaction), so what it hears is heard once, across restarts too. A
 * step that rolls back is never heard; a row quarantined as poison is not either.
 */
public interface RowCensus {

    /** A census that hears nothing — what a reader built without one has (tests, embedders). */
    RowCensus NONE = new RowCensus() {
        @Override
        public boolean counts(String tier, SummaryBean<?> bean) {
            return false;
        }

        @Override
        public void committed(String tier, List<byte[]> decodedRows) {
        }
    };

    /** Does the drain of {@code bean} in {@code tier} count its rows? Asked before each step, so the rows are kept only then. */
    boolean counts(String tier, SummaryBean<?> bean);

    /** After the step committed: the decoded rows it consumed, in id order. Must never throw into the drain. */
    void committed(String tier, List<byte[]> decodedRows);
}
