package com.telcobright.summary.bean.spi;

/**
 * The SQL rendering family of a summary column. Drives how a value is written into an extended INSERT
 * tuple or an UPDATE set-clause: numerics go in bare, strings/datetimes are quoted, null strings render
 * as {@code ''} (the legacy ToNotNullSqlField behaviour, so a key never NREs and stays comparable).
 */
public enum ColumnType {
    INT,
    LONG,
    DECIMAL,
    STRING,
    DATETIME
}
