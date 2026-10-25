package com.telcobright.summary.outbox.internal;

import com.telcobright.summary.registry.api.SummaryBeanRegistry;
import com.telcobright.summary.outbox.api.OutboxReader;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Trims the outbox of EVERY served tenant schema, for EVERY entity that has a registered bean (HARDEN-1): every
 * {@code reaper-interval-seconds}, in each schema and for each such entity, deletes that entity's
 * {@code summary_affected} rows which ALL its registered beans have passed there — {@code id <= min(last_offset)} over
 * THAT entity's beans only (a bean with no offset row yet counts as 0, so nothing of its entity is deleted until it has
 * progressed). An entity with no registered bean is never trimmed: a bean that comes back must find its rows (SS-0008
 * D1). A schema billing-core has not served yet has no outbox: nothing to trim there.
 *
 * <p>The trim deletes in BOUNDED batches (HARDEN-1 B4): at most {@code reap-batch} rows per transaction, the lowest ids
 * first, batch after batch until that (schema, entity)'s passed rows are gone or the pass's time budget
 * ({@code reap-pass-budget-ms}) is spent. Every (schema, entity) gets its FIRST batch whatever the budget, so a small
 * trim (the {@code cdr} entity's minute of rows) is never held up behind a big one (a {@code wifi_flow} backlog). What a
 * spent budget leaves is the next pass's — said in ONE INFO, with the rows left. One transaction per batch keeps every
 * lock short and every undo small; a batch that fails is said and the others go on.
 *
 * <p>A (schema, entity) that fails is said ONCE (a WARN) and once more when it trims again (an INFO) — not at every
 * pass while a database is away (brief S16); the passes in between are DEBUG lines. The reaper is not a worker: a
 * missed pass loses nothing, the next one trims what the last one could not.
 *
 * <p>{@value #RETIRED_ENTITY_TYPE_KEY} — the ONE entity the reaper trimmed before (a WiFi tenant's {@code wifi_flow}
 * rows stayed for ever: SS-0008 F1) — is retired: a profile that still sets it is told so once, at start, and nothing
 * else changes.
 */
@ApplicationScoped
public class OutboxReaper {

    private static final Logger LOG = Logger.getLogger(OutboxReaper.class);

    /** The retired key: the one entity the reaper trimmed until HARDEN-1. Read only to say that it is ignored. */
    public static final String RETIRED_ENTITY_TYPE_KEY = "summary.outbox.entity-type";
    /** Rows per reaper transaction, and the time a pass may spend on more than each (schema, entity)'s first batch. */
    public static final String REAP_BATCH_KEY = "summary.outbox.reap-batch", REAP_PASS_BUDGET_KEY = "summary.outbox.reap-pass-budget-ms";
    public static final int DEFAULT_REAP_BATCH = 10_000;
    public static final long DEFAULT_REAP_PASS_BUDGET_MS = 5_000;

    private final OutboxReader reader;
    private final SummaryBeanRegistry registry;
    private final Optional<String> retiredEntityType;
    private final int intervalSeconds;
    private final int reapBatch;
    private final long passBudgetMillis;
    private ScheduledExecutorService scheduler;
    /** The (schema, entity) pairs whose passes are failing, and how many in a row (the reaper's own thread, or a test's). */
    private final Map<Trimmed, Integer> failingPasses = new ConcurrentHashMap<>();

    @Inject
    public OutboxReaper(OutboxReader reader, SummaryBeanRegistry registry,
                        @ConfigProperty(name = RETIRED_ENTITY_TYPE_KEY) Optional<String> retiredEntityType,
                        @ConfigProperty(name = "summary.outbox.reaper-interval-seconds", defaultValue = "60") int intervalSeconds,
                        @ConfigProperty(name = REAP_BATCH_KEY, defaultValue = "10000") int reapBatch,
                        @ConfigProperty(name = REAP_PASS_BUDGET_KEY, defaultValue = "5000") long passBudgetMillis) {
        if (reapBatch < 1 || passBudgetMillis < 0) {
            throw new IllegalArgumentException(REAP_BATCH_KEY + " must be at least 1 (it is " + reapBatch + ") and " + REAP_PASS_BUDGET_KEY
                    + " at least 0 (it is " + passBudgetMillis + ")");
        }
        this.reader = reader;
        this.registry = registry;
        this.retiredEntityType = retiredEntityType;
        this.intervalSeconds = intervalSeconds;
        this.reapBatch = reapBatch;
        this.passBudgetMillis = passBudgetMillis;
    }

    /** A reaper of the given batch and budget, whose profile does not set the retired key (tests, embedders). */
    public OutboxReaper(OutboxReader reader, SummaryBeanRegistry registry, int intervalSeconds, int reapBatch, long passBudgetMillis) {
        this(reader, registry, Optional.empty(), intervalSeconds, reapBatch, passBudgetMillis);
    }

    /** A reaper of the default batch and budget, whose profile does not set the retired key (tests, embedders). */
    public OutboxReaper(OutboxReader reader, SummaryBeanRegistry registry, int intervalSeconds) {
        this(reader, registry, intervalSeconds, DEFAULT_REAP_BATCH, DEFAULT_REAP_PASS_BUDGET_MS);
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        sayTheRetiredKeyIsIgnored();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "summary-reaper");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::reapQuietly, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        LOG.infof("reaper started: every %ds, in batches of at most %d rows (more than one per entity while the pass's %d ms last), "
                + "each entity that has a registered bean (now %s) trimmed by its own beans' bookmarks",
                intervalSeconds, reapBatch, passBudgetMillis, registry.registeredBeanNamesByEntity().keySet());
    }

    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void reapQuietly() {
        try {
            reapOnce();
        } catch (RuntimeException e) {
            LOG.warn("reaper pass failed; will retry next interval", e);
        }
    }

    /** One reap pass: every served schema, every entity that has a registered bean; returns the rows deleted in all. Visible for tests. */
    public int reapOnce() {
        Map<String, Set<String>> beansByEntity = registry.registeredBeanNamesByEntity();
        PassBudget budget = new PassBudget(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(passBudgetMillis));
        int deleted = 0;
        for (String schema : registry.servedSchemas()) {
            deleted += reapSchema(schema, beansByEntity, budget);
        }
        sayWhatTheBudgetLeft(budget);
        return deleted;
    }

    /** Each entity by its OWN beans' bookmarks: one entity's watermark is never another's. */
    private int reapSchema(String schema, Map<String, Set<String>> beansByEntity, PassBudget budget) {
        int deleted = 0;
        for (Map.Entry<String, Set<String>> entity : beansByEntity.entrySet()) {
            deleted += reapEntity(new Trimmed(schema, entity.getKey()), entity.getValue(), budget);
        }
        return deleted;
    }

    /** One (schema, entity), batch by batch: a failure is said once for it, and the others go on. Returns the rows its committed batches deleted. */
    private int reapEntity(Trimmed trimmed, Set<String> itsBeans, PassBudget budget) {
        BatchedTrim trim = new BatchedTrim(trimmed, itsBeans);
        try {
            trim.untilDoneOrOutOf(budget);
            sayItTrimsAgain(trimmed);
        } catch (RuntimeException failure) {
            sayTheTrouble(trimmed, failure);
        }
        return trim.deleted;
    }

    /** One (schema, entity)'s trim in this pass: batches of at most {@code reapBatch}, each its own transaction. */
    private final class BatchedTrim {
        private final Trimmed trimmed;
        private final Set<String> itsBeans;
        private int deleted;

        BatchedTrim(Trimmed trimmed, Set<String> itsBeans) {
            this.trimmed = trimmed;
            this.itsBeans = itsBeans;
        }

        /** The first batch always; then more while a batch comes back full and the budget lasts; a full last batch counts what it left. */
        void untilDoneOrOutOf(PassBudget budget) {
            int batch;
            do {
                batch = reader.reap(trimmed.schemaArgument(), trimmed.entityType(), itsBeans, reapBatch);
                deleted += batch;
            } while (batch == reapBatch && !budget.spent());
            if (batch == reapBatch) {
                budget.leftBehind(trimmed, reader.passedRowsLeft(trimmed.schemaArgument(), trimmed.entityType(), itsBeans));
            }
        }
    }

    /** The time a pass may spend on more than each (schema, entity)'s first batch, and what it left when it ran out. */
    private static final class PassBudget {
        private final long deadlineNanos;
        private final Map<Trimmed, Long> rowsLeft = new LinkedHashMap<>();

        PassBudget(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }

        boolean spent() {
            return System.nanoTime() - deadlineNanos >= 0;
        }

        void leftBehind(Trimmed trimmed, long rows) {
            if (rows > 0) {
                rowsLeft.put(trimmed, rows);
            }
        }
    }

    private void sayWhatTheBudgetLeft(PassBudget budget) {
        if (budget.rowsLeft.isEmpty()) {
            return;
        }
        LOG.infof("reaper: this pass stopped on its budget (%s=%d) — the next pass goes on with the rows left: %s", REAP_PASS_BUDGET_KEY,
                passBudgetMillis, budget.rowsLeft.entrySet().stream().map(left -> left.getKey().said() + ": " + left.getValue())
                        .collect(Collectors.joining("; ")));
    }

    private void sayTheRetiredKeyIsIgnored() {
        retiredEntityType.ifPresent(entityType -> LOG.infof("%s=%s is IGNORED: the key is retired (HARDEN-1). Every pass trims EACH entity "
                + "that has a registered bean, each by min(last_offset) over that entity's own beans; an entity with no registered bean is "
                + "never trimmed (a bean that comes back must find its rows). The line can be removed from the profile",
                RETIRED_ENTITY_TYPE_KEY, entityType));
    }

    private void sayTheTrouble(Trimmed trimmed, RuntimeException failure) {
        int passes = failingPasses.merge(trimmed, 1, Integer::sum);
        if (passes == 1) {
            LOG.warnf(failure, "reaper: %s failed this pass; the other schemas and entities go on. It is tried again every pass, quietly: "
                    + "ONE line says when it trims again", trimmed.said());
        } else if (LOG.isDebugEnabled()) {
            LOG.debugf("reaper: %s failed again (%d passes): %s", trimmed.said(), passes, failure.toString());
        }
    }

    private void sayItTrimsAgain(Trimmed trimmed) {
        Integer passes = failingPasses.remove(trimmed);
        if (passes != null) {
            LOG.infof("reaper: %s trims again — after %d failed pass%s", trimmed.said(), passes, passes == 1 ? "" : "es");
        }
    }

    /** One entity's outbox rows in one served schema — what one reaper transaction trims. */
    private record Trimmed(String schema, String entityType) {

        /** The schema as the reader takes it: null = the connection's own. */
        String schemaArgument() {
            return schema.equals(SummaryBeanRegistry.OWN_SCHEMA) ? null : schema;
        }

        String said() {
            return "schema " + (schema.equals(SummaryBeanRegistry.OWN_SCHEMA) ? "(the connection's own)" : schema) + ", entity " + entityType;
        }
    }
}
