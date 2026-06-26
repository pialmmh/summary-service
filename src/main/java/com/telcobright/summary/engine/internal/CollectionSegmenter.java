package com.telcobright.summary.engine.internal;

import java.util.List;
import java.util.function.Consumer;

/**
 * Slices a list into fixed-size segments — the ported legacy batch slicer, so any number of rows write in
 * bounded chunks and one multi-row INSERT never exceeds {@code max_allowed_packet}.
 */
final class CollectionSegmenter<T> {

    private final List<T> items;
    private int cursor = 0;

    CollectionSegmenter(List<T> items) {
        this.items = items;
    }

    private List<T> nextSegment(int segmentSize) {
        if (segmentSize <= 0) {
            throw new IllegalArgumentException("Segment size must be > 0");
        }
        int from = Math.min(cursor, items.size());
        int to = Math.min(cursor + segmentSize, items.size());
        cursor += segmentSize;
        return items.subList(from, to);
    }

    void forEachSegment(int segmentSize, Consumer<List<T>> method) {
        List<T> segment;
        while (!(segment = nextSegment(segmentSize)).isEmpty()) {
            method.accept(segment);
        }
    }
}
