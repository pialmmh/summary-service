package com.telcobright.summary.it;

import com.telcobright.summary.bean.spi.SqlDialect;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.outbox.api.OutboxReader;
import com.telcobright.summary.outbox.internal.OutboxCodec;
import com.telcobright.summary.registry.api.SummaryBeanRegistry;
import com.telcobright.summary.runtime.internal.JdbcUnitOfWorkFactory;
import com.telcobright.summary.runtime.internal.TestPools;
import com.telcobright.summary.runtime.spi.UnitOfWork;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.tenancy.api.TenantWatcher;
import com.telcobright.summary.testkit.Await;
import io.agroal.api.AgroalDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Brief S6 on MySQL: there a tenant is a DATABASE of the one server ({@code telcobright}, {@code res_233}), and
 * one process serves the tree's databases through ONE pool — each unit of work enters its own ({@code USE}). The
 * voice deployments get the same rule as the wifi one; this holds it on a real server with two databases.
 */
class MySqlTreeIT {

    private static final String SERVER_URL = System.getProperty("summary.it.mysql.url",
            "jdbc:mysql://127.0.0.1:7633/?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true");
    private static final String USER = System.getProperty("summary.it.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("summary.it.mysql.password", "");
    private static final String BASE = System.getProperty("summary.it.mysql.db", "summary_it");
    private static final String ROOT = BASE + "_root", RESELLER = BASE + "_res_233";
    private static final String DAILY = "dailyAdSummary";

    private AgroalDataSource pool;
    private SummaryBeanRegistry registry;

    @BeforeEach
    void twoDatabasesOneServer() throws SQLException {
        assumeTrue(serverAnswers(), "MySQL not reachable — skipping integration test");
        try (Connection server = DriverManager.getConnection(SERVER_URL, USER, PASSWORD); Statement st = server.createStatement()) {
            for (String db : List.of(ROOT, RESELLER)) {
                st.execute("drop database if exists " + db);
                st.execute("create database " + db + " character set utf8mb4");
            }
        }
        System.setProperty("summary.ddl.partition-start", "2026-09-01");
        System.setProperty("summary.ddl.partition-days", "60");
    }

    @AfterEach
    void stop() throws SQLException {
        System.clearProperty("summary.ddl.partition-start");
        System.clearProperty("summary.ddl.partition-days");
        if (registry != null) registry.stopAll();
        if (pool != null) pool.close();
        try (Connection c = DriverManager.getConnection(SERVER_URL, USER, PASSWORD); Statement st = c.createStatement()) {
            st.execute("drop database if exists " + ROOT);
            st.execute("drop database if exists " + RESELLER);
        } catch (SQLException ignored) {
            // the lab went away: nothing to clean
        }
    }

    @Test
    void one_process_serves_two_tenant_databases_through_one_pool_each_with_its_own_rows() throws Exception {
        pool = TestPools.pool(SqlDialect.MYSQL, SERVER_URL, USER, PASSWORD, 2);            // the URL names NO database
        OutboxReader reader = new OutboxReader(new JdbcUnitOfWorkFactory(pool, SqlDialect.MYSQL), new SummaryEngine(), 1000, 1, 8);
        registry = new SummaryBeanRegistry(reader, 1);
        registry.register(AdTestSupport.dailyBean());
        TenantWatcher watcher = new TenantWatcher(registry, ROOT, () -> List.of(ROOT, RESELLER));

        assertEquals(Set.of(ROOT, RESELLER), watcher.reloadNow());                         // the tables of each are made at its first use
        java.time.LocalDateTime t = AdTestSupport.at(2026, 9, 29, 10, 0);
        billingWrites(RESELLER, OutboxCodec.encode(AdTestSupport.batchOf(AdTestSupport.leafView(t))));
        billingWrites(ROOT, OutboxCodec.encode(AdTestSupport.batchOf(AdTestSupport.rootView(t))));

        assertTrue(Await.until(() -> offset(ROOT) == 1 && offset(RESELLER) == 1, 30_000), "a bookmark per (database, bean)");
        assertEquals(RESELLER + "|61|0.500000", row(RESELLER), "the advertiser's record, in the reseller's database, named after it");
        assertEquals(ROOT + "|44|0.400000", row(ROOT), "the reseller's record, in the root's database");
    }

    @Test
    void one_pooled_connection_serves_database_after_database_and_goes_home_when_none_is_named() {
        pool = TestPools.pool(SqlDialect.MYSQL, SERVER_URL.replace("/?", "/" + ROOT + "?"), USER, PASSWORD, 1);   // ONE connection, the URL's database is the root's
        JdbcUnitOfWorkFactory factory = new JdbcUnitOfWorkFactory(pool, SqlDialect.MYSQL);
        OutboxReader reader = new OutboxReader(factory, new SummaryEngine(), 1000, 1, 8);
        reader.ensureInfraTables(ROOT);
        reader.ensureInfraTables(RESELLER);
        try (UnitOfWork inReseller = factory.begin(RESELLER)) {
            inReseller.outbox().advanceOffset("cdr", "onlyInReseller", 9);
            inReseller.commit();
            assertEquals(RESELLER, inReseller.schema());
        }

        try (UnitOfWork home = factory.begin(null)) {                                     // the same connection, last left in the reseller's database
            assertEquals(ROOT, home.schema(), "a unit of work that names none runs in the URL's own database");
            assertEquals(Set.of(), home.outbox().bookmarkedBeans("cdr"), "it went home: the reseller's bookmark is not seen here");
            home.commit();
        }
        try (UnitOfWork again = factory.begin(RESELLER)) {
            assertEquals(Set.of("onlyInReseller"), again.outbox().bookmarkedBeans("cdr"));
            again.commit();
        }
    }

    private static boolean serverAnswers() {
        try (Connection probe = DriverManager.getConnection(SERVER_URL, USER, PASSWORD)) {
            return true;
        } catch (SQLException unreachable) {
            return false;
        }
    }

    private static void billingWrites(String database, String data) throws SQLException {
        try (Connection c = connect(database); PreparedStatement ps = c.prepareStatement("insert into summary_affected(entity_type, op, data) values('cdr', 'add', ?)")) {
            ps.setString(1, data);
            ps.executeUpdate();
        }
    }

    private static long offset(String database) {
        try (Connection c = connect(database); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select coalesce(max(last_offset), 0) from summary_offset where bean_name = '" + DAILY + "'")) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String row(String database) throws SQLException {
        try (Connection c = connect(database); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select concat_ws('|', tup_tenant, tup_partnerid, chargedamount) from sum_ad_day_30")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(SERVER_URL.replace("/?", "/" + database + "?"), USER, PASSWORD);
    }
}
