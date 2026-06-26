package com.telcobright.summary.runtime.internal;

import com.telcobright.summary.engine.spi.ColumnDef;
import com.telcobright.summary.engine.spi.SummaryRow;
import com.telcobright.summary.engine.spi.SummaryStore;
import com.telcobright.summary.engine.spi.SummaryStoreException;
import com.telcobright.summary.engine.spi.WindowSchema;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.StringJoiner;

/**
 * The JDBC {@link SummaryStore} over the ONE transaction-bound connection the batch owns. It LOADs all rows
 * for the involved buckets in a single query and maps them (carrying their DB id), and runs the engine's
 * INSERT/UPDATE/DELETE segments. It never commits or rolls back — the {@link JdbcUnitOfWork} does.
 */
final class JdbcSummaryStore implements SummaryStore {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Connection connection;

    JdbcSummaryStore(Connection connection) {
        this.connection = connection;
    }

    @Override
    public List<SummaryRow> load(WindowSchema schema, Collection<LocalDateTime> buckets) {
        if (buckets.isEmpty()) {
            return List.of();
        }
        String sql = selectByBuckets(schema, buckets);
        List<SummaryRow> rows = new ArrayList<>();
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(mapRow(schema, rs));
            }
        } catch (SQLException e) {
            throw new SummaryStoreException("load failed for " + schema.table(), e);
        }
        return rows;
    }

    @Override
    public int executeNonQuery(String sql) {
        try (Statement st = connection.createStatement()) {
            return sumAffected(st, sql);
        } catch (SQLException e) {
            throw new SummaryStoreException("write failed: " + truncate(sql), e);
        }
    }

    private static String selectByBuckets(WindowSchema schema, Collection<LocalDateTime> buckets) {
        StringJoiner cols = new StringJoiner(",");
        cols.add("id");
        schema.columns().forEach(c -> cols.add(c.name()));
        StringJoiner in = new StringJoiner(",");
        buckets.forEach(b -> in.add("'" + DATETIME.format(b) + "'"));
        return "select " + cols + " from " + schema.table() + " where " + schema.bucketColumn() + " in (" + in + ")";
    }

    private static SummaryRow mapRow(WindowSchema schema, ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        LinkedHashMap<String, Object> key = new LinkedHashMap<>();
        LinkedHashMap<String, BigDecimal> counters = new LinkedHashMap<>();
        for (ColumnDef c : schema.columns()) {
            if (c.isCounter()) {
                BigDecimal v = rs.getBigDecimal(c.name());
                counters.put(c.name(), v == null ? BigDecimal.ZERO : v);
            } else {
                key.put(c.name(), readKey(rs, c));
            }
        }
        return new SummaryRow(id, key, counters);
    }

    private static Object readKey(ResultSet rs, ColumnDef c) throws SQLException {
        return switch (c.type()) {
            case STRING -> rs.getString(c.name());
            case DATETIME -> rs.getObject(c.name(), LocalDateTime.class);
            case DECIMAL -> rs.getBigDecimal(c.name());
            case INT, LONG -> {
                long v = rs.getLong(c.name());
                yield rs.wasNull() ? null : v;
            }
        };
    }

    /** Run one statement or a {@code ;}-joined segment, summing every update count (multi-statement aware). */
    private static int sumAffected(Statement st, String sql) throws SQLException {
        int total = 0;
        boolean isResultSet = st.execute(sql);
        do {
            if (!isResultSet) {
                int affected = st.getUpdateCount();
                if (affected == -1) {
                    break;
                }
                total += affected;
            }
            isResultSet = st.getMoreResults();
        } while (isResultSet || st.getUpdateCount() != -1);
        return total;
    }

    private static String truncate(String sql) {
        return sql.length() <= 200 ? sql : sql.substring(0, 200) + "…";
    }
}
