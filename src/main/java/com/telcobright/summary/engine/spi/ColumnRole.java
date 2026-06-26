package com.telcobright.summary.engine.spi;

/**
 * What a summary column is to the merge. KEY columns (dimensions + the window bucket) form the tuple the
 * engine groups and merges on; COUNTER columns are the additive values that get summed / negated /
 * overwritten.
 */
public enum ColumnRole {
    KEY,
    COUNTER
}
