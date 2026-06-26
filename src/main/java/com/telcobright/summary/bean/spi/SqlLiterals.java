package com.telcobright.summary.bean.spi;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Renders Java values into MySQL literals for the multi-row INSERT / UPDATE that summary entities build —
 * the port of legacy {@code ToMySqlField} (numbers/dates) + {@code ToNotNullSqlField} (strings, null -> '').
 * {@link #decimalKey} additionally strips trailing zeros so a decimal used in a key canonicalizes.
 */
public final class SqlLiterals {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SqlLiterals() {
    }

    public static String num(long value) {
        return Long.toString(value);
    }

    public static String num(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }

    /** Strings: null/absent renders as {@code ''} (the legacy ToNotNullSqlField rule), else quoted + escaped. */
    public static String str(String value) {
        return value == null ? "''" : "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    public static String datetime(LocalDateTime value) {
        return value == null ? "''" : "'" + DATETIME.format(value) + "'";
    }

    /** The canonical datetime token for a key (no quotes): {@code yyyy-MM-dd HH:mm:ss}. */
    public static String datetimeKey(LocalDateTime value) {
        return value == null ? "" : DATETIME.format(value);
    }

    /** The canonical decimal token for a key: trailing zeros stripped so 1.5 and 1.500000 compare equal. */
    public static String decimalKey(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
