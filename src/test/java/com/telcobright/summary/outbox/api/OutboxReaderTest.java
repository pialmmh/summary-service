package com.telcobright.summary.outbox.api;

import com.telcobright.summary.summarybeans.call.internal.CallSummaryBean;
import com.telcobright.summary.summarybeans.call.model.CallSummary;
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
    private static final String BEAN = "dailyCallSummary";
    private final CallSummaryBean bean = CdrTestSupport.dailyBean();

    private static final int QUARANTINE_AFTER = 3;

    private OutboxReader reader(FakeUnitOfWorkFactory factory) {
        return new OutboxReader(factory, new SummaryEngine(), 1000, 50, QUARANTINE_AFTER);
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

    @Test
    void a_subtract_row_decrements_the_loaded_window() {
        // a billing correction's subtract row (the OLD values) folds in as MergeMode.SUBTRACT — the window,
        // committed earlier at 2 calls, decrements to 1 (add-then-subtract ACROSS rows in one tx is proven
        // against real MySQL in the IT; the fake cannot read back its own writes)
        FakeOutboxStore outbox = new FakeOutboxStore();
        outbox.seed(1, "subtract", encodedBatch(List.of(sg10Entry(at(2026, 6, 19, 10, 0)))));
        FakeSummaryStore store = new FakeSummaryStore();
        CallSummary existing = CdrTestSupport.daySummary(at(2026, 6, 19, 10, 0));
        existing.merge(CdrTestSupport.daySummary(at(2026, 6, 19, 10, 0)));   // the window holds 2 calls
        existing.setId(100L);
        store.seed(DAY_TABLE, at(2026, 6, 19, 0, 0), existing);
        FakeUnitOfWorkFactory factory = new FakeUnitOfWorkFactory(store, outbox);

        assertEquals(1, reader(factory).drain(bean), "the subtract row is consumed like any other");

        String update = store.firstSqlMatching("update " + DAY_TABLE);
        assertTrue(update != null && update.contains("totalcalls=1,"), "2 calls - 1 = 1 after the subtract: " + update);
        assertEquals(1, outbox.readOffset(ENTITY, BEAN), "offset advanced past the correction row");
    }

    @Test
    void subtract_on_a_missing_window_dead_letters_after_the_threshold() {
        // ruling A1=B: a subtract whose target window never existed (head-init skipped it, or its add was
        // dead-lettered) must NOT wedge the bean forever — same quarantine policy as a poison blob
        FakeOutboxStore outbox = new FakeOutboxStore();
        outbox.seed(1, "subtract", encodedBatch(List.of(sg10Entry(at(2026, 6, 19, 10, 0)))));
        FakeSummaryStore store = new FakeSummaryStore();   // empty: nothing to subtract from
        FakeUnitOfWorkFactory factory = new FakeUnitOfWorkFactory(store, outbox);
        OutboxReader reader = reader(factory);

        for (int attempt = 1; attempt < QUARANTINE_AFTER; attempt++) {
            assertThrows(RuntimeException.class, () -> reader.drainOnce(bean));
            assertEquals(0, outbox.readOffset(ENTITY, BEAN));
        }

        assertEquals(1, reader.drainOnce(bean), "threshold reached -> the subtract row is dead-lettered");
        assertEquals(1, outbox.deadLetters().size(), "the un-applied correction is preserved as the repair ticket");
        assertEquals(1, outbox.readOffset(ENTITY, BEAN));
        assertEquals(null, store.firstSqlMatching("update"), "nothing was half-applied");
        assertEquals(null, store.firstSqlMatching("insert"), "nothing was half-applied");
    }

    @Test
    void a_poison_head_row_is_quarantined_after_the_threshold_and_the_bean_moves_on() {
        FakeOutboxStore outbox = new FakeOutboxStore();
        outbox.seed(1, "%%% not base64 %%%");                                      // poison
        outbox.seed(2, encodedBatch(List.of(sg10Entry(at(2026, 6, 19, 10, 0)))));  // clean, stuck behind it
        FakeSummaryStore store = new FakeSummaryStore();
        FakeUnitOfWorkFactory factory = new FakeUnitOfWorkFactory(store, outbox);
        OutboxReader reader = reader(factory);

        for (int attempt = 1; attempt < QUARANTINE_AFTER; attempt++) {
            assertThrows(RuntimeException.class, () -> reader.drainOnce(bean), "pre-threshold attempts rethrow");
            assertEquals(0, outbox.readOffset(ENTITY, BEAN), "offset pinned while retrying");
            assertTrue(outbox.deadLetters().isEmpty(), "nothing dead-lettered before the threshold");
        }

        assertEquals(1, reader.drainOnce(bean), "threshold reached -> the poison row is consumed as a dead letter");
        assertEquals(1, outbox.deadLetters().size());
        assertEquals(1, outbox.deadLetters().get(0).outboxId());
        assertEquals(BEAN, outbox.deadLetters().get(0).beanName(), "quarantine is PER BEAN");
        assertEquals(1, outbox.readOffset(ENTITY, BEAN), "offset advanced past the poison row");

        assertEquals(1, reader.drain(bean), "the clean row behind the poison now drains normally");
        assertEquals(2, outbox.readOffset(ENTITY, BEAN));
        assertTrue(store.ranSqlMatching("insert into " + DAY_TABLE));
    }

    @Test
    void a_transient_write_failure_is_never_quarantined() {
        FakeOutboxStore outbox = new FakeOutboxStore();
        outbox.seed(1, encodedBatch(List.of(sg10Entry(at(2026, 6, 19, 10, 0)))));  // the blob is healthy
        FakeSummaryStore failing = new FakeSummaryStore();
        failing.failWrites();                                                       // the SQL layer is not
        FakeUnitOfWorkFactory factory = new FakeUnitOfWorkFactory(failing, outbox);
        OutboxReader reader = reader(factory);

        for (int attempt = 0; attempt < QUARANTINE_AFTER + 2; attempt++) {
            assertThrows(RuntimeException.class, () -> reader.drainOnce(bean));
        }

        assertTrue(outbox.deadLetters().isEmpty(), "SQL failures retry forever — data is never dead-lettered for them");
        assertEquals(0, outbox.readOffset(ENTITY, BEAN));
    }

    @Test
    void a_clean_prefix_commits_before_a_poison_row_and_the_poison_becomes_the_head() {
        FakeOutboxStore outbox = new FakeOutboxStore();
        outbox.seed(1, encodedBatch(List.of(sg10Entry(at(2026, 6, 19, 10, 0)))));  // clean
        outbox.seed(2, "%%% poison %%%");                                          // read in the same 50-row batch
        FakeSummaryStore store = new FakeSummaryStore();
        FakeUnitOfWorkFactory factory = new FakeUnitOfWorkFactory(store, outbox);

        assertEquals(1, reader(factory).drainOnce(bean), "the clean prefix commits; the poison row waits");

        assertEquals(1, outbox.readOffset(ENTITY, BEAN), "offset stops just before the poison row");
        assertTrue(store.ranSqlMatching("insert into " + DAY_TABLE), "the clean row's summaries are written");
        assertTrue(outbox.deadLetters().isEmpty(), "the poison row's streak only starts once it is the head");
    }

    @Test
    void init_offset_at_head_makes_a_late_enabled_bean_start_from_now() {
        FakeOutboxStore outbox = new FakeOutboxStore();
        outbox.seed(1, encodedBatch(List.of(sg10Entry(at(2026, 6, 19, 10, 0)))));  // pre-enable residue
        outbox.seed(2, encodedBatch(List.of(sg10Entry(at(2026, 6, 20, 10, 0)))));
        FakeSummaryStore store = new FakeSummaryStore();
        FakeUnitOfWorkFactory factory = new FakeUnitOfWorkFactory(store, outbox);
        OutboxReader reader = reader(factory);

        reader.initOffsetAtHead(bean);

        assertEquals(2, outbox.readOffset(ENTITY, BEAN), "bookmark seeded at the outbox head");
        assertEquals(0, reader.drain(bean), "the residue is NOT consumed (a partial backfill would look complete)");
        assertFalse(store.ranSqlMatching("insert"), "no summaries from pre-enablement rows");

        outbox.seed(3, encodedBatch(List.of(sg10Entry(at(2026, 6, 21, 10, 0)))));
        assertEquals(1, reader.drain(bean), "rows landing after enablement flow normally");
        assertEquals(3, outbox.readOffset(ENTITY, BEAN));

        reader.initOffsetAtHead(bean);
        assertEquals(3, outbox.readOffset(ENTITY, BEAN), "head-init is a no-op once a bookmark exists");
    }
}
