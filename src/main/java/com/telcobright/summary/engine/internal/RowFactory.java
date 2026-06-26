package com.telcobright.summary.engine.internal;

import com.telcobright.summary.bean.spi.ColumnType;
import com.telcobright.summary.bean.spi.CounterDef;
import com.telcobright.summary.bean.spi.DimensionDef;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.WindowDef;
import com.telcobright.summary.engine.spi.SummaryRow;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;

/**
 * Builds the single-event DELTA row for one window: the key = dimensions (extracted, normalized) + the window
 * bucket (event time truncated to the granularity); the counters = each event's declared delta. Normalization
 * (null string -> "", absent numeric -> 0) keeps the key stable against a reloaded row.
 */
public final class RowFactory {

    private RowFactory() {
    }

    public static <E> SummaryRow delta(SummaryBean<E> bean, WindowDef window, E event) {
        LinkedHashMap<String, Object> key = new LinkedHashMap<>();
        for (DimensionDef<E> d : bean.dimensions()) {
            key.put(d.column(), normalizeKey(d.extractor().apply(event), d.type()));
        }
        key.put(window.bucketColumn(), window.granularity().bucketStart(bean.eventTime(event), bean.zone()));

        LinkedHashMap<String, BigDecimal> counters = new LinkedHashMap<>();
        for (CounterDef<E> c : bean.counters()) {
            counters.put(c.column(), toBigDecimal(c.delta().apply(event)));
        }
        return new SummaryRow(null, key, counters);
    }

    private static Object normalizeKey(Object v, ColumnType type) {
        if (type == ColumnType.STRING) {
            return v == null ? "" : v.toString();
        }
        return v == null ? 0 : v;
    }

    private static BigDecimal toBigDecimal(Number n) {
        if (n == null) {
            return BigDecimal.ZERO;
        }
        return n instanceof BigDecimal b ? b : new BigDecimal(n.toString());
    }
}
