package com.telcobright.summary.engine.internal;

import com.telcobright.summary.bean.spi.ColumnType;
import com.telcobright.summary.engine.spi.ColumnDef;
import com.telcobright.summary.engine.spi.SummaryRow;
import com.telcobright.summary.engine.spi.WindowSchema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.StringJoiner;

/**
 * Renders the generic summary SQL from a {@link WindowSchema} — the one place that knows the text. Numbers
 * go in bare; strings and datetimes are quoted; null strings render as {@code ''} (the legacy ToNotNullSqlField
 * rule). INSERT omits id (AUTO_INCREMENT assigns it); UPDATE/DELETE target the loaded row by id.
 */
final class SqlRenderer {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SqlRenderer() {
    }

    /** {@code select id,col1,... from <table> where <bucketCol> in ('..','..')} — id so loaded rows can be UPDATEd. */
    static String selectByBuckets(WindowSchema schema, Collection<LocalDateTime> buckets) {
        StringJoiner cols = new StringJoiner(",");
        cols.add("id");
        schema.columns().forEach(c -> cols.add(c.name()));
        StringJoiner in = new StringJoiner(",");
        buckets.forEach(b -> in.add("'" + DATETIME.format(b) + "'"));
        return "select " + cols + " from " + schema.table()
                + " where " + schema.bucketColumn() + " in (" + in + ")";
    }

    /** {@code insert into <table> (col1,...) values } — the header reused for every value tuple in a segment. */
    static String insertHeader(WindowSchema schema) {
        StringJoiner cols = new StringJoiner(",");
        schema.columns().forEach(c -> cols.add(c.name()));
        return "insert into " + schema.table() + " (" + cols + ") values ";
    }

    /** {@code (v1,v2,...)} — values in canonical column order, matching {@link #insertHeader}. */
    static String insertTuple(WindowSchema schema, SummaryRow row) {
        StringJoiner vals = new StringJoiner(",", "(", ")");
        for (ColumnDef c : schema.columns()) {
            vals.add(c.isCounter() ? counter(row.counter(c.name()), c.type()) : value(row.keyValue(c.name()), c.type()));
        }
        return vals.toString();
    }

    /** {@code update <table> set counter1=v1,... where id=<id>}. */
    static String updateStatement(WindowSchema schema, SummaryRow row) {
        StringJoiner sets = new StringJoiner(",");
        for (ColumnDef c : schema.counterColumns()) {
            sets.add(c.name() + "=" + counter(row.counter(c.name()), c.type()));
        }
        return "update " + schema.table() + " set " + sets + " where id=" + row.id();
    }

    /** {@code delete from <table> where id=<id>}. */
    static String deleteStatement(WindowSchema schema, SummaryRow row) {
        return "delete from " + schema.table() + " where id=" + row.id();
    }

    // ---- value rendering ----

    /**
     * The canonical token of a key value — also used as the cache merge key, so a freshly built row and a
     * reloaded row compare equal regardless of JDBC's Integer-vs-Long or decimal scale (1.5 vs 1.500000).
     */
    static String keyToken(Object v, ColumnType type) {
        return value(v, type);
    }

    private static String value(Object v, ColumnType type) {
        return switch (type) {
            case STRING -> v == null ? "''" : "'" + escape(v.toString()) + "'";
            case DATETIME -> v == null ? "''" : "'" + DATETIME.format((LocalDateTime) v) + "'";
            case INT, LONG -> v == null ? "0" : Long.toString(((Number) v).longValue());
            // strip trailing zeros so a key decimal canonicalizes (1.500000 -> 1.5) and stays comparable
            case DECIMAL -> v == null ? "0" : new BigDecimal(v.toString()).stripTrailingZeros().toPlainString();
        };
    }

    private static String counter(BigDecimal v, ColumnType type) {
        BigDecimal n = v == null ? BigDecimal.ZERO : v;
        return switch (type) {
            case INT, LONG -> n.toBigInteger().toString();
            case DECIMAL -> n.toPlainString();
            case STRING, DATETIME -> throw new IllegalStateException("counter column cannot be " + type);
        };
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("'", "''");
    }
}
