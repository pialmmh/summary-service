package com.telcobright.summary.engine.internal;

import com.telcobright.summary.engine.spi.SummaryStore;

import java.util.List;

/**
 * The single-connection batch writer (ported BatchSqlWriter). INSERTs are written as multi-row extended
 * inserts ({@code header + join(",", tuples)}) sliced into segments; UPDATE/DELETE statements are
 * {@code ;}-joined per segment and run in one round trip. Requires {@code allowMultiQueries=true} on the
 * MySQL JDBC URL for the statement segments. NO transaction control here — the batch runner owns the commit.
 */
final class SegmentedSqlWriter {

    private SegmentedSqlWriter() {
    }

    /** Write the value tuples as segmented multi-row INSERTs; returns total affected rows. */
    static int writeInsertsInSegments(SummaryStore store, String header, List<String> valueTuples, int segmentSize) {
        if (valueTuples.isEmpty()) {
            return 0;
        }
        int[] affected = {0};
        new CollectionSegmenter<>(valueTuples).forEachSegment(segmentSize, segment ->
                affected[0] += store.executeNonQuery(header + String.join(",", segment)));
        return affected[0];
    }

    /** Run each pre-built statement (UPDATE/DELETE) in {@code ;}-joined segments; returns total affected rows. */
    static int writeStatementsInSegments(SummaryStore store, List<String> statements, int segmentSize) {
        if (statements.isEmpty()) {
            return 0;
        }
        int[] affected = {0};
        new CollectionSegmenter<>(statements).forEachSegment(segmentSize, segment -> {
            StringBuilder sql = new StringBuilder();
            for (String s : segment) {
                sql.append(s);
                if (!s.stripTrailing().endsWith(";")) {
                    sql.append(';');
                }
            }
            affected[0] += store.executeNonQuery(sql.toString());
        });
        return affected[0];
    }
}
