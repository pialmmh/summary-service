package com.telcobright.summary.outbox.internal;

import com.telcobright.summary.testkit.FakeOutboxStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The reaper's safety watermark: delete only up to min(last_offset) across all active beans. */
class OutboxReapTest {

    private static final List<String> BEANS = List.of("dailyCallSummary", "hourlyCallSummary");

    @Test
    void deletes_up_to_the_min_offset_across_beans() {
        FakeOutboxStore outbox = new FakeOutboxStore();
        outbox.seed(1, "x");
        outbox.seed(2, "x");
        outbox.seed(3, "x");
        outbox.advanceOffset("cdr", "dailyCallSummary", 3);
        outbox.advanceOffset("cdr", "hourlyCallSummary", 2);   // hourly lags

        long min = outbox.minOffset("cdr", BEANS);
        assertEquals(2, min, "min across beans = the laggard");
        assertEquals(2, outbox.deleteUpTo("cdr", min), "rows 1 and 2 removed");
        assertEquals(1, outbox.rowCount(), "row 3 kept — hourly hasn't passed it");
    }

    @Test
    void keeps_everything_until_every_bean_has_progressed() {
        FakeOutboxStore outbox = new FakeOutboxStore();
        outbox.seed(1, "x");
        outbox.advanceOffset("cdr", "dailyCallSummary", 1);    // hourly has NO offset row yet

        assertEquals(0, outbox.minOffset("cdr", BEANS), "a bean with no offset counts as 0");
        assertEquals(0, outbox.deleteUpTo("cdr", 0));
        assertEquals(1, outbox.rowCount());
    }
}
