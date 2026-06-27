package com.telcobright.summary.outbox.api;

import com.telcobright.summary.beans.cdr.CdrSummaryBean;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.FakeOutboxStore;
import com.telcobright.summary.testkit.FakeSummaryStore;
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.telcobright.summary.testkit.CdrTestSupport.DAY_TABLE;
import static com.telcobright.summary.testkit.CdrTestSupport.at;
import static com.telcobright.summary.testkit.CdrTestSupport.encodedBatch;
import static com.telcobright.summary.testkit.CdrTestSupport.sg10Entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The drain: reads rows after the offset, writes summaries + advances the offset in ONE tx — exactly-once. */
class OutboxReaderTest {

    private static final String ENTITY = "cdr";
    private static final String BEAN = "dailyCdrSummary";
    private final CdrSummaryBean bean = CdrTestSupport.dailyBean();

    private OutboxReader reader(FakeUnitOfWorkFactory factory) {
        return new OutboxReader(factory, new SummaryEngine(), 1000, 50);
    }

    @Test
    void drains_rows_writes_summaries_and_advances_offset() {
        FakeOutboxStore outbox = new FakeOutboxStore();
        outbox.seed(1, encodedBatch(List.of(sg10Entry(at(2026, 6, 19, 10, 0)))));
        outbox.seed(2, encodedBatch(List.of(sg10Entry(at(2026, 6, 20, 10, 0)))));
        FakeSummaryStore store = new FakeSummaryStore();
        FakeUnitOfWorkFactory factory = new FakeUnitOfWorkFactory(store, outbox);

        int processed = reader(factory).drain(bean);

        assertEquals(2, processed, "both outbox rows processed");
        assertEquals(2, outbox.readOffset(ENTITY, BEAN), "offset advanced to the last row id");
        assertTrue(store.ranSqlMatching("insert into " + DAY_TABLE), "summaries written");
        assertTrue(factory.last.committed);
        assertFalse(factory.last.rolledBack);
    }

    @Test
    void re_drain_after_success_is_a_no_op() {
        FakeOutboxStore outbox = new FakeOutboxStore();
        outbox.seed(1, encodedBatch(List.of(sg10Entry(at(2026, 6, 19, 10, 0)))));
        FakeSummaryStore store = new FakeSummaryStore();
        FakeUnitOfWorkFactory factory = new FakeUnitOfWorkFactory(store, outbox);
        OutboxReader reader = reader(factory);

        reader.drain(bean);
        int sqlAfterFirst = store.executedSql().size();

        int processedAgain = reader.drain(bean);

        assertEquals(0, processedAgain, "nothing new to process");
        assertEquals(sqlAfterFirst, store.executedSql().size(), "no extra writes (exactly-once)");
        assertEquals(1, outbox.readOffset(ENTITY, BEAN), "offset unchanged");
    }

    @Test
    void a_write_failure_rolls_back_and_leaves_the_offset_unchanged() {
        FakeOutboxStore outbox = new FakeOutboxStore();
        outbox.seed(1, encodedBatch(List.of(sg10Entry(at(2026, 6, 19, 10, 0)))));
        FakeSummaryStore failing = new FakeSummaryStore();
        failing.failWrites();
        FakeUnitOfWorkFactory factory = new FakeUnitOfWorkFactory(failing, outbox);

        assertThrows(RuntimeException.class, () -> reader(factory).drainOnce(bean));

        assertEquals(0, outbox.readOffset(ENTITY, BEAN), "offset NOT advanced -> batch redelivers, no double-count");
        assertTrue(factory.last.rolledBack);
        assertFalse(factory.last.committed);
    }
}
