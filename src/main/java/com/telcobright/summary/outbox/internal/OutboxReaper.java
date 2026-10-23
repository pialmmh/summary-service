package com.telcobright.summary.outbox.internal;

import com.telcobright.summary.registry.api.SummaryBeanRegistry;
import com.telcobright.summary.outbox.api.OutboxReader;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Trims the outbox of EVERY served tenant schema: every {@code reaper-interval-seconds}, in each schema, deletes
 * the {@code summary_affected} rows that ALL configured beans have passed there — {@code id <= min(last_offset)}
 * (a bean with no offset row yet counts as 0, so nothing is deleted until every bean has progressed). Each schema
 * is its own transaction; one that fails is said and the others go on. Keeps the tables bounded without holding
 * up any bean's transaction. A schema billing-core has not served yet has no outbox: nothing to trim there.
 *
 * <p>A schema that fails is said ONCE (a WARN) and once more when it trims again (an INFO) — not at every pass
 * while a database is away (brief S16); the passes in between are DEBUG lines. The reaper is not a worker: a
 * missed pass loses nothing, the next one trims what the last one could not.
 */
@ApplicationScoped
public class OutboxReaper {

    private static final Logger LOG = Logger.getLogger(OutboxReaper.class);

    private final OutboxReader reader;
    private final SummaryBeanRegistry registry;
    private final String entityType;
    private final int intervalSeconds;
    private ScheduledExecutorService scheduler;
    /** The schemas whose passes are failing, and how many in a row (the reaper's own thread, or a test's). */
    private final Map<String, Integer> failingPasses = new ConcurrentHashMap<>();

    @Inject
    public OutboxReaper(OutboxReader reader, SummaryBeanRegistry registry,
                        @ConfigProperty(name = "summary.outbox.entity-type", defaultValue = "cdr") String entityType,
                        @ConfigProperty(name = "summary.outbox.reaper-interval-seconds", defaultValue = "60") int intervalSeconds) {
        this.reader = reader;
        this.registry = registry;
        this.entityType = entityType;
        this.intervalSeconds = intervalSeconds;
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "summary-reaper");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::reapQuietly, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        LOG.infof("reaper started: entity=%s every %ds", entityType, intervalSeconds);
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

    /** One reap pass over every served schema; returns the rows deleted in all of them. Visible for tests. */
    public int reapOnce() {
        // watermark over the CONFIGURED beans (work order §5.2, Q2 resolved): a hot-stopped bean's offset
        // still gates deletion, so its unread rows survive until it catches up or is decommissioned
        Set<String> configured = registry.registeredBeanNames(entityType);
        int deleted = 0;
        for (String schema : registry.servedSchemas()) {
            try {
                deleted += reader.reap(schema.equals(SummaryBeanRegistry.OWN_SCHEMA) ? null : schema, entityType, configured);
                sayItTrimsAgain(schema);
            } catch (RuntimeException failure) {
                sayTheTrouble(schema, failure);
            }
        }
        return deleted;
    }

    private void sayTheTrouble(String schema, RuntimeException failure) {
        int passes = failingPasses.merge(schema, 1, Integer::sum);
        if (passes == 1) {
            LOG.warnf(failure, "reaper: schema %s failed this pass; the other schemas go on. It is tried again every pass, quietly: "
                    + "ONE line says when it trims again", said(schema));
        } else if (LOG.isDebugEnabled()) {
            LOG.debugf("reaper: schema %s failed again (%d passes): %s", said(schema), passes, failure.toString());
        }
    }

    private void sayItTrimsAgain(String schema) {
        Integer passes = failingPasses.remove(schema);
        if (passes != null) {
            LOG.infof("reaper: schema %s trims again — after %d failed pass%s", said(schema), passes, passes == 1 ? "" : "es");
        }
    }

    private static String said(String schema) {
        return schema.equals(SummaryBeanRegistry.OWN_SCHEMA) ? "(the connection's own)" : schema;
    }
}
