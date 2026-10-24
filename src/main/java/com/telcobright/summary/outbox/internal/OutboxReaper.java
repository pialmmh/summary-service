package com.telcobright.summary.outbox.internal;

import com.telcobright.summary.registry.api.SummaryBeanRegistry;
import com.telcobright.summary.outbox.api.OutboxReader;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Trims the outbox of EVERY served tenant schema, for EVERY entity that has a registered bean (HARDEN-1): every
 * {@code reaper-interval-seconds}, in each schema and for each such entity, deletes that entity's
 * {@code summary_affected} rows which ALL its registered beans have passed there — {@code id <= min(last_offset)} over
 * THAT entity's beans only (a bean with no offset row yet counts as 0, so nothing of its entity is deleted until it has
 * progressed). An entity with no registered bean is never trimmed: a bean that comes back must find its rows (SS-0008
 * D1). Each (schema, entity) is its own transaction; one that fails is said and the others go on. Keeps the table
 * bounded without holding up any bean's transaction. A schema billing-core has not served yet has no outbox: nothing
 * to trim there.
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

    private final OutboxReader reader;
    private final SummaryBeanRegistry registry;
    private final Optional<String> retiredEntityType;
    private final int intervalSeconds;
    private ScheduledExecutorService scheduler;
    /** The (schema, entity) pairs whose passes are failing, and how many in a row (the reaper's own thread, or a test's). */
    private final Map<Trimmed, Integer> failingPasses = new ConcurrentHashMap<>();

    @Inject
    public OutboxReaper(OutboxReader reader, SummaryBeanRegistry registry,
                        @ConfigProperty(name = RETIRED_ENTITY_TYPE_KEY) Optional<String> retiredEntityType,
                        @ConfigProperty(name = "summary.outbox.reaper-interval-seconds", defaultValue = "60") int intervalSeconds) {
        this.reader = reader;
        this.registry = registry;
        this.retiredEntityType = retiredEntityType;
        this.intervalSeconds = intervalSeconds;
    }

    /** A reaper whose profile does not set the retired key (tests, embedders). */
    public OutboxReaper(OutboxReader reader, SummaryBeanRegistry registry, int intervalSeconds) {
        this(reader, registry, Optional.empty(), intervalSeconds);
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
        LOG.infof("reaper started: every %ds, each entity that has a registered bean (now %s) trimmed by its own beans' bookmarks",
                intervalSeconds, registry.registeredBeanNamesByEntity().keySet());
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
        int deleted = 0;
        for (String schema : registry.servedSchemas()) {
            deleted += reapSchema(schema, beansByEntity);
        }
        return deleted;
    }

    /** Each entity by its OWN beans' bookmarks: one entity's watermark is never another's. */
    private int reapSchema(String schema, Map<String, Set<String>> beansByEntity) {
        int deleted = 0;
        for (Map.Entry<String, Set<String>> entity : beansByEntity.entrySet()) {
            deleted += reapEntity(new Trimmed(schema, entity.getKey()), entity.getValue());
        }
        return deleted;
    }

    /** One (schema, entity), its own transaction: a failure is said once for it, and the others go on. */
    private int reapEntity(Trimmed trimmed, Set<String> itsBeans) {
        try {
            int deleted = reader.reap(trimmed.schemaArgument(), trimmed.entityType(), itsBeans);
            sayItTrimsAgain(trimmed);
            return deleted;
        } catch (RuntimeException failure) {
            sayTheTrouble(trimmed, failure);
            return 0;
        }
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
