package com.telcobright.summary.registry.api;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryEntity;
import com.telcobright.summary.registry.internal.KafkaConsumerFactory;
import com.telcobright.summary.registry.internal.SummaryWorker;
import com.telcobright.summary.runtime.api.BatchRunner;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.Consumer;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the configured beans and their running workers. A bean can be {@link #start started} or
 * {@link #stop stopped} at runtime — the hot-start hook: a newly registered bean gets its own consumer +
 * worker thread with NO app restart. v1 registers the {@code enabledSummary} beans at startup (see
 * {@code SummaryBootstrap}); the UI-defined-beans phase will register beans here at runtime.
 */
@ApplicationScoped
public class SummaryBeanRegistry {

    private static final Logger LOG = Logger.getLogger(SummaryBeanRegistry.class);

    private final KafkaConsumerFactory consumerFactory;
    private final BatchRunner batchRunner;
    private final Map<String, SummaryBean<?>> beans = new ConcurrentHashMap<>();
    private final Map<String, RunningWorker> workers = new ConcurrentHashMap<>();

    @Inject
    public SummaryBeanRegistry(KafkaConsumerFactory consumerFactory, BatchRunner batchRunner) {
        this.consumerFactory = consumerFactory;
        this.batchRunner = batchRunner;
    }

    /** Make a bean known (idempotent). Does NOT start it. */
    public void register(SummaryBean<?> bean) {
        beans.put(bean.name(), bean);
    }

    public Set<String> beanNames() {
        return Set.copyOf(beans.keySet());
    }

    public boolean isRunning(String beanName) {
        return workers.containsKey(beanName);
    }

    /** name -> running, for every registered bean. */
    public Map<String, Boolean> status() {
        Map<String, Boolean> status = new LinkedHashMap<>();
        beans.keySet().forEach(name -> status.put(name, workers.containsKey(name)));
        return status;
    }

    /** Start a registered bean's worker (idempotent — a no-op if already running). */
    public synchronized void start(String beanName) {
        SummaryBean<?> bean = beans.get(beanName);
        if (bean == null) {
            throw new IllegalArgumentException("unknown summary bean: " + beanName);
        }
        if (workers.containsKey(beanName)) {
            return;
        }
        startWorker(bean);
    }

    public synchronized void stop(String beanName) {
        RunningWorker running = workers.remove(beanName);
        if (running == null) {
            return;
        }
        running.worker().stop();
        try {
            running.thread().join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void stopAll() {
        Set.copyOf(workers.keySet()).forEach(this::stop);
    }

    private <T extends SummaryEntity<T>> void startWorker(SummaryBean<T> bean) {
        Consumer<String, byte[]> consumer = consumerFactory.create(bean);
        SummaryWorker<T> worker = new SummaryWorker<>(bean, consumer, batchRunner);
        Thread thread = new Thread(worker, "summary-worker-" + bean.name());
        thread.setDaemon(true);
        workers.put(bean.name(), new RunningWorker(worker, thread));
        thread.start();
    }

    private record RunningWorker(SummaryWorker<?> worker, Thread thread) {
    }
}
