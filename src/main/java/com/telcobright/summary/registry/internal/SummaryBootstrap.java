package com.telcobright.summary.registry.internal;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.context.api.ContextRegistry;
import com.telcobright.summary.outbox.internal.OutboxReaper;
import com.telcobright.summary.ping.internal.PingListener;
import com.telcobright.summary.registry.api.SummaryBeanRegistry;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * At startup: read {@code summary.enabledSummary}, find each named bean among the CDI-discovered
 * {@link SummaryBean}s (the per-window summary classes — {@code summarybeans/<category>/…}), ensure its context
 * is loaded, and register it. Only when {@code summary.autostart=true} does it start the per-bean workers, the
 * ping listener, and the reaper — so the app boots cleanly with NO MySQL/Kafka/config-manager needed; the
 * architect flips autostart on at cutover.
 *
 * <p>A new summary bean = a new {@code @Singleton SummaryBean} class in its category package; no factory, no
 * registration code — list its {@link SummaryBean#name()} in {@code enabledSummary} to activate it.
 */
@ApplicationScoped
public class SummaryBootstrap {

    private static final Logger LOG = Logger.getLogger(SummaryBootstrap.class);

    private final SummaryBeanRegistry registry;
    private final Instance<SummaryBean<?>> discoveredBeans;
    private final ContextRegistry contexts;
    private final OutboxReaper reaper;
    private final PingListener pingListener;
    private final boolean autostart;

    @Inject
    public SummaryBootstrap(SummaryBeanRegistry registry,
                            @Any Instance<SummaryBean<?>> discoveredBeans,
                            ContextRegistry contexts,
                            OutboxReaper reaper,
                            PingListener pingListener,
                            @ConfigProperty(name = "summary.autostart", defaultValue = "false") boolean autostart) {
        this.registry = registry;
        this.discoveredBeans = discoveredBeans;
        this.contexts = contexts;
        this.reaper = reaper;
        this.pingListener = pingListener;
        this.autostart = autostart;
    }

    void onStart(@Observes StartupEvent event) {
        Config config = ConfigProvider.getConfig();
        List<String> enabled = config.getOptionalValues("summary.enabledSummary", String.class).orElse(List.of());
        for (String name : enabled) {
            activateBean(name);
        }
        if (autostart) {
            pingListener.start();
            reaper.start();
        } else {
            LOG.infof("autostart off — %d bean(s) registered; workers/ping/reaper NOT started", enabled.size());
        }
    }

    private void activateBean(String name) {
        try {
            SummaryBean<?> bean = findByName(name);
            if (bean == null) {
                LOG.errorf("enabledSummary '%s' has no matching SummaryBean class on the classpath", name);
                return;
            }
            if (bean.table() == null) {
                LOG.errorf("summary bean '%s' has no summary.beans.%s.table configured — skipping", name, name);
                return;
            }
            registry.register(bean);
            if (bean.contextName() != null) {
                contexts.ensureLoaded(bean.contextName());   // best-effort; not load-bearing for the call build
            }
            if (autostart) {
                registry.start(name);
            } else {
                LOG.infof("bean registered (not started): name=%s entity=%s window=%s table=%s",
                        name, bean.entityType(), bean.window(), bean.table());
            }
        } catch (RuntimeException e) {
            LOG.errorf(e, "could not activate summary bean '%s'", name);
        }
    }

    private SummaryBean<?> findByName(String name) {
        for (SummaryBean<?> bean : discoveredBeans) {
            if (bean.name().equals(name)) {
                return bean;
            }
        }
        return null;
    }
}
