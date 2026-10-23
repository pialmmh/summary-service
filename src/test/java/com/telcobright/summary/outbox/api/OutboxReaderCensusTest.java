package com.telcobright.summary.outbox.api;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.outbox.internal.OutboxCodec;
import com.telcobright.summary.outbox.spi.RowCensus;
import com.telcobright.summary.summarybeans.call.internal.CallSummaryBean;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.FakeOutboxStore;
import com.telcobright.summary.testkit.FakeSummaryStore;
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.telcobright.summary.testkit.CdrTestSupport.at;
import static com.telcobright.summary.testkit.CdrTestSupport.encodedBatch;
import static com.telcobright.summary.testkit.CdrTestSupport.sg10Entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SS-0008 item 2: the reader's {@link RowCensus} hears the rows a step COMMITTED — for its counting bean only, after the
 * commit, once; a step that rolls back is never heard, nor a quarantined row.
 */
class OutboxReaderCensusTest {

    private final CallSummaryBean counting = CdrTestSupport.dailyBean();
    private final CallSummaryBean another = CdrTestSupport.hourlyBean();

    /** A census that writes down what it heard: "<tier>:<rows>". */
    private static final class Heard implements RowCensus {
        private final String countingBean;
        private final List<String> heard = new ArrayList<>();
        private final List<byte[]> rows = new ArrayList<>();

        Heard(String countingBean) {
            this.countingBean = countingBean;
        }

        @Override
        public boolean counts(String tier, SummaryBean<?> bean) {
            return bean.name().equals(countingBean);
        }

        @Override
        public void committed(String tier, List<byte[]> decodedRows) {
            heard.add(tier + ":" + decodedRows.size());
            rows.addAll(decodedRows);
        }
    }

    private static OutboxReader reader(FakeUnitOfWorkFactory factory, RowCensus census, int quarantineAfter) {
        return new OutboxReader(factory, new SummaryEngine(), 1000, 50, quarantineAfter, census);
    }

    @Test
    void the_counting_beans_committed_rows_are_heard_once_after_the_commit_with_the_tier() {
        FakeOutboxStore outbox = new FakeOutboxStore();
        outbox.seed(1, encodedBatch(List.of(sg10Entry(at(2026, 6, 19, 10, 0)))));
        outbox.seed(2, encodedBatch(List.of(sg10Entry(at(2026, 6, 20, 10, 0)))));
        FakeUnitOfWorkFactory factory = new FakeUnitOfWorkFactory(new FakeSummaryStore(), outbox);
        Heard census = new Heard(counting.name());
        OutboxReader reader = reader(factory, census, 8);

        assertEquals(2, reader.drain(counting));
        assertEquals(List.of(factory.last.schema() + ":2"), census.heard, "one step, its two rows, heard after it committed");
        assertTrue(factory.last.committed);
        assertEquals(new String(OutboxCodec.decode(encodedBatch(List.of(sg10Entry(at(2026, 6, 19, 10, 0)))))), new String(census.rows.get(0)),
                "the DECODED row, as the beans read it");

        assertEquals(0, reader.drain(counting), "caught up");
        assertEquals(1, census.heard.size(), "a re-drain hears nothing again");
        assertEquals(2, reader.drain(another), "another bean drains the same rows");
        assertEquals(1, census.heard.size(), "and is not heard: a row is counted once, whatever number of beans read it");
    }

    @Test
    void a_step_that_rolls_back_is_never_heard() {
        FakeOutboxStore outbox = new FakeOutboxStore();
        outbox.seed(1, encodedBatch(List.of(sg10Entry(at(2026, 6, 19, 10, 0)))));
        FakeSummaryStore failing = new FakeSummaryStore();
        failing.failWrites();
        FakeUnitOfWorkFactory factory = new FakeUnitOfWorkFactory(failing, outbox);
        Heard census = new Heard(counting.name());

        assertThrows(RuntimeException.class, () -> reader(factory, census, 8).drainOnce(counting));

        assertTrue(factory.last.rolledBack);
        assertTrue(census.heard.isEmpty(), "rolled back: the row is drained again later, and heard then");
    }

    @Test
    void a_quarantined_row_is_not_heard_and_the_clean_prefix_before_a_poison_row_is() {
        FakeOutboxStore outbox = new FakeOutboxStore();
        outbox.seed(1, encodedBatch(List.of(sg10Entry(at(2026, 6, 19, 10, 0)))));
        outbox.seed(2, "%%% poison %%%");                  // poison for every bean
        FakeUnitOfWorkFactory factory = new FakeUnitOfWorkFactory(new FakeSummaryStore(), outbox);
        Heard census = new Heard(counting.name());
        OutboxReader reader = reader(factory, census, 1);

        assertEquals(1, reader.drainOnce(counting), "the clean prefix commits");
        assertEquals(List.of(factory.last.schema() + ":1"), census.heard, "and is heard");
        assertEquals(1, reader.drainOnce(counting), "the poison head row is quarantined (threshold 1)");
        assertEquals(1, outbox.deadLetters().size());
        assertEquals(1, census.heard.size(), "a quarantined row is not heard");
    }

    @Test
    void a_reader_built_without_a_census_hears_nothing_and_drains_as_before() {
        FakeOutboxStore outbox = new FakeOutboxStore();
        outbox.seed(1, encodedBatch(List.of(sg10Entry(at(2026, 6, 19, 10, 0)))));
        FakeUnitOfWorkFactory factory = new FakeUnitOfWorkFactory(new FakeSummaryStore(), outbox);

        assertEquals(1, new OutboxReader(factory, new SummaryEngine(), 1000, 50, 8).drain(counting));
        assertTrue(factory.last.committed);
    }
}
