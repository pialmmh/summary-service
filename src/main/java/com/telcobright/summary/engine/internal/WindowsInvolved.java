package com.telcobright.summary.engine.internal;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.WindowDef;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The distinct window buckets a batch touches — the few datetimes the load query filters on. Computing this
 * once and loading all involved windows in ONE query is THE invariant: loading per event would re-read (and
 * double-count) a window that several events share.
 */
public final class WindowsInvolved {

    private WindowsInvolved() {
    }

    public static <E> Set<LocalDateTime> of(SummaryBean<E> bean, WindowDef window, List<E> events) {
        Set<LocalDateTime> buckets = new LinkedHashSet<>();
        for (E event : events) {
            buckets.add(window.granularity().bucketStart(bean.eventTime(event), bean.zone()));
        }
        return buckets;
    }
}
