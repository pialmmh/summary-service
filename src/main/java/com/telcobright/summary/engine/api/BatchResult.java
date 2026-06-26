package com.telcobright.summary.engine.api;

/**
 * What one batch did: how many events folded in, and how many window rows were inserted vs updated. Returned
 * by {@link SummaryEngine#runBatch} for logging/metrics; carries no rows (those are already written).
 */
public record BatchResult(String bean, int eventsProcessed, int rowsInserted, int rowsUpdated) {

    public static BatchResult empty(String bean) {
        return new BatchResult(bean, 0, 0, 0);
    }
}
