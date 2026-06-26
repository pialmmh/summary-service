package com.telcobright.summary.runtime.internal;

import com.telcobright.summary.engine.spi.RowMapper;
import com.telcobright.summary.engine.spi.SummaryStore;
import com.telcobright.summary.engine.spi.SummaryStoreException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;

/**
 * The JDBC {@link SummaryStore} over the ONE transaction-bound connection the batch owns. It LOADs all rows for
 * the involved buckets in a single query (selecting id + the entity's insert columns) and maps them to typed
 * entities via the bean's mapper, and runs the engine's INSERT/UPDATE/DELETE segments. It never commits or
 * rolls back — the {@link JdbcUnitOfWork} does.
 */
final class JdbcSummaryStore implements SummaryStore {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Connection connection;

    JdbcSummaryStore(Connection connection) {
        this.connection = connection;
    }

    @Override
    public <T> List<T> load(String table, String insertColumnsCsv, String bucketColumn,
                            Collection<LocalDateTime> buckets, RowMapper<T> mapper) {
        if (buckets.isEmpty()) {
            return List.of();
        }
        String sql = "select id," + insertColumnsCsv + " from " + table
                + " where " + bucketColumn + " in (" + bucketLiterals(buckets) + ")";
        List<T> rows = new ArrayList<>();
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(mapper.map(rs));
            }
        } catch (SQLException e) {
            throw new SummaryStoreException("load failed for " + table, e);
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

    private static String bucketLiterals(Collection<LocalDateTime> buckets) {
        StringJoiner in = new StringJoiner(",");
        buckets.forEach(b -> in.add("'" + DATETIME.format(b) + "'"));
        return in.toString();
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
