package com.telcobright.summary.bean.spi;

/**
 * How a bean folds generated summaries into its windows — the per-bean SETTING (user directive 2026-07-03).
 *
 * <ul>
 *   <li>{@code INCREMENTAL} — generate a delta per input and AMEND the window (add; a correction outbox row
 *       subtracts). ALL outbox polls run incrementally — this is the default and the only mode implemented.</li>
 *   <li>{@code REPLACE} — the caller supplies ALL inputs of a window; existing rows of that window are dropped
 *       and fresh summaries written (overwrite; naturally idempotent). PROTOTYPE ONLY for now — the engine
 *       throws {@code UnsupportedOperationException} until it is implemented.</li>
 * </ul>
 */
public enum SummaryMode {
    INCREMENTAL,
    REPLACE;

    /** Config value → mode; null/blank/"incremental" → INCREMENTAL, "replace" → REPLACE (case-insensitive). */
    public static SummaryMode parse(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("incremental")) {
            return INCREMENTAL;
        }
        if (value.equalsIgnoreCase("replace")) {
            return REPLACE;
        }
        throw new IllegalArgumentException("unknown summary mode '" + value + "' — use incremental or replace");
    }
}
