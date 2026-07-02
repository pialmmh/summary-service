package com.telcobright.summary.outbox.api;

import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.FakeOutboxStore;
import com.telcobright.summary.testkit.FakeSummaryStore;
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Self-provisioning (user directive 2026-07-02): before a bean's worker runs, its table is CREATEd IF NOT
 * EXISTS with the FULL partition set, and the service-level infra tables are ensured once per process.
 */
class OutboxProvisioningTest {

    private final FakeSummaryStore store = new FakeSummaryStore();
    private final OutboxReader reader =
            new OutboxReader(new FakeUnitOfWorkFactory(store, new FakeOutboxStore()), new SummaryEngine(), 1000, 1, 8);

    @Test
    void ensure_provisioned_runs_the_beans_create_if_not_exists_with_partitions() {
        reader.ensureProvisioned(CdrTestSupport.dailyBean());

        String ddl = store.firstSqlMatching("CREATE TABLE IF NOT EXISTS sum_voice_day_03");
        assertNotNull(ddl, "the bean's own table is self-provisioned");
        assertTrue(ddl.contains("PARTITION BY RANGE COLUMNS(tup_starttime)"),
                "the CREATE carries the partition clause — never create-bare-then-ALTER");
        assertTrue(ddl.contains("PARTITION pMAX VALUES LESS THAN (MAXVALUE)"), "closed by the catch-all");
        assertTrue(ddl.contains("PRIMARY KEY (id, tup_starttime)"),
                "partitioned tables carry the bucket in the PK (MySQL requires it in every unique key)");
    }

    @Test
    void ensure_infra_tables_creates_offset_dlq_and_the_dev_outbox_copy_once() {
        reader.ensureInfraTables();
        reader.ensureInfraTables();   // second call must be a no-op

        assertNotNull(store.firstSqlMatching("CREATE TABLE IF NOT EXISTS summary_offset"));
        assertNotNull(store.firstSqlMatching("CREATE TABLE IF NOT EXISTS summary_affected_dlq"));
        assertNotNull(store.firstSqlMatching("CREATE TABLE IF NOT EXISTS summary_affected "));
        assertEquals(3, store.executedSql().size(), "exactly one CREATE per infra table — ensured ONCE per process");
    }
}
