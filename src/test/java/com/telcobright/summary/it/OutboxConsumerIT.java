package com.telcobright.summary.it;

import com.telcobright.summary.summarybeans.call.internal.CallSummaryBean;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.outbox.api.OutboxReader;
import com.telcobright.summary.runtime.internal.JdbcUnitOfWorkFactory;
import com.telcobright.summary.testkit.CdrTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * INTEGRATION (local lxc MySQL): the full outbox consumer over real MySQL. Seeds {@code summary_affected} with
 * base64(gzip(JSON {Cdr,Customer})) rows (what billing writes), runs the daily bean's drain, and verifies the
 * summaries land, the per-bean {@code last_offset} advances, and a re-drain is a no-op (exactly-once). SELF-SKIPS
 * if MySQL is unreachable; password via {@code -Dsummary.it.mysql.password=…} (no credential in git).
 */
class OutboxConsumerIT {

    private static final String SERVER_URL = System.getProperty("summary.it.mysql.url",
            "jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true");
    private static final String USER = System.getProperty("summary.it.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("summary.it.mysql.password", "");
    private static final String DB = "summary_it";
    private static final String DAY_TABLE = CdrTestSupport.DAY_TABLE;

    private final CallSummaryBean bean = CdrTestSupport.dailyBean();
    private OutboxReader reader;

    @BeforeEach
    void setUp() {
        Connection probe = tryConnect(SERVER_URL);
        assumeTrue(probe != null, "MySQL not reachable — skipping integration test");
        try (probe) {
            createSchema(probe);
        } catch (SQLException e) {
            throw new IllegalStateException("could not prepare the integration schema", e);
        }
        DataSource dataSource = new DriverManagerDataSource(SERVER_URL.replace("/?", "/" + DB + "?"));
        reader = new OutboxReader(new JdbcUnitOfWorkFactory(dataSource), new SummaryEngine(), 1000, 50);
    }

    @Test
    void drains_the_outbox_writes_summaries_advances_offset_and_is_exactly_once() {
        // row 1: two calls on the same day -> the daily bean merges them; row 2: another day
        seedOutbox(1, CdrTestSupport.encodedBatch(List.of(
                CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 10, 0)),
                CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 15, 0)))));
        seedOutbox(2, CdrTestSupport.encodedBatch(List.of(
                CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 20, 9, 0)))));

        int processed = reader.drain(bean);

        assertEquals(2, processed, "two outbox rows consumed");
        assertEquals(2, count(DAY_TABLE), "two day windows -> two summary rows");
        assertEquals(3, sumTotalCalls(), "three calls counted across the windows");
        assertEquals(2, offset("dailyCallSummary"), "last_offset advanced to the last row id");

        // re-drain: nothing new, no double-count
        int again = reader.drain(bean);
        assertEquals(0, again);
        assertEquals(2, count(DAY_TABLE));
        assertEquals(3, sumTotalCalls());
    }

    @Test
    void drains_a_week_of_outbox_rows_into_seven_day_windows() {
        // 7 outbox rows, one per day June 19..25, with day-index calls (1,2,…,7) -> 28 calls, 7 day windows
        int expectedCalls = 0;
        for (int i = 0; i < 7; i++) {
            int callsThatDay = i + 1;
            seedOutbox(i + 1, CdrTestSupport.encodedBatch(
                    CdrTestSupport.series(CdrTestSupport.at(2026, 6, 19 + i, 0, 0), 60, callsThatDay)));
            expectedCalls += callsThatDay;
        }

        int processed = reader.drain(bean);

        assertEquals(7, processed, "seven outbox rows consumed");
        assertEquals(7, count(DAY_TABLE), "seven distinct day windows");
        assertEquals(expectedCalls, sumTotalCalls(), "1+2+…+7 = 28 calls counted across the windows");
        assertEquals(7, offset("dailyCallSummary"), "last_offset advanced to the last row id");

        // re-drain is exactly-once
        assertEquals(0, reader.drain(bean));
        assertEquals(7, count(DAY_TABLE));
        assertEquals(expectedCalls, sumTotalCalls());
    }

    // ---- schema + helpers ----

    private static Connection tryConnect(String url) {
        try {
            return DriverManager.getConnection(url, USER, PASSWORD);
        } catch (SQLException e) {
            return null;
        }
    }

    private void createSchema(Connection conn) throws SQLException {
        exec(conn, "create database if not exists " + DB + " character set utf8mb4");
        exec(conn, "use " + DB);
        exec(conn, "drop table if exists summary_affected");
        exec(conn, "drop table if exists summary_offset");
        exec(conn, "drop table if exists " + DAY_TABLE);
        exec(conn, "create table summary_affected (id bigint not null auto_increment, entity_type varchar(32) not null,"
                + " data longtext not null, primary key(id), key ix_entity(entity_type,id)) engine=innodb default charset=utf8mb4");
        exec(conn, "create table summary_offset (entity_type varchar(32) not null, bean_name varchar(64) not null,"
                + " last_offset bigint not null default 0, primary key(entity_type,bean_name)) engine=innodb default charset=utf8mb4");
        exec(conn, createSumVoiceTable());
    }

    private static String createSumVoiceTable() {
        return "create table " + DAY_TABLE + " ("
                + "id bigint not null auto_increment,"
                + "tup_switchid int not null default 0, tup_inpartnerid int not null default 0,"
                + "tup_outpartnerid int not null default 0,"
                + "tup_incomingroute varchar(64) not null default '', tup_outgoingroute varchar(64) not null default '',"
                + "tup_customerrate decimal(18,6) not null default 0, tup_supplierrate decimal(18,6) not null default 0,"
                + "tup_incomingip varchar(64) not null default '', tup_outgoingip varchar(64) not null default '',"
                + "tup_countryorareacode varchar(32) not null default '',"
                + "tup_matchedprefixcustomer varchar(32) not null default '', tup_matchedprefixsupplier varchar(32) not null default '',"
                + "tup_sourceId varchar(32) not null default '', tup_destinationId varchar(32) not null default '',"
                + "tup_customercurrency varchar(16) not null default '', tup_suppliercurrency varchar(16) not null default '',"
                + "tup_tax1currency varchar(16) not null default '', tup_tax2currency varchar(16) not null default '',"
                + "tup_vatcurrency varchar(16) not null default '', tup_starttime datetime not null,"
                + "totalcalls bigint not null default 0, connectedcalls bigint not null default 0,"
                + "connectedcallsCC bigint not null default 0, successfulcalls bigint not null default 0,"
                + "actualduration decimal(18,6) not null default 0, roundedduration decimal(18,6) not null default 0,"
                + "duration1 decimal(18,6) not null default 0, duration2 decimal(18,6) not null default 0,"
                + "duration3 decimal(18,6) not null default 0, PDD decimal(18,6) not null default 0,"
                + "customercost decimal(18,6) not null default 0, suppliercost decimal(18,6) not null default 0,"
                + "tax1 decimal(18,6) not null default 0, tax2 decimal(18,6) not null default 0,"
                + "vat decimal(18,6) not null default 0,"
                + "intAmount1 int not null default 0, intAmount2 int not null default 0,"
                + "longAmount1 bigint not null default 0, longAmount2 bigint not null default 0,"
                + "longDecimalAmount1 decimal(18,6) not null default 0, longDecimalAmount2 decimal(18,6) not null default 0,"
                + "intAmount3 int not null default 0, longAmount3 bigint not null default 0,"
                + "longDecimalAmount3 decimal(18,6) not null default 0,"
                + "decimalAmount1 decimal(18,6) not null default 0, decimalAmount2 decimal(18,6) not null default 0,"
                + "decimalAmount3 decimal(18,6) not null default 0,"
                + "primary key (id), key ix_starttime (tup_starttime)) engine=innodb default charset=utf8mb4";
    }

    private void seedOutbox(long id, String data) {
        String sql = "insert into summary_affected(id, entity_type, data) values(?, 'cdr', ?)";
        try (Connection c = dbConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, data);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("seed failed", e);
        }
    }

    private long count(String table) {
        return queryLong("select count(*) from " + table);
    }

    private long sumTotalCalls() {
        return queryLong("select coalesce(sum(totalcalls),0) from " + DAY_TABLE);
    }

    private long offset(String beanName) {
        return queryLong("select coalesce(max(last_offset),0) from summary_offset where bean_name='" + beanName + "'");
    }

    private long queryLong(String sql) {
        try (Connection c = dbConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private Connection dbConnection() throws SQLException {
        return DriverManager.getConnection(SERVER_URL.replace("/?", "/" + DB + "?"), USER, PASSWORD);
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /** Minimal DataSource over DriverManager — only getConnection() is used by the unit of work. */
    private record DriverManagerDataSource(String url) implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, USER, PASSWORD);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
