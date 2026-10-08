package com.telcobright.summary.registry.internal;

import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.outbox.api.OutboxReader;
import com.telcobright.summary.summarybeans.call.internal.CallSummaryBean;
import com.telcobright.summary.testkit.Await;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory;
import com.telcobright.summary.testkit.LogCapture;
import com.telcobright.summary.testkit.StoreThatGoesAway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Brief S16 (found on the bed, X-0020 X-2): the database was stopped for 40 s and started again; summary-service's
 * workers failed every try until a RESTART. The rule: a worker whose store went away comes back BY ITSELF when the
 * store is back — no restart — and says so: ONE ERROR when the trouble starts, ONE INFO when it writes again, not
 * a line per try. (Why the bed's did not: its pool handed out the connections the restart had ended, for ever —
 * the pool's side is held by the contract's {@code a_store_that_ended_every_session…} on both engines.)
 */
class OutboxWorkerTest {

    private final FakeUnitOfWorkFactory database = new FakeUnitOfWorkFactory();
    private final StoreThatGoesAway store = new StoreThatGoesAway(database);
    private final CallSummaryBean bean = CdrTestSupport.dailyBean();
    private final OutboxReader reader = new OutboxReader(store, new SummaryEngine(), 1000, 1, 8);
    private OutboxWorker<?> worker;
    private Thread thread;

    private void startTheWorker(int pollIntervalSeconds) {
        worker = new OutboxWorker<>(bean, reader, pollIntervalSeconds);
        thread = new Thread(worker, "summary-worker-test");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopTheWorker() throws InterruptedException {
        if (worker != null) {
            worker.stop();
            thread.join(5000);
        }
    }

    private void billingWrites(long id, int hour) {
        database.outbox.seed(id, CdrTestSupport.encodedBatch(List.of(CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, hour, 0)))));
    }

    @Test
    void a_worker_whose_store_went_away_writes_again_by_itself_when_it_is_back_with_no_ping_and_no_restart() {
        store.goAway();
        billingWrites(1, 10);
        startTheWorker(1);
        // at EVERY poll, never later: six tries within 8 s of a 1 s poll (a wait that grew — 1, 2, 3, 4, 5 s — would reach six at 15 s)
        assertTrue(Await.until(() -> store.triedWhileAway() >= 6, 8_000), "it tries again at every poll while the store is away: " + store.triedWhileAway());
        assertEquals(0, database.outbox.readOffset("cdr", bean.name()), "nothing moved: the bookmark waits");

        store.comeBack();
        long back = System.nanoTime();

        assertTrue(Await.until(() -> database.outbox.readOffset("cdr", bean.name()) == 1, 5_000),
                "the row is written by the SAME worker after the store is back — no ping, no restart");
        long seconds = (System.nanoTime() - back) / 1_000_000_000L;
        assertTrue(seconds <= 2, "by the next poll (1 s) at the latest: " + seconds + " s");
        assertTrue(thread.isAlive(), "the worker did not die, nor was it replaced");

        billingWrites(2, 11);                                       // and it goes on as before
        worker.wake();
        assertTrue(Await.until(() -> database.outbox.readOffset("cdr", bean.name()) == 2, 5_000));
    }

    @Test
    void the_trouble_is_said_once_when_it_starts_and_once_when_it_writes_again_never_a_line_per_try() {
        try (LogCapture said = LogCapture.of(OutboxWorker.class)) {
            store.goAway();
            billingWrites(1, 10);
            startTheWorker(3600);                                   // only wakes (pings) make it try: twelve of them
            assertTrue(Await.until(() -> store.triedWhileAway() == 1, 5_000), "the first try is the start's own, before any ping");
            for (int ping = 0; ping < 12; ping++) {
                int before = store.triedWhileAway();
                worker.wake();
                assertTrue(Await.until(() -> store.triedWhileAway() > before, 5_000), "a ping during the trouble tries at once");
            }
            store.comeBack();
            worker.wake();
            assertTrue(Await.until(() -> database.outbox.readOffset("cdr", bean.name()) == 1, 5_000));
            assertTrue(Await.until(() -> said.lines().stream().anyMatch(line -> line.contains("writes again —")), 5_000), "said once the drain is over");

            List<String> errors = said.lines().stream().filter(line -> line.contains("drain failed")).toList();
            assertEquals(1, errors.size(), "ONE line when the trouble starts, not one per try (13 tries): " + errors);
            assertTrue(errors.get(0).contains("offset STUCK") && errors.get(0).contains("ONE line says when it writes again"), errors.get(0));
            List<String> back = said.lines().stream().filter(line -> line.contains("writes again —")).toList();
            assertEquals(1, back.size(), "ONE line when it writes again: " + said.lines());
            assertTrue(back.get(0).contains("after ") && back.get(0).contains(" s and 13 failed tries; nothing was lost"), back.get(0));
            assertEquals(1, said.records(java.util.logging.Level.SEVERE).size(), "the one line is an ERROR");
        }
    }

    @Test
    void a_worker_with_no_trouble_says_nothing_of_it() {
        try (LogCapture said = LogCapture.of(OutboxWorker.class)) {
            billingWrites(1, 10);
            startTheWorker(3600);
            assertTrue(Await.until(() -> database.outbox.readOffset("cdr", bean.name()) == 1, 5_000));
            assertTrue(said.lines().stream().noneMatch(line -> line.contains("writes again") || line.contains("drain failed")), said.lines().toString());
        }
    }

    @Test
    void the_second_trouble_is_said_again_once() {
        try (LogCapture said = LogCapture.of(OutboxWorker.class)) {
            startTheWorker(3600);
            for (int trouble = 1; trouble <= 2; trouble++) {
                store.goAway();
                billingWrites(trouble, 9 + trouble);
                for (int ping = 0; ping < 3; ping++) {
                    int before = store.triedWhileAway();
                    worker.wake();
                    assertTrue(Await.until(() -> store.triedWhileAway() > before, 5_000));
                }
                store.comeBack();
                worker.wake();
                long written = trouble;
                assertTrue(Await.until(() -> database.outbox.readOffset("cdr", bean.name()) == written, 5_000));
                long saidBack = trouble;
                assertTrue(Await.until(() -> said.lines().stream().filter(line -> line.contains("writes again —")).count() == saidBack, 5_000));
            }
            assertEquals(2, said.lines().stream().filter(line -> line.contains("drain failed")).count(), "one per trouble");
            assertEquals(2, said.lines().stream().filter(line -> line.contains("writes again —")).count());
        }
    }

}
