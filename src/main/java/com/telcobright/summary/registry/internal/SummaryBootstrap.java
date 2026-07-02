package com.telcobright.summary.registry.internal;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.context.api.ContextRegistry;
import com.telcobright.summary.outbox.internal.OutboxReaper;
import com.telcobright.summary.ping.internal.PingListener;
import com.telcobright.summary.registry.api.SummaryBeanRegistry;
import com.telcobright.summary.summarybeans.call.CallSummaries;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
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
 * registration code — list its {@link SummaryBean#name()} in {@code enabledSummary} to activate it. An enabled
 * name with NO catalog class but a {@code summary.beans.<name>.window} key is CONFIG-INSTANTIATED instead
 * (§12g) — an extra call-bean instance under its own name, e.g. the SG11 pair beside the SG10 catalog beans.
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

    @PreDestroy
    void onShutdown() {
        pingListener.stop();   // close the Kafka consumer instead of dropping the socket
        reaper.stop();         // (workers are stopped by the registry's own @PreDestroy)
    }

    private void activateBean(String name) {
        try {
            SummaryBean<?> bean = findByName(name);
            if (bean == null) {
                bean = configInstantiated(name);
            }
            if (bean == null) {
                LOG.errorf("enabledSummary '%s' matches no SummaryBean class and has no summary.beans.%s.window "
                        + "config to instantiate from", name, name);
                return;
            }
            bean.table();   // fail-fast probe: throws if table-suffix is missing/invalid (caught + logged below)
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

    /**
     * The config-instantiated path (§12g): an enabled name with no catalog class but a
     * {@code summary.beans.<name>.window} key becomes an EXTRA instance of the call category under its own
     * name — how a second service group (legacy covered SG10 AND SG11) gets its own worker/offset/table
     * without a per-SG class. v1 has one category; a {@code category:} key will select among factories
     * once a second category exists.
     */
    private SummaryBean<?> configInstantiated(String name) {
        Config config = ConfigProvider.getConfig();
        String window = config.getOptionalValue("summary.beans." + name + ".window", String.class).orElse(null);
        if (window == null) {
            return null;
        }
        Integer serviceGroup = config.getOptionalValue("summary.beans." + name + ".service-group", Integer.class).orElse(null);
        if (serviceGroup == null) {
            LOG.errorf("config-instantiated bean '%s' needs summary.beans.%s.service-group", name, name);
            return null;
        }
        String tableSuffix = config.getOptionalValue("summary.beans." + name + ".table-suffix", String.class).orElse(null);
        String context = config.getOptionalValue("summary.beans." + name + ".context", String.class).orElse(null);
        return CallSummaries.forWindow(name, window, tableSuffix, serviceGroup, context);
    }

    private SummaryBean<?> findByName(String name) {
        // iterate HANDLES so one bean whose own config fails to convert (e.g. a non-numeric service-group)
        // cannot break the lookup of every OTHER bean — it is skipped with a warning instead
        for (Instance.Handle<SummaryBean<?>> handle : discoveredBeans.handles()) {
            try {
                SummaryBean<?> bean = handle.get();
                if (bean.name().equals(name)) {
                    return bean;
                }
            } catch (RuntimeException e) {
                LOG.warnf("a summary bean failed to instantiate while resolving '%s' — skipping it: %s", name, e.toString());
            }
        }
        return null;
    }
}
