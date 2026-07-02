package com.telcobright.summary.runtime.internal;

import com.telcobright.summary.engine.spi.SummaryStoreException;
import com.telcobright.summary.outbox.spi.OutboxRow;
import com.telcobright.summary.outbox.spi.OutboxStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;

/**
 * JDBC {@link OutboxStore} over the batch's transaction-bound connection. All reads/writes are parameterized.
 * It never commits or rolls back — the {@link JdbcUnitOfWork} owns the transaction, so a bean's offset advance
 * commits atomically with its summaries.
 */
final class JdbcOutboxStore implements OutboxStore {

    private final Connection connection;

    JdbcOutboxStore(Connection connection) {
        this.connection = connection;
    }

    @Override
    public long readOffset(String entityType, String beanName) {
        // FOR UPDATE (dotnet A1c belt-and-braces): under the ratified single-active topology the row lock is
        // free; if a second instance ever starts by accident, its drain blocks here instead of double-counting
        String sql = "select last_offset from summary_offset where entity_type=? and bean_name=? for update";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, entityType);
            ps.setString(2, beanName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new SummaryStoreException("readOffset failed for " + beanName, e);
        }
    }

    @Override
    public void initOffsetAtHead(String entityType, String beanName) {
        String sql = "insert ignore into summary_offset(entity_type,bean_name,last_offset) "
                + "select ?, ?, coalesce(max(id),0) from summary_affected where entity_type=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, entityType);
            ps.setString(2, beanName);
            ps.setString(3, entityType);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new SummaryStoreException("initOffsetAtHead failed for " + beanName, e);
        }
    }

    @Override
    public List<OutboxRow> readAfter(String entityType, long afterId, int limit) {
        String sql = "select id, op, data from summary_affected where entity_type=? and id>? order by id asc limit ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, entityType);
            ps.setLong(2, afterId);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<OutboxRow> rows = new ArrayList<>();
                while (rs.next()) {
                    String op = rs.getString("op");
                    rows.add(new OutboxRow(rs.getLong("id"), op == null ? "add" : op, rs.getString("data")));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new SummaryStoreException("readAfter failed for " + entityType, e);
        }
    }

    @Override
    public void advanceOffset(String entityType, String beanName, long newOffset) {
        String sql = "insert into summary_offset(entity_type,bean_name,last_offset) values(?,?,?) "
                + "on duplicate key update last_offset=?";   // parameter twice, not VALUES() (deprecated in MySQL 8.0.20+)
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, entityType);
            ps.setString(2, beanName);
            ps.setLong(3, newOffset);
            ps.setLong(4, newOffset);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new SummaryStoreException("advanceOffset failed for " + beanName, e);
        }
    }

    @Override
    public long minOffset(String entityType, Collection<String> beanNames) {
        if (beanNames.isEmpty()) {
            return 0L;
        }
        StringJoiner placeholders = new StringJoiner(",");
        beanNames.forEach(b -> placeholders.add("?"));
        String sql = "select count(*), coalesce(min(last_offset),0) from summary_offset "
                + "where entity_type=? and bean_name in (" + placeholders + ")";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, entityType);
            int i = 2;
            for (String bean : beanNames) {
                ps.setString(i++, bean);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0L;
                }
                long present = rs.getLong(1);
                long min = rs.getLong(2);
                // a registered bean with NO row yet is effectively at offset 0 -> nothing is safe to delete
                return present < beanNames.size() ? 0L : min;
            }
        } catch (SQLException e) {
            throw new SummaryStoreException("minOffset failed for " + entityType, e);
        }
    }

    @Override
    public void deadLetter(String entityType, String beanName, OutboxRow row, String error) {
        String sql = "insert into summary_affected_dlq(entity_type,bean_name,outbox_id,data,error) values(?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, entityType);
            ps.setString(2, beanName);
            ps.setLong(3, row.id());
            ps.setString(4, row.data());
            ps.setString(5, error);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new SummaryStoreException("deadLetter failed for " + beanName + " row " + row.id(), e);
        }
    }

    @Override
    public int deleteUpTo(String entityType, long maxIdInclusive) {
        if (maxIdInclusive <= 0) {
            return 0;
        }
        String sql = "delete from summary_affected where entity_type=? and id<=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, entityType);
            ps.setLong(2, maxIdInclusive);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new SummaryStoreException("deleteUpTo failed for " + entityType, e);
        }
    }
}
