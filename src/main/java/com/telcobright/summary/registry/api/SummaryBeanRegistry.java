package com.telcobright.summary.registry.api;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryEntity;
import com.telcobright.summary.outbox.api.OutboxReader;
import com.telcobright.summary.registry.internal.OutboxWorker;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the configured beans, the tenant schemas this process serves, and one running outbox worker per
 * <b>(schema, bean)</b> (brief S6). A call or an ad view writes one outbox row in EACH tier's schema, so one
 * process serves every schema of its root's tree: each (schema, bean) has its own worker thread, its own bookmark
 * (the schema's {@code summary_offset}) and its own transaction — single instance per root, so one worker is the
 * only consumer of its bookmark.
 *
 * <p>A schema is {@link #serve served} when it is first seen — at the start, or later when the tree gains a
 * reseller: its infra tables and each bean's table are made THEN (first use), its bookmarks are decided once for
 * all its beans (brief S7), and its workers start; no restart. A bean can still be {@link #start started} or
 * {@link #stop stopped} at run time (the hot-start hook) — on every served schema. A ping {@link #wake wakes} the
 * workers of the tenant it names.
 *
 * <p>A one-tenant deployment serves ONE schema: the connection's own ({@link #OWN_SCHEMA}), entered by nobody —
 * exactly as before the tree existed.
 */
@ApplicationScoped
public class SummaryBeanRegistry {

    /** The connection's own schema — what a deployment without a tree serves. */
    public static final String OWN_SCHEMA = "";

    private static final Logger LOG = Logger.getLogger(SummaryBeanRegistry.class);

    private final OutboxReader reader;
    private final int pollIntervalSeconds;
    private final Map<String, SummaryBean<?>> beans = new ConcurrentHashMap<>();
    private final Map<WorkerKey, RunningWorker> workers = new ConcurrentHashMap<>();
    private final Set<String> served = ConcurrentHashMap.newKeySet();

    @Inject
    public SummaryBeanRegistry(OutboxReader reader,
                               @ConfigProperty(name = "summary.outbox.poll-interval-seconds", defaultValue = "5") int pollIntervalSeconds) {
        this.reader = reader;
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    public void register(SummaryBean<?> bean) {
        beans.put(bean.name(), bean);
    }

    public Set<String> beanNames() {
        return Set.copyOf(beans.keySet());
    }

    /** Is this bean's worker running in at least one served schema? */
    public boolean isRunning(String beanName) {
        return workers.keySet().stream().anyMatch(key -> key.bean().equals(beanName));
    }

    /** Is the worker of this bean running in this schema? */
    public boolean isRunning(String schema, String beanName) {
        return workers.containsKey(new WorkerKey(schema, beanName));
    }

    public Map<String, Boolean> status() {
        Map<String, Boolean> status = new LinkedHashMap<>();
        beans.keySet().forEach(name -> status.put(name, isRunning(name)));
        return status;
    }

    /** The schemas this process serves now ({@link #OWN_SCHEMA} for a one-tenant deployment). */
    public Set<String> servedSchemas() {
        return Set.copyOf(served);
    }

    /** The names of running beans consuming the given outbox entity_type (in any schema). */
    public Set<String> runningBeanNames(String entityType) {
        Set<String> names = new LinkedHashSet<>();
        workers.forEach((key, running) -> {
            if (running.bean().entityType().equals(entityType)) {
                names.add(key.bean());
            }
        });
        return names;
    }

    /**
     * The names of ALL registered (configured) beans for the entity_type — the reaper's watermark set
     * (work order §5.2, resolving Q2): a hot-STOPPED bean's unread rows must survive until it catches up or
     * its offset row is explicitly decommissioned, so the watermark covers configured beans, not just
     * currently-running workers. A registered bean with no offset row yet pins the watermark at 0.
     */
    public Set<String> registeredBeanNames(String entityType) {
        Set<String> names = new LinkedHashSet<>();
        beans.forEach((name, bean) -> {
            if (bean.entityType().equals(entityType)) {
                names.add(name);
            }
        });
        return names;
    }

    /**
     * Serve a tenant schema: make what is the service's own there, decide the bookmarks, start a worker per
     * configured bean. Safe to call again — a schema already served only gets the workers it still lacks (a bean
     * whose table could not be made last time is tried again). Returns the beans whose worker runs after the call.
     *
     * <p>The order is the rule of S7: the infra tables, then the bookmarks of ALL beans in one transaction, then
     * the workers. A bean's failure (its table cannot be made) stops that bean only, and is said.
     */
    public synchronized List<String> serve(String schema) {
        String key = schema == null ? OWN_SCHEMA : schema;
        reader.ensureInfraTables(argOf(key));                 // summary_offset / DLQ (+ MySQL's dev outbox) — once per schema
        reader.seedBookmarks(argOf(key), beans.values());     // 0 for a schema never served, the head for a late-enabled bean
        boolean first = served.add(key);
        List<String> running = new ArrayList<>();
        for (SummaryBean<?> bean : beans.values()) {
            if (startIfNotRunning(key, bean)) {
                running.add(bean.name());
            }
        }
        if (first) {
            LOG.infof("schema %s is served: %d of %d bean(s) running", saidOf(key), running.size(), beans.size());
        }
        return running;
    }

    /** Stop serving a schema: its workers stop; its bookmarks and tables stay as they are. */
    public synchronized void unserve(String schema) {
        String key = schema == null ? OWN_SCHEMA : schema;
        for (WorkerKey worker : List.copyOf(workers.keySet())) {
            if (worker.schema().equals(key)) {
                stopWorker(worker);
            }
        }
        if (served.remove(key)) {
            LOG.infof("schema %s is no longer served: its workers are stopped, its bookmarks stay", saidOf(key));
        }
    }

    /** Hot-start a configured bean: its worker starts in every served schema (a late-enabled bean: from the head there). */
    public synchronized void start(String beanName) {
        SummaryBean<?> bean = beans.get(beanName);
        if (bean == null) {
            throw new IllegalArgumentException("unknown summary bean: " + beanName);
        }
        for (String schema : served) {
            reader.seedBookmarks(argOf(schema), List.of(bean));
            startIfNotRunning(schema, bean);
        }
    }

    /** Hot-stop a bean: its worker stops in every schema (its unread outbox rows are kept: it is still configured). */
    public synchronized void stop(String beanName) {
        for (WorkerKey worker : List.copyOf(workers.keySet())) {
            if (worker.bean().equals(beanName)) {
                stopWorker(worker);
            }
        }
    }

    /** Nudge every running worker to drain now. */
    public void wakeAll() {
        workers.values().forEach(running -> running.worker().wake());
    }

    /**
     * A ping named a tenant and an entity (billing-core's {@code cdr_summary_ping}: {@code {tenant, entity, rows}},
     * the tenant = its schema): wake that schema's workers of that entity (brief S8). A one-tenant deployment's
     * workers serve the connection's own schema, whatever its name: they wake on every ping, as before. Returns
     * the number of workers woken.
     */
    public int wake(String tenant, String entityType) {
        int woken = 0;
        for (Map.Entry<WorkerKey, RunningWorker> entry : workers.entrySet()) {
            String schema = entry.getKey().schema();
            boolean itsTenant = schema.equals(OWN_SCHEMA) || schema.equals(tenant);
            boolean itsEntity = entityType == null || entityType.isBlank() || entry.getValue().bean().entityType().equals(entityType);
            if (itsTenant && itsEntity) {
                entry.getValue().worker().wake();
                woken++;
            }
        }
        return woken;
    }

    /** Stop every worker (the shutdown; a test's clean-up). */
    @PreDestroy
    public void stopAll() {
        List.copyOf(workers.keySet()).forEach(this::stopWorker);
    }

    /** True when the bean's worker runs in the schema after the call. */
    private boolean startIfNotRunning(String schema, SummaryBean<?> bean) {
        WorkerKey key = new WorkerKey(schema, bean.name());
        RunningWorker existing = workers.get(key);
        if (existing != null) {
            if (existing.thread().isAlive()) {
                return true;   // already running (or still winding down) — NEVER two workers on one bookmark
            }
            workers.remove(key);   // died with an uncaught error — respawn below
        }
        try {
            startWorker(schema, bean);
            return true;
        } catch (RuntimeException failure) {
            LOG.errorf(failure, "schema %s: bean '%s' could NOT be started (its table could not be made?) — the other beans go on; "
                    + "it is tried again when the schema is served again", saidOf(schema), bean.name());
            return false;
        }
    }

    private void stopWorker(WorkerKey key) {
        RunningWorker running = workers.get(key);
        if (running == null) {
            return;
        }
        running.worker().stop();
        try {
            running.thread().join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (running.thread().isAlive()) {
            // keep the entry: removing it would let a start spawn a SECOND worker over the same bookmark
            // (concurrent drains double-count). The worker checks its stop flag between per-tx steps,
            // so this only happens with one drain step still in flight.
            LOG.errorf("worker '%s' of schema %s did not stop within 5s — entry kept, a start is refused until it exits",
                    key.bean(), saidOf(key.schema()));
            return;
        }
        workers.remove(key);
    }

    private <T extends SummaryEntity<T>> void startWorker(String schema, SummaryBean<T> bean) {
        reader.ensureProvisioned(argOf(schema), bean);   // the bean's own table in this schema: made at its first use
        OutboxWorker<T> worker = new OutboxWorker<>(argOf(schema), bean, reader, pollIntervalSeconds);
        Thread thread = new Thread(worker, "summary-worker-" + (schema.equals(OWN_SCHEMA) ? "" : schema + "-") + bean.name());
        thread.setDaemon(true);
        // an Error (e.g. OOM on an oversized blob) escapes the worker's own catch: log FATAL and KEEP the
        // registry entry — the reaper keeps counting this bean, so its unread outbox rows are preserved
        // until an operator intervenes (a start respawns over a dead thread).
        thread.setUncaughtExceptionHandler((t, e) -> LOG.fatalf(e,
                "worker '%s' of schema %s DIED — offset frozen, outbox rows retained; fix the cause and start it again",
                bean.name(), saidOf(schema)));
        workers.put(new WorkerKey(schema, bean.name()), new RunningWorker(worker, thread, bean));
        thread.start();
    }

    /** The schema as the unit of work takes it: null = the connection's own. */
    private static String argOf(String schema) {
        return schema.equals(OWN_SCHEMA) ? null : schema;
    }

    private static String saidOf(String schema) {
        return schema.equals(OWN_SCHEMA) ? "(the connection's own)" : schema;
    }

    private record WorkerKey(String schema, String bean) {
    }

    private record RunningWorker(OutboxWorker<?> worker, Thread thread, SummaryBean<?> bean) {
    }
}
