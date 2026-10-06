package com.telcobright.summary.it;

import com.telcobright.summary.bean.spi.SqlDialect;
import com.telcobright.summary.bean.spi.SummaryTableSpec;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.outbox.api.OutboxReader;
import com.telcobright.summary.outbox.internal.OutboxCodec;
import com.telcobright.summary.runtime.internal.JdbcUnitOfWorkFactory;
import com.telcobright.summary.runtime.spi.UnitOfWork;
import com.telcobright.summary.summarybeans.ad.internal.AdSummaryBean;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.summarybeans.call.internal.CallSummaryBean;
import com.telcobright.summary.summarybeans.call.internal.CdrBlobMapper;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.PgLab;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The outbox consumer's contract ({@link OutboxConsumerContract}) on PostgreSQL (brief S5) — every test MySQL
 * runs, word for word, in a tier SCHEMA of the switch database made the way the real ones are:
 * prime-context's provisioning (the schema and its grants), billing-core's outbox with its own grant, and the
 * service acting as {@code summary_service} — which creates only what is its own. The voice tables are made by the
 * beans themselves (the rendered PostgreSQL DDL), not by hand.
 *
 * <p>The lab: {@code tools/lab/pg-lab.sh up} (127.0.0.1:7643, trust; no password exists). SELF-SKIPS if it does
 * not answer.
 */
class PostgresOutboxConsumerIT extends OutboxConsumerContract {

    protected static final String TIER = PgLab.schema("tier");

    @Override
    protected SqlDialect dialect() {
        return SqlDialect.POSTGRESQL;
    }

    @Override
    protected String schemaName() {
        return TIER;
    }

    @Override
    protected DataSource freshSchema() {
        assumeTrue(PgLab.reachable(), "the PostgreSQL lab is not reachable (tools/lab/pg-lab.sh up) — skipping integration test");
        PgLab.provisionTier(TIER);
        PgLab.billingTables(TIER);
        return PgLab.dataSource(PgLab.SUMMARY_SERVICE, TIER);
    }

    /** The service's own tables, made by the service as a worker's start makes them: infra, then each bean's table. */
    @Override
    protected void serviceTables() {
        reader.ensureInfraTables();
        reader.ensureProvisioned(bean);
        reader.ensureProvisioned(CdrTestSupport.hourlyBean());
    }

    // ---- what is PostgreSQL's alone ----

    @Test
    void the_service_makes_only_its_own_tables_and_never_billings_outbox() {
        // a tier prime-context provisioned and billing-core has NOT served yet: whoever creates summary_affected
        // there owns it, so the summary side must not (ad-is-a-call §3; SS-0001 F6, agreed on both sides)
        String bare = PgLab.schema("bare");
        PgLab.provisionTier(bare);
        try {
            OutboxReader onBare = new OutboxReader(new JdbcUnitOfWorkFactory(PgLab.dataSource(PgLab.SUMMARY_SERVICE, bare), SqlDialect.POSTGRESQL),
                    new SummaryEngine(), 1000, 50, 8);

            onBare.ensureInfraTables();
            onBare.ensureProvisioned(AdTestSupport.dailyBean());

            assertEquals("sum_ad_day_30,summary_affected_dlq,summary_offset", PgLab.queryText(PgLab.PRIME_CONTEXT, null,
                    "select string_agg(tablename, ',' order by tablename) from pg_tables where schemaname = '" + bare + "'"),
                    "its bookmark, its dead letters, its summary — and no outbox");
            assertEquals(0, PgLab.queryLong(PgLab.PRIME_CONTEXT, null, "select count(*) from pg_tables where schemaname = '" + bare
                    + "' and tableowner <> '" + PgLab.SUMMARY_SERVICE + "'"), "all three are the service's own");
        } finally {
            PgLab.dropTier(bare);
        }
    }

    @Test
    void the_summary_tables_are_plain_tables_and_ad_spheres_reader_sees_and_reads_them() throws SQLException {
        AdSummaryBean adDaily = AdTestSupport.dailyBean();
        reader.ensureProvisioned(adDaily);
        seedOutbox(1, OutboxCodec.encode(AdTestSupport.batchOf(AdTestSupport.leafView(AdTestSupport.at(2026, 9, 29, 10, 0)))));
        assertEquals(1, reader.drain(adDaily));

        assertEquals("r", PgLab.queryText(PgLab.PRIME_CONTEXT, null, "select c.relkind::text from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                + "where n.nspname = '" + TIER + "' and c.relname = 'sum_ad_day_30'"), "an ordinary table: no partitions on PostgreSQL (ruled on SS-0001)");

        // ad-sphere's JdbcCdrReader, as the role ad_sphere: its presence check, then its statement — both word for word
        try (Connection asAdSphere = PgLab.connect(PgLab.AD_SPHERE, null)) {
            try (ResultSet present = asAdSphere.getMetaData().getTables(null, TIER, "sum_ad_day_30", new String[] {"TABLE", "BASE TABLE"})) {
                assertTrue(present.next(), "present(): the reader asks for TABLE / BASE TABLE — a partitioned table would answer neither");
            }
            String select = "SELECT tup_starttime, tup_tenant, tup_partnerid, tup_campaignid, tup_rulecode, tup_zone, tup_site, tup_app, tup_mediakind, tup_outcome,"
                    + " views, shown, completed, credited, failed, watchedsec, chargedamount FROM " + TIER + ".sum_ad_day_30 WHERE 1 = 1"
                    + " AND tup_partnerid = ? AND tup_starttime >= ? AND tup_starttime < ?"
                    + " ORDER BY tup_starttime, tup_partnerid, tup_campaignid LIMIT 5000";
            try (PreparedStatement ps = asAdSphere.prepareStatement(select)) {
                ps.setObject(1, 61);
                ps.setObject(2, Timestamp.valueOf(LocalDateTime.of(2026, 9, 29, 0, 0)));
                ps.setObject(3, Timestamp.valueOf(LocalDateTime.of(2026, 9, 30, 0, 0)));
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "the row summary-service wrote");
                    assertEquals(LocalDateTime.of(2026, 9, 29, 0, 0), rs.getTimestamp("tup_starttime").toLocalDateTime());
                    assertEquals(TIER, rs.getString("tup_tenant"), "the schema's own name");
                    assertEquals(61, rs.getInt("tup_partnerid"));
                    assertEquals(5, rs.getInt("tup_campaignid"));
                    assertEquals("wifi", rs.getString("tup_app"));
                    assertEquals(1, rs.getLong("views"));
                    assertEquals(15, rs.getLong("watchedsec"));
                    assertEquals(0, new BigDecimal("0.50").compareTo(rs.getBigDecimal("chargedamount")));
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void summary_service_may_delete_from_the_outbox_and_from_nothing_else_of_billings() {
        // the ruled rights (brief N2): SELECT on what billing-core creates, DELETE on the outbox alone — given by its owner
        assertEquals(null, PgLab.refusal(PgLab.SUMMARY_SERVICE, TIER, "DELETE FROM summary_affected WHERE id < 0"), "the reaper's statement is allowed");
        assertDenied(PgLab.refusal(PgLab.SUMMARY_SERVICE, TIER, "DELETE FROM cdr"), "a call's record is never the summary side's to erase");
        assertDenied(PgLab.refusal(PgLab.SUMMARY_SERVICE, TIER, "INSERT INTO summary_affected (entity_type, data) VALUES ('cdr', 'x')"),
                "only billing-core writes the outbox");
        assertEquals(1, PgLab.queryLong(PgLab.SUMMARY_SERVICE, TIER, "select count(*) from cdr"), "it reads billing's tables");
    }

    @Test
    void ad_sphere_reads_what_the_service_creates_and_writes_none_of_it() {
        assertEquals(0, PgLab.queryLong(PgLab.AD_SPHERE, TIER, "select count(*) from sum_voice_day_03"), "SELECT, by prime-context's default privileges");
        assertEquals(0, PgLab.queryLong(PgLab.AD_SPHERE, TIER, "select count(*) from summary_offset"));
        assertDenied(PgLab.refusal(PgLab.AD_SPHERE, TIER, "DELETE FROM sum_voice_day_03"), "a reader");
        assertDenied(PgLab.refusal(PgLab.AD_SPHERE, TIER, "UPDATE summary_offset SET last_offset = 0"), "a reader");
        assertDenied(PgLab.refusal(PgLab.AD_SPHERE, TIER, "INSERT INTO summary_affected_dlq (entity_type, bean_name, outbox_id, data, error) VALUES ('a','b',1,'c','d')"), "a reader");
    }

    @Test
    void a_table_is_made_with_its_indexes_or_not_at_all() {
        // PostgreSQL's DDL is transactional and the statements run in ONE transaction: an index that fails takes
        // the CREATE TABLE with it, so a table never exists half-made
        CallSummaryBean halfMade = new CallSummaryBean(CdrBlobMapper.create(), "halfMade", "half", 10, null) {
            @Override
            public WindowSize window() {
                return WindowSize.parse("daily");
            }

            @Override
            public SummaryTableSpec tableSpec() {
                return SummaryTableSpec.table(table()).identity("id").datetime("tup_starttime").primaryKey("id")
                        .index("ix_bad", "a_column_that_is_not_there").build();
            }
        };

        assertThrows(RuntimeException.class, () -> reader.ensureProvisioned(halfMade));

        assertEquals(null, PgLab.queryText(PgLab.SUMMARY_SERVICE, TIER, "select to_regclass('" + TIER + ".sum_voice_day_half')::text"),
                "the table is not there either");
    }

    @Test
    void identifiers_are_stored_in_lower_case_and_the_beans_read_them_back() {
        assertEquals(3, PgLab.queryLong(PgLab.SUMMARY_SERVICE, TIER, "select count(*) from information_schema.columns where table_schema = '" + TIER
                + "' and table_name = 'sum_voice_day_03' and column_name in ('connectedcallscc', 'pdd', 'tup_sourceid')"),
                "never quoted, so PostgreSQL folds connectedcallsCC, PDD, tup_sourceId (design §3)");
        seedOutbox(1, CdrTestSupport.encodedBatch(java.util.List.of(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 10, 0)))));
        seedOutbox(2, CdrTestSupport.encodedBatch(java.util.List.of(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 11, 0)))));
        assertEquals(2, reader.drain(bean), "row 2 maps the stored row back through the mixed-case names and merges");
        assertEquals(2, queryLong("select connectedcallsCC from " + DAY_TABLE));
    }

    @Test
    void the_string_setting_is_the_services_own_sessions_never_the_databases() {
        // the service's units of work read a backslash as MySQL does; nobody else's session is touched
        assertEquals("on", PgLab.queryText(PgLab.AD_SPHERE, null, "show standard_conforming_strings"));
        assertEquals("on", PgLab.queryText(PgLab.SUMMARY_SERVICE, null, "show standard_conforming_strings"), "a plain connection of the same role");
    }

    @Test
    void a_unit_of_work_leaves_nothing_on_its_connection_the_schema_and_the_string_setting_end_with_its_transaction() throws SQLException {
        // ONE physical connection, handed out again and again as a pool does (it is never really closed here)
        String other = PgLab.schema("other");
        PgLab.provisionTier(other);
        try (Connection physical = PgLab.connect(PgLab.SUMMARY_SERVICE, TIER)) {
            JdbcUnitOfWorkFactory onOneConnection = new JdbcUnitOfWorkFactory(PgLab.neverClosing(physical), SqlDialect.POSTGRESQL);
            String inside;
            try (UnitOfWork work = onOneConnection.begin(other)) {
                inside = settingsOf(physical);                                    // asked inside the unit of work's transaction
                work.commit();
            }
            assertEquals(other + "|off", inside, "inside its transaction: its schema, a backslash read as MySQL reads it");
            assertEquals(TIER + "|on", settingsOf(physical), "after the commit: the connection is as it was");

            try (UnitOfWork work = onOneConnection.begin(other)) {
                work.rollback();
            }
            assertEquals(TIER + "|on", settingsOf(physical), "after a rollback too");
        } finally {
            PgLab.dropTier(other);
        }
    }

    private static String settingsOf(Connection connection) throws SQLException {
        try (java.sql.Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("select current_schema() || '|' || current_setting('standard_conforming_strings')")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void assertDenied(String refusal, String why) {
        assertTrue(refusal != null && refusal.contains("permission denied"), why + " — the database said: " + refusal);
    }

    @Override
    protected Connection billingConnection() throws SQLException {
        return PgLab.connect(PgLab.BILLING_CORE, TIER);
    }

    @Override
    protected Connection dbConnection() throws SQLException {
        return PgLab.connect(PgLab.SUMMARY_SERVICE, TIER);
    }

    @Override
    protected DataSource theServicesOwnPool(int size) {
        return com.telcobright.summary.runtime.internal.TestPools.pool(SqlDialect.POSTGRESQL, PgLab.urlFor(TIER), PgLab.SUMMARY_SERVICE, "", size);
    }

    /** Every session of the role summary_service is ended by the server (as a restart ends them), by the lab's superuser. */
    @Override
    protected int endTheServicesSessions() throws SQLException {
        return (int) PgLab.queryLong("postgres", null, "select count(pg_terminate_backend(pid)) from pg_stat_activity where usename = '"
                + PgLab.SUMMARY_SERVICE + "' and pid <> pg_backend_pid()");
    }
}
