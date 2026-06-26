package com.telcobright.summary.engine.spi;

import com.telcobright.summary.bean.spi.ColumnType;

/**
 * A resolved summary column: its name, SQL rendering family, and whether it is part of the key or a counter.
 * The engine builds these from a bean's dimension/counter/window declarations and uses them to render
 * SELECT / INSERT / UPDATE generically, with no per-bean SQL.
 */
public record ColumnDef(String name, ColumnType type, ColumnRole role) {

    public boolean isKey() {
        return role == ColumnRole.KEY;
    }

    public boolean isCounter() {
        return role == ColumnRole.COUNTER;
    }
}
