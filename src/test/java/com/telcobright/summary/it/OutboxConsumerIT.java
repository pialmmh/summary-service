package com.telcobright.summary.it;

import com.telcobright.summary.bean.spi.SqlDialect;
import com.telcobright.summary.testkit.CdrTestSupport;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The outbox consumer's contract ({@link OutboxConsumerContract}) on MySQL — the tests this class always held, now
 * shared word for word with PostgreSQL; the fixture is the one it always had (a throwaway database, the outbox and
 * the voice tables made by hand as production has them). SELF-SKIPS if MySQL is unreachable; password via
 * {@code -Dsummary.it.mysql.password=…} (no credential in git). The lab: {@code tools/lab/pg-lab.sh mysql}, then
 * {@code -Dsummary.it.mysql.url=jdbc:mysql://127.0.0.1:7633/?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true}.
 */
class OutboxConsumerIT extends OutboxConsumerContract {

    private static final String SERVER_URL = System.getProperty("summary.it.mysql.url",
            "jdbc:mysql://127.0.0.1:7633/?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true");
    private static final String USER = System.getProperty("summary.it.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("summary.it.mysql.password", "");
    /** The throwaway database; {@code -Dsummary.it.mysql.db=…} lets two runs share one server without sharing tables. */
    private static final String DB = System.getProperty("summary.it.mysql.db", "summary_it");

    @Override
    protected SqlDialect dialect() {
        return SqlDialect.MYSQL;
    }

    @Override
    protected String schemaName() {
        return DB;
    }

    @Override
    protected DataSource freshSchema() {
        Connection probe = tryConnect(SERVER_URL);
        assumeTrue(probe != null, "MySQL not reachable — skipping integration test");
        try (probe) {
            createSchema(probe);
        } catch (SQLException e) {
            throw new IllegalStateException("could not prepare the integration schema", e);
        }
        return new DriverManagerDataSource(SERVER_URL.replace("/?", "/" + DB + "?"));
    }

    @Override
    protected Connection billingConnection() throws SQLException {
        return dbConnection();
    }

    @Override
    protected Connection dbConnection() throws SQLException {
        return DriverManager.getConnection(SERVER_URL.replace("/?", "/" + DB + "?"), USER, PASSWORD);
    }

    @Override
    protected DataSource theServicesOwnPool(int size) {
        return com.telcobright.summary.runtime.internal.TestPools.pool(SqlDialect.MYSQL, SERVER_URL.replace("/?", "/" + DB + "?"), USER, PASSWORD, size);
    }

    /** Every OTHER session in the test's database is killed by the server (as a restart ends them). */
    @Override
    protected int endTheServicesSessions() throws SQLException {
        int ended = 0;
        try (Connection admin = DriverManager.getConnection(SERVER_URL, USER, PASSWORD);
             java.sql.Statement st = admin.createStatement();
             java.sql.ResultSet sessions = st.executeQuery("select id from information_schema.processlist where id <> connection_id() and db = '" + DB + "'")) {
            List<Long> ids = new java.util.ArrayList<>();
            while (sessions.next()) ids.add(sessions.getLong(1));
            for (long id : ids) {
                try (java.sql.Statement kill = admin.createStatement()) {
                    kill.execute("kill " + id);
                    ended++;
                }
            }
        }
        return ended;
    }

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
        exec(conn, "drop table if exists summary_affected_dlq");
        exec(conn, "drop table if exists " + DAY_TABLE);
        exec(conn, "drop table if exists " + HOUR_TABLE);
        exec(conn, "create table summary_affected (id bigint not null auto_increment, entity_type varchar(32) not null,"
                + " op enum('add','subtract') not null default 'add',"
                + " data longtext not null, primary key(id), key ix_entity(entity_type,id)) engine=innodb default charset=utf8mb4");
        exec(conn, "drop table if exists sum_voice_day_30");
        exec(conn, "drop table if exists sum_voice_hr_30");
        exec(conn, "drop table if exists sum_ad_day_30");
        exec(conn, "drop table if exists sum_ad_hr_30");
        exec(conn, "drop table if exists sum_chargeable_day");
        exec(conn, "drop table if exists sum_chargeable_hr");
        exec(conn, "create table summary_offset (entity_type varchar(32) not null, bean_name varchar(64) not null,"
                + " last_offset bigint not null default 0, primary key(entity_type,bean_name)) engine=innodb default charset=utf8mb4");
        exec(conn, "create table summary_affected_dlq (id bigint not null auto_increment, entity_type varchar(32) not null,"
                + " bean_name varchar(64) not null, outbox_id bigint not null, data longtext not null,"
                + " error varchar(512) not null, created_at timestamp not null default current_timestamp,"
                + " primary key(id), key ix_bean(entity_type,bean_name,outbox_id)) engine=innodb default charset=utf8mb4");
        exec(conn, createSumVoiceTable());
        exec(conn, "create table " + HOUR_TABLE + " like " + DAY_TABLE);
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
