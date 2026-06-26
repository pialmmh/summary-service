package com.telcobright.summary.engine.spi;

/**
 * How a delta row folds into a cached window row.
 *
 * <ul>
 *   <li>{@code ADD} — normal increment: counters += delta.</li>
 *   <li>{@code SUBTRACT} — decrement: counters += (-delta). The window MUST already exist (you cannot
 *       subtract from a row that was never loaded).</li>
 *   <li>{@code OVERWRITE} — correction: counters are REPLACED by the recomputed absolute values; naturally
 *       idempotent. Used by the correction/recompute path.</li>
 * </ul>
 */
public enum MergeMode {
    ADD,
    SUBTRACT,
    OVERWRITE
}
