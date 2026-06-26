package com.telcobright.summary.engine.spi;

import com.telcobright.summary.bean.spi.WindowDef;

import java.util.List;

/**
 * The resolved column layout for ONE window of a bean — the engine's compiled view of the bean's
 * declarations. Column order is fixed: dimensions (in bean order), then the bucket column, then counters
 * (in bean order). That order is the SELECT / INSERT order, so a loaded row and a freshly built row line up.
 *
 * @param window  the window this schema describes (table, bucket column, granularity)
 * @param columns ALL columns in canonical order (dimensions…, bucket, counters…)
 */
public record WindowSchema(WindowDef window, List<ColumnDef> columns) {

    public String table() {
        return window.table();
    }

    public String bucketColumn() {
        return window.bucketColumn();
    }

    public List<ColumnDef> keyColumns() {
        return columns.stream().filter(ColumnDef::isKey).toList();
    }

    public List<ColumnDef> counterColumns() {
        return columns.stream().filter(ColumnDef::isCounter).toList();
    }
}
