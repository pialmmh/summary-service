package com.telcobright.summary.outbox.internal;

import com.telcobright.summary.registry.api.SummaryBeanRegistry;
import com.telcobright.summary.runtime.spi.UnitOfWork;
import com.telcobright.summary.runtime.spi.UnitOfWorkFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Trims the shared outbox: every {@code reaper-interval-seconds}, deletes {@code summary_affected} rows that
 * ALL running beans have passed — {@code id <= min(last_offset)} across the entity's active beans (a bean with
 * no offset row yet counts as 0, so nothing is deleted until every bean has progressed). Keeps the table
 * bounded without holding up any bean's transaction.
 */
@ApplicationScoped
public class OutboxReaper {

    private static final Logger LOG = Logger.getLogger(OutboxReaper.class);

    private final UnitOfWorkFactory unitOfWorkFactory;
    private final SummaryBeanRegistry registry;
    private final String entityType;
    private final int intervalSeconds;
    private ScheduledExecutorService scheduler;

    @Inject
    public OutboxReaper(UnitOfWorkFactory unitOfWorkFactory, SummaryBeanRegistry registry,
                        @ConfigProperty(name = "summary.outbox.entity-type", defaultValue = "cdr") String entityType,
                        @ConfigProperty(name = "summary.outbox.reaper-interval-seconds", defaultValue = "60") int intervalSeconds) {
        this.unitOfWorkFactory = unitOfWorkFactory;
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

    /** One reap pass; returns rows deleted. Visible for tests. */
    public int reapOnce() {
        // watermark over the CONFIGURED beans (work order §5.2, Q2 resolved): a hot-stopped bean's offset
        // still gates deletion, so its unread rows survive until it catches up or is decommissioned
        Set<String> activeBeans = registry.registeredBeanNames(entityType);
        if (activeBeans.isEmpty()) {
            return 0;
        }
        UnitOfWork unitOfWork = unitOfWorkFactory.begin();
        try {
            long min = unitOfWork.outbox().minOffset(entityType, activeBeans);
            int deleted = min > 0 ? unitOfWork.outbox().deleteUpTo(entityType, min) : 0;
            unitOfWork.commit();
            if (deleted > 0) {
                LOG.infof("reaper deleted %d outbox rows (entity=%s id<=%d)", deleted, entityType, min);
            }
            return deleted;
        } catch (RuntimeException failure) {
            try {
                unitOfWork.rollback();
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            unitOfWork.close();
        }
    }
}
