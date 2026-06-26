package com.telcobright.summary.engine.internal;

import com.telcobright.summary.bean.spi.ColumnType;
import com.telcobright.summary.bean.spi.CounterDef;
import com.telcobright.summary.bean.spi.DimensionDef;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.WindowDef;
import com.telcobright.summary.engine.spi.ColumnDef;
import com.telcobright.summary.engine.spi.ColumnRole;
import com.telcobright.summary.engine.spi.WindowSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a bean's dimension/counter declarations + one window into a {@link WindowSchema} with columns in
 * canonical order: dimensions (bean order), then the window bucket column, then counters (bean order).
 */
public final class WindowSchemaFactory {

    private WindowSchemaFactory() {
    }

    public static <E> WindowSchema build(SummaryBean<E> bean, WindowDef window) {
        List<ColumnDef> columns = new ArrayList<>();
        for (DimensionDef<E> d : bean.dimensions()) {
            columns.add(new ColumnDef(d.column(), d.type(), ColumnRole.KEY));
        }
        columns.add(new ColumnDef(window.bucketColumn(), ColumnType.DATETIME, ColumnRole.KEY));
        for (CounterDef<E> c : bean.counters()) {
            columns.add(new ColumnDef(c.column(), c.type(), ColumnRole.COUNTER));
        }
        return new WindowSchema(window, List.copyOf(columns));
    }
}
