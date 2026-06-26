package com.telcobright.summary.it;

import com.telcobright.summary.beans.cdr.CdrSummary;
import com.telcobright.summary.beans.cdr.CdrSummaryBean;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.runtime.api.BatchRunner;
import com.telcobright.summary.runtime.internal.JdbcUnitOfWorkFactory;
import com.telcobright.summary.testkit.CdrTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * INTEGRATION (local lxc MySQL): the WHOLE batch is ONE transaction owned by {@link BatchRunner} over the real
 * 47-column sum_voice schema. Success commits the merged window; an atomic multi-row INSERT that fails on one
 * bad value persists NOTHING (the good row is not left behind). SELF-SKIPS if MySQL is unreachable.
 *
 * <p>Local dev DB per the house convention (127.0.0.1:3306, root). NO password is baked into git — supply it
 * with {@code -Dsummary.it.mysql.password=…}; with none the connect fails and the test skips.
 */
class CdrBatchAtomicityIT {

    private static final String SERVER_URL = System.getProperty("summary.it.mysql.url",
            "jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true");
    private static final String USER = System.getProperty("summary.it.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("summary.it.mysql.password", "");
    private static final String DB = "summary_it";
    private static final String TABLE = CdrTestSupport.DAY_TABLE;

    private final CdrSummaryBean bean = CdrTestSupport.dailyBean();
    private BatchRunner runner;

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
        runner = new BatchRunner(new JdbcUnitOfWorkFactory(dataSource), new SummaryEngine(), 1000);
    }

    @Test
    void batch_merges_and_commits_against_real_mysql() {
        List<CdrSummary> batch = List.of(
                CdrTestSupport.daySummary(CdrTestSupport.at(2026, 6, 19, 10, 0)),
                CdrTestSupport.daySummary(CdrTestSupport.at(2026, 6, 19, 15, 0)),   // same day -> merges
                CdrTestSupport.daySummary(CdrTestSupport.at(2026, 6, 20, 9, 0)));   // another day -> new row

        runner.run(bean, batch);

        assertEquals(2, count(TABLE), "two day windows -> two rows");
        assertEquals(3, sum("totalcalls"), "three calls counted across the two rows");
        assertEquals(3, sum("connectedcallsCC"), "connectedcallsCC summed in Merge");
    }

    @Test
    void a_failing_multi_row_insert_persists_nothing() {
        execOnDb("alter table " + TABLE + " modify tup_destinationId varchar(2) not null default ''");

        // two distinct rows in ONE multi-row insert; the second destination ('99999') overflows varchar(2)
        List<CdrSummary> batch = List.of(
                CdrTestSupport.daySummary(CdrTestSupport.at(2026, 6, 19, 10, 0), 7),
                CdrTestSupport.daySummary(CdrTestSupport.at(2026, 6, 19, 10, 0), 99999));

        assertThrows(RuntimeException.class, () -> runner.run(bean, batch));

        assertEquals(0, count(TABLE), "the whole batch rolled back — not even the good row persisted");
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
        exec(conn, "drop table if exists " + TABLE);
        exec(conn, createTable());
    }

    private static String createTable() {
        return "create table " + TABLE + " ("
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

    private long count(String table) {
        return queryLong("select count(*) from " + table);
    }

    private long sum(String column) {
        return queryLong("select coalesce(sum(" + column + "),0) from " + TABLE);
    }

    private long queryLong(String sql) {
        try (Connection c = dbConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private void execOnDb(String sql) {
        try (Connection c = dbConnection()) {
            exec(c, sql);
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
