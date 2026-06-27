package com.telcobright.summary.registry.internal;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.context.api.ContextRegistry;
import com.telcobright.summary.outbox.internal.OutboxReaper;
import com.telcobright.summary.ping.internal.PingListener;
import com.telcobright.summary.registry.api.SummaryBeanRegistry;
import com.telcobright.summary.registry.spi.BeanConfig;
import com.telcobright.summary.registry.spi.SummaryBeanFactory;

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
 * At startup: read {@code summary.enabledSummary}, build each named bean from its {@code summary.beans.<name>}
 * config via the matching {@link SummaryBeanFactory}, ensure its context is loaded, and register it. Only when
 * {@code summary.autostart=true} does it start the per-bean workers, the ping listener, and the reaper — so the
 * app boots cleanly with NO MySQL/Kafka/config-manager needed; the architect flips autostart on at cutover.
 */
@ApplicationScoped
public class SummaryBootstrap {

    private static final Logger LOG = Logger.getLogger(SummaryBootstrap.class);

    private final SummaryBeanRegistry registry;
    private final Instance<SummaryBeanFactory> factories;
    private final ContextRegistry contexts;
    private final OutboxReaper reaper;
    private final PingListener pingListener;
    private final boolean autostart;

    @Inject
    public SummaryBootstrap(SummaryBeanRegistry registry,
                            @Any Instance<SummaryBeanFactory> factories,
                            ContextRegistry contexts,
                            OutboxReaper reaper,
                            PingListener pingListener,
                            @ConfigProperty(name = "summary.autostart", defaultValue = "false") boolean autostart) {
        this.registry = registry;
        this.factories = factories;
        this.contexts = contexts;
        this.reaper = reaper;
        this.pingListener = pingListener;
        this.autostart = autostart;
    }

    void onStart(@Observes StartupEvent event) {
        Config config = ConfigProvider.getConfig();
        List<String> enabled = config.getOptionalValues("summary.enabledSummary", String.class).orElse(List.of());
        for (String name : enabled) {
            configureBean(config, name);
        }
        if (autostart) {
            pingListener.start();
            reaper.start();
        } else {
            LOG.infof("autostart off — %d bean(s) registered; workers/ping/reaper NOT started", enabled.size());
        }
    }

    private void configureBean(Config config, String name) {
        try {
            BeanConfig beanConfig = readBeanConfig(config, name);
            SummaryBean<?> bean = factoryFor(beanConfig.entity()).create(beanConfig);
            registry.register(bean);
            if (beanConfig.context() != null) {
                contexts.ensureLoaded(beanConfig.context());   // best-effort; not load-bearing for CDR v1
            }
            if (autostart) {
                registry.start(name);
            } else {
                LOG.infof("bean registered (not started): name=%s entity=%s window=%s table=%s",
                        name, beanConfig.entity(), beanConfig.window(), beanConfig.table());
            }
        } catch (RuntimeException e) {
            LOG.errorf(e, "could not configure summary bean '%s'", name);
        }
    }

    private static BeanConfig readBeanConfig(Config config, String name) {
        String prefix = "summary.beans." + name + ".";
        return new BeanConfig(
                name,
                config.getValue(prefix + "entity", String.class),
                WindowSize.parse(config.getValue(prefix + "window", String.class)),
                config.getValue(prefix + "table", String.class),
                config.getOptionalValue(prefix + "service-group", Integer.class).orElse(null),
                config.getOptionalValue(prefix + "context", String.class).orElse(null));
    }

    private SummaryBeanFactory factoryFor(String entity) {
        for (SummaryBeanFactory factory : factories) {
            if (factory.entity().equals(entity)) {
                return factory;
            }
        }
        throw new IllegalArgumentException("no summary bean factory for entity: " + entity);
    }
}
