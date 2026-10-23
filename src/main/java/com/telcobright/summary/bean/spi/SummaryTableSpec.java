package com.telcobright.summary.bean.spi;

import java.util.ArrayList;
import java.util.List;

/**
 * A summary table described ONCE, for every engine: its columns in order, its key, its indexes, and the bucket
 * column a partitioned engine partitions by. A bean hands this out ({@link SummaryBean#tableSpec()}); the store's
 * edge renders it for the engine it runs on ({@link TableDdl}) — so MySQL and PostgreSQL always get the same
 * columns, the same widths and the same key, and only the spelling differs.
 *
 * <p>Every column is NOT NULL (a summary row has no unknown): a dimension defaults to {@code ''} or 0, a measure
 * to 0, the bucket has no default.
 *
 * <p>A table whose shape this service OWNS may be {@link Builder#keptUpToDate() kept up to date}: a table of that
 * name made by an earlier version is then brought up to this description at its first use — a column it lacks is
 * added, a text column that is narrower is widened ({@link TableDdl#bringUpToDate}). A table that is NOT marked is
 * never altered: a summary table another system also writes (the voice deployment's legacy tables) stays as it is.
 */
public final class SummaryTableSpec {

    /** The column types a summary table uses, by their MySQL names (the engine the tables were born on). */
    public enum Type {
        BIGINT, INT, TINYINT, VARCHAR, DECIMAL, DATETIME, LONGTEXT,
        /** A point in time set by the database when the row is written ({@code created_at}). */
        CREATED_AT
    }

    /**
     * One column. {@code width} is the length of a VARCHAR or the precision of a DECIMAL; {@code scale} the
     * decimals of a DECIMAL; {@code defaultLiteral} the SQL literal of its default, or null for none;
     * {@code identity} = the database numbers it.
     */
    public record Column(String name, Type type, int width, int scale, String defaultLiteral, boolean identity) {
    }

    /**
     * One index of the table. {@code mysqlPrefixChars} greater than 0 indexes only the FIRST characters of each of its
     * text columns on MySQL ({@code KEY ix (col(N))}): InnoDB's whole key is 3072 bytes, so a utf8mb4 column cannot be
     * indexed past 768 characters and a 1000-wide one not at all. PostgreSQL has no prefix index and ignores the number:
     * it indexes the column whole, which its btree takes while a value stays well under its own row limit.
     */
    public record Index(String name, List<String> columns, int mysqlPrefixChars) {

        public Index(String name, List<String> columns) {
            this(name, columns, 0);
        }
    }

    private final String name;
    private final List<Column> columns;
    private final List<String> primaryKey;
    private final List<Index> indexes;
    private final String partitionColumn;
    private final boolean keptUpToDate;

    private SummaryTableSpec(Builder builder) {
        this.name = builder.name;
        this.columns = List.copyOf(builder.columns);
        this.primaryKey = List.copyOf(builder.primaryKey);
        this.indexes = List.copyOf(builder.indexes);
        this.partitionColumn = builder.partitionColumn;
        this.keptUpToDate = builder.keptUpToDate;
    }

    public static Builder table(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public List<Column> columns() {
        return columns;
    }

    public List<String> primaryKey() {
        return primaryKey;
    }

    public List<Index> indexes() {
        return indexes;
    }

    /** The bucket column an engine that partitions summary tables (MySQL) ranges by, one partition a day; null = never partitioned. */
    public String partitionColumn() {
        return partitionColumn;
    }

    /** The columns' names, in order. */
    public List<String> columnNames() {
        return columns.stream().map(Column::name).toList();
    }

    /** True when an EXISTING table of this name is brought up to this description at its first use. */
    public boolean keptUpToDate() {
        return keptUpToDate;
    }

    public static final class Builder {
        private final String name;
        private final List<Column> columns = new ArrayList<>();
        private List<String> primaryKey = List.of();
        private final List<Index> indexes = new ArrayList<>();
        private String partitionColumn;
        private boolean keptUpToDate;

        private Builder(String name) {
            this.name = name;
        }

        /** The row id the database assigns. */
        public Builder identity(String column) {
            columns.add(new Column(column, Type.BIGINT, 0, 0, null, true));
            return this;
        }

        public Builder bigint(String column) {
            columns.add(new Column(column, Type.BIGINT, 0, 0, "0", false));
            return this;
        }

        /** A BIGINT with no default (a value the writer always gives). */
        public Builder bigintRequired(String column) {
            columns.add(new Column(column, Type.BIGINT, 0, 0, null, false));
            return this;
        }

        public Builder integer(String column) {
            columns.add(new Column(column, Type.INT, 0, 0, "0", false));
            return this;
        }

        public Builder tinyInt(String column) {
            columns.add(new Column(column, Type.TINYINT, 0, 0, "0", false));
            return this;
        }

        /** A text dimension, default {@code ''}. */
        public Builder varchar(String column, int width) {
            columns.add(new Column(column, Type.VARCHAR, width, 0, "''", false));
            return this;
        }

        /** A text the writer always gives (no default). */
        public Builder varcharRequired(String column, int width) {
            columns.add(new Column(column, Type.VARCHAR, width, 0, null, false));
            return this;
        }

        public Builder decimal(String column, int precision, int scale) {
            columns.add(new Column(column, Type.DECIMAL, precision, scale, "0", false));
            return this;
        }

        /** A wall-clock time without a zone and without a default: the window's bucket. */
        public Builder datetime(String column) {
            columns.add(new Column(column, Type.DATETIME, 0, 0, null, false));
            return this;
        }

        public Builder longText(String column) {
            columns.add(new Column(column, Type.LONGTEXT, 0, 0, null, false));
            return this;
        }

        public Builder createdAt(String column) {
            columns.add(new Column(column, Type.CREATED_AT, 0, 0, "CURRENT_TIMESTAMP", false));
            return this;
        }

        public Builder primaryKey(String... keyColumns) {
            this.primaryKey = List.of(keyColumns);
            return this;
        }

        public Builder index(String indexName, String... indexColumns) {
            indexes.add(new Index(indexName, List.of(indexColumns)));
            return this;
        }

        /** An index that takes only the first {@code prefixChars} characters of each of its text columns on MySQL ({@link Index}). */
        public Builder indexOnPrefix(String indexName, int prefixChars, String... indexColumns) {
            indexes.add(new Index(indexName, List.of(indexColumns), prefixChars));
            return this;
        }

        /** Partitioned by day on this column where the engine partitions summary tables. */
        public Builder partitionedByDayOn(String bucketColumn) {
            this.partitionColumn = bucketColumn;
            return this;
        }

        /**
         * This service owns the table's shape: an existing table of this name is brought up to the description —
         * a missing column added, a narrower text column widened. Never for a table another system also writes.
         */
        public Builder keptUpToDate() {
            this.keptUpToDate = true;
            return this;
        }

        public SummaryTableSpec build() {
            if (columns.isEmpty() || primaryKey.isEmpty()) {
                throw new IllegalStateException("table " + name + " needs columns and a primary key");
            }
            return new SummaryTableSpec(this);
        }
    }
}
