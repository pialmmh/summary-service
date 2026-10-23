package com.telcobright.summary.bean.spi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Renders a {@link SummaryTableSpec} for an engine — the statements that make the table when it is absent, in
 * the order they must run. Every statement is safe to run again ({@code IF NOT EXISTS}). And, for a table the
 * service owns the shape of, the statements that bring an OLDER table up to the description ({@link #bringUpToDate}).
 *
 * <ul>
 *   <li><b>MySQL</b>: ONE statement. The table is RANGE-partitioned by day on its bucket column, and the FULL
 *       partition set is inside the CREATE (house rule: never create bare, then ALTER). {@code DDL} commits by
 *       itself there, so one statement is also what makes it all-or-nothing.</li>
 *   <li><b>PostgreSQL</b>: a PLAIN table and one statement per index (ruled on SS-0001: a summary table holds one
 *       row per key per day or hour — no partitions, and no partitioned variant). Index names are per SCHEMA
 *       there, so each carries its table's name. {@code DDL} is transactional: the caller runs the statements in
 *       one transaction, so a table never exists without its indexes.</li>
 * </ul>
 *
 * Identifiers are never quoted: PostgreSQL stores them in lower case, as the design rules (ad-is-a-call §3).
 */
public final class TableDdl {

    private TableDdl() {
    }

    /** The statements for {@code dialect}; the MySQL partition horizon comes from the configuration. */
    public static List<String> createIfAbsent(SummaryTableSpec table, SqlDialect dialect) {
        return dialect == SqlDialect.POSTGRESQL ? postgres(table) : List.of(mysql(table));
    }

    /** MySQL's single CREATE; partitioned when the table names a bucket column, the horizon from the configuration. */
    public static String mysql(SummaryTableSpec table) {
        StringJoiner body = new StringJoiner(",");
        for (SummaryTableSpec.Column column : table.columns()) {
            body.add(mysqlColumn(column));
        }
        body.add("PRIMARY KEY (" + String.join(", ", table.primaryKey()) + ")");
        for (SummaryTableSpec.Index index : table.indexes()) {
            body.add("KEY " + index.name() + " (" + String.join(", ", mysqlIndexColumns(index)) + ")");
        }
        String create = "CREATE TABLE IF NOT EXISTS " + table.name() + " (" + body + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        return table.partitionColumn() == null ? create : create + DdlPartitions.dailyRangeFromConfig(table.partitionColumn());
    }

    /** A MySQL index column: its name, or {@code name(prefix)} where the index takes only the column's first characters. */
    private static List<String> mysqlIndexColumns(SummaryTableSpec.Index index) {
        if (index.mysqlPrefixChars() <= 0) {
            return index.columns();
        }
        return index.columns().stream().map(column -> column + "(" + index.mysqlPrefixChars() + ")").toList();
    }

    /** PostgreSQL: the plain table, then its indexes. */
    public static List<String> postgres(SummaryTableSpec table) {
        StringJoiner body = new StringJoiner(",");
        for (SummaryTableSpec.Column column : table.columns()) {
            body.add(postgresColumn(column));
        }
        body.add("PRIMARY KEY (" + String.join(", ", table.primaryKey()) + ")");
        List<String> statements = new ArrayList<>();
        statements.add("CREATE TABLE IF NOT EXISTS " + table.name() + " (" + body + ")");
        for (SummaryTableSpec.Index index : table.indexes()) {
            statements.add("CREATE INDEX IF NOT EXISTS " + table.name() + "_" + index.name() + " ON " + table.name()
                    + " (" + String.join(", ", index.columns()) + ")");
        }
        return statements;
    }

    /**
     * The statements that bring an EXISTING table up to its description — only for a table whose shape this service
     * owns ({@link SummaryTableSpec#keptUpToDate()}); for any other table: none, ever.
     *
     * <ul>
     *   <li>a column the table LACKS is added, with its default — the rows that are there read it as that default
     *       (history is not rebuilt);</li>
     *   <li>a text column that is NARROWER than described is widened. Nothing is ever narrowed, dropped, renamed or
     *       re-typed.</li>
     * </ul>
     *
     * {@code existing} is what the table has NOW: column name → the width of a text column, or -1
     * ({@code SummaryStore.columnWidths}). A table that is as described gives no statement — so the step is safe at
     * every start: the second time it changes nothing. An empty {@code existing} (no such table to compare with)
     * gives none either.
     */
    public static List<String> bringUpToDate(SummaryTableSpec table, SqlDialect dialect, Map<String, Integer> existing) {
        if (!table.keptUpToDate() || existing.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> now = new HashMap<>();
        existing.forEach((name, width) -> now.put(name.toLowerCase(Locale.ROOT), width));
        boolean postgres = dialect == SqlDialect.POSTGRESQL;
        List<String> statements = new ArrayList<>();
        for (SummaryTableSpec.Column column : table.columns()) {
            Integer width = now.get(column.name().toLowerCase(Locale.ROOT));
            if (width == null) {
                statements.add(addColumn(table, column, postgres));
            } else if (column.type() == SummaryTableSpec.Type.VARCHAR && width >= 0 && width < column.width()) {
                statements.add(postgres
                        ? "ALTER TABLE " + table.name() + " ALTER COLUMN " + column.name() + " TYPE VARCHAR(" + column.width() + ")"
                        : "ALTER TABLE " + table.name() + " MODIFY COLUMN " + mysqlColumn(column));
            }
        }
        return statements;
    }

    /** A column an existing table lacks. One without a default cannot be given to rows that are already there. */
    private static String addColumn(SummaryTableSpec table, SummaryTableSpec.Column column, boolean postgres) {
        if (column.identity() || column.defaultLiteral() == null) {
            throw new IllegalStateException("table " + table.name() + " has no column " + column.name() + ", and that column has no default: "
                    + "it cannot be added to a table that holds rows. Is " + table.name() + " this service's table?");
        }
        return postgres
                ? "ALTER TABLE " + table.name() + " ADD COLUMN IF NOT EXISTS " + postgresColumn(column)
                : "ALTER TABLE " + table.name() + " ADD COLUMN " + mysqlColumn(column);
    }

    private static String mysqlColumn(SummaryTableSpec.Column column) {
        String type = switch (column.type()) {
            case BIGINT -> "BIGINT";
            case INT -> "INT";
            case TINYINT -> "TINYINT";
            case VARCHAR -> "VARCHAR(" + column.width() + ")";
            case DECIMAL -> "DECIMAL(" + column.width() + "," + column.scale() + ")";
            case DATETIME -> "DATETIME";
            case LONGTEXT -> "LONGTEXT";
            case CREATED_AT -> "TIMESTAMP";
        };
        return column.name() + " " + type + " NOT NULL" + (column.identity() ? " AUTO_INCREMENT" : "")
                + (column.defaultLiteral() == null ? "" : " DEFAULT " + column.defaultLiteral());
    }

    private static String postgresColumn(SummaryTableSpec.Column column) {
        String type = switch (column.type()) {
            case BIGINT -> "BIGINT";
            case INT -> "INTEGER";
            case TINYINT -> "SMALLINT";
            case VARCHAR -> "VARCHAR(" + column.width() + ")";
            case DECIMAL -> "NUMERIC(" + column.width() + "," + column.scale() + ")";
            case DATETIME -> "TIMESTAMP";               // WITHOUT time zone: the tenant's wall clock, as written
            case LONGTEXT -> "TEXT";
            case CREATED_AT -> "TIMESTAMPTZ";           // an instant, set by the server
        };
        if (column.identity()) {
            return column.name() + " " + type + " GENERATED BY DEFAULT AS IDENTITY";    // an identity is NOT NULL by itself
        }
        return column.name() + " " + type + " NOT NULL" + (column.defaultLiteral() == null ? "" : " DEFAULT " + column.defaultLiteral());
    }
}
