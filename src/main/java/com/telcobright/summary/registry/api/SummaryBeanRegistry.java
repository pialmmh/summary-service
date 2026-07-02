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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the configured beans and their running outbox workers. A bean can be {@link #start started} or
 * {@link #stop stopped} at runtime (the hot-start hook), and {@link #wakeAll woken} when a ping arrives. Each
 * worker is one daemon thread draining one bean's outbox; single-instance per tenant (Q1), so one worker per
 * bean is the only consumer of that bean's offset.
 */
@ApplicationScoped
public class SummaryBeanRegistry {

    private static final Logger LOG = Logger.getLogger(SummaryBeanRegistry.class);

    private final OutboxReader reader;
    private final int pollIntervalSeconds;
    private final Map<String, SummaryBean<?>> beans = new ConcurrentHashMap<>();
    private final Map<String, RunningWorker> workers = new ConcurrentHashMap<>();

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

    public boolean isRunning(String beanName) {
        return workers.containsKey(beanName);
    }

    public Map<String, Boolean> status() {
        Map<String, Boolean> status = new LinkedHashMap<>();
        beans.keySet().forEach(name -> status.put(name, workers.containsKey(name)));
        return status;
    }

    /** The names of running beans consuming the given outbox entity_type (the reaper's active-bean set). */
    public Set<String> runningBeanNames(String entityType) {
        Set<String> names = new LinkedHashSet<>();
        workers.forEach((name, running) -> {
            if (running.bean().entityType().equals(entityType)) {
                names.add(name);
            }
        });
        return names;
    }

    public synchronized void start(String beanName) {
        SummaryBean<?> bean = beans.get(beanName);
        if (bean == null) {
            throw new IllegalArgumentException("unknown summary bean: " + beanName);
        }
        RunningWorker existing = workers.get(beanName);
        if (existing != null) {
            if (existing.thread().isAlive()) {
                return;   // already running (or still winding down) — NEVER two workers on one bean's offset
            }
            workers.remove(beanName);   // died with an uncaught error — respawn below
        }
        startWorker(bean);
    }

    public synchronized void stop(String beanName) {
        RunningWorker running = workers.get(beanName);
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
            // keep the entry: removing it would let start() spawn a SECOND worker over the same offset
            // (concurrent drains double-count). The worker checks its stop flag between per-tx steps,
            // so this only happens with one drain step still in flight.
            LOG.errorf("worker '%s' did not stop within 5s — entry kept, start() refused until it exits", beanName);
            return;
        }
        workers.remove(beanName);
    }

    /** A ping arrived — nudge every running worker to drain now. */
    public void wakeAll() {
        workers.values().forEach(running -> running.worker().wake());
    }

    public void wake(String beanName) {
        RunningWorker running = workers.get(beanName);
        if (running != null) {
            running.worker().wake();
        }
    }

    @PreDestroy
    void stopAll() {
        Set.copyOf(workers.keySet()).forEach(this::stop);
    }

    private <T extends SummaryEntity<T>> void startWorker(SummaryBean<T> bean) {
        reader.initOffsetAtHead(bean);   // head-init BEFORE the first drain: a late-enabled bean starts from NOW
        OutboxWorker<T> worker = new OutboxWorker<>(bean, reader, pollIntervalSeconds);
        Thread thread = new Thread(worker, "summary-worker-" + bean.name());
        thread.setDaemon(true);
        // an Error (e.g. OOM on an oversized blob) escapes the worker's own catch: log FATAL and KEEP the
        // registry entry — the reaper keeps counting this bean, so its unread outbox rows are preserved
        // until an operator intervenes (start() respawns over a dead thread).
        thread.setUncaughtExceptionHandler((t, e) -> LOG.fatalf(e,
                "worker '%s' DIED — offset frozen, outbox rows retained; fix the cause and start() it again", bean.name()));
        workers.put(bean.name(), new RunningWorker(worker, thread, bean));
        thread.start();
    }

    private record RunningWorker(OutboxWorker<?> worker, Thread thread, SummaryBean<?> bean) {
    }
}
