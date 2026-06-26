package com.telcobright.summary.it;

import com.telcobright.summary.beans.cdr.CdrVoiceSummaryBean;
import com.telcobright.summary.beans.cdr.RatedCdrEvent;
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
 * INTEGRATION (local lxc MySQL): the WHOLE batch is ONE transaction owned by {@link BatchRunner}. Success
 * commits every window together; a mid-batch failure rolls EVERYTHING back (the already-written day row is
 * undone when the hour write fails). Mirrors billing-core's CdrBatchAtomicityTests. SELF-SKIPS if MySQL is
 * unreachable — runs in the {@code verify} phase (failsafe).
 *
 * <p>Targets the local dev MySQL (127.0.0.1:3306, root) per the house convention. NO password is baked into
 * git — supply it at run time with {@code -Dsummary.it.mysql.password=…} (and optionally
 * {@code -Dsummary.it.mysql.url/user}). With no password the connect fails and the test SELF-SKIPS.
 */
class CdrBatchAtomicityIT {

    private static final String SERVER_URL = System.getProperty("summary.it.mysql.url",
            "jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true");
    private static final String USER = System.getProperty("summary.it.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("summary.it.mysql.password", "");
    private static final String DB = "summary_it";

    private final CdrVoiceSummaryBean bean = CdrTestSupport.bean();
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
    void batch_commits_every_window_atomically_on_success() {
        List<RatedCdrEvent> batch = List.of(
                CdrTestSupport.sg10Call(CdrTestSupport.at(2026, 6, 19, 14, 30)),
                CdrTestSupport.sg10Call(CdrTestSupport.at(2026, 6, 19, 15, 45)));   // same day, two hours

        runner.run(bean, batch);

        assertEquals(1, count("sum_voice_day_03"), "two calls, one day window -> one merged day row");
        assertEquals(2, count("sum_voice_hr_03"), "two distinct hour windows -> two hour rows");
        assertEquals(2, dayTotalCalls(), "day row counts both calls");
    }

    @Test
    void batch_rolls_back_entirely_when_a_later_window_write_fails() {
        execOnDb("drop table sum_voice_hr_03");   // the hour write (AFTER the day write) will fail

        assertThrows(RuntimeException.class, () ->
                runner.run(bean, List.of(CdrTestSupport.sg10Call(CdrTestSupport.at(2026, 6, 19, 14, 30)))));

        assertEquals(0, count("sum_voice_day_03"), "the already-written day row was rolled back");
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
        for (String table : new String[]{"sum_voice_day_03", "sum_voice_hr_03"}) {
            exec(conn, "drop table if exists " + table);
            exec(conn, createTable(table));
        }
    }

    private static String createTable(String table) {
        return "create table " + table + " ("
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
                + "tup_starttime datetime not null,"
                + "totalcalls bigint not null default 0, connectedcalls bigint not null default 0,"
                + "successfulcalls bigint not null default 0,"
                + "actualduration decimal(18,6) not null default 0, roundedduration decimal(18,6) not null default 0,"
                + "duration1 decimal(18,6) not null default 0,"
                + "customercost decimal(18,6) not null default 0, suppliercost decimal(18,6) not null default 0,"
                + "tax1 decimal(18,6) not null default 0, tax2 decimal(18,6) not null default 0,"
                + "primary key (id), key ix_starttime (tup_starttime)) engine=innodb default charset=utf8mb4";
    }

    private long count(String table) {
        return queryLong("select count(*) from " + table);
    }

    private long dayTotalCalls() {
        return queryLong("select coalesce(sum(totalcalls),0) from sum_voice_day_03");
    }

    private long queryLong(String sql) {
        try (Connection c = DriverManager.getConnection(SERVER_URL.replace("/?", "/" + DB + "?"), USER, PASSWORD);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private void execOnDb(String sql) {
        try (Connection c = DriverManager.getConnection(SERVER_URL.replace("/?", "/" + DB + "?"), USER, PASSWORD)) {
            exec(c, sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
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
