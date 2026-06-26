package com.telcobright.summary.registry.internal;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.registry.api.SummaryBeanRegistry;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * At startup, registers every compiled {@link SummaryBean} into the registry and — only if
 * {@code summary.autostart=true} — starts the ones whose {@code summary.beans.<name>.enabled=true}. With
 * autostart OFF (the default) the app boots cleanly with NO Kafka/MySQL needed; the architect starts beans
 * via the registry at cutover. The later UI-defined-beans phase registers beans here at runtime instead.
 */
@ApplicationScoped
public class SummaryBootstrap {

    private static final Logger LOG = Logger.getLogger(SummaryBootstrap.class);

    private final SummaryBeanRegistry registry;
    private final Instance<SummaryBean<?>> discoveredBeans;
    private final boolean autostart;

    @Inject
    public SummaryBootstrap(SummaryBeanRegistry registry,
                            @Any Instance<SummaryBean<?>> discoveredBeans,
                            @ConfigProperty(name = "summary.autostart", defaultValue = "false") boolean autostart) {
        this.registry = registry;
        this.discoveredBeans = discoveredBeans;
        this.autostart = autostart;
    }

    void onStart(@Observes StartupEvent event) {
        for (SummaryBean<?> bean : discoveredBeans) {
            registry.register(bean);
            boolean enabled = beanEnabled(bean.name());
            if (autostart && enabled) {
                tryStart(bean.name());
            } else {
                LOG.infof("bean registered: name=%s enabled=%s autostart=%s (worker not started)",
                        bean.name(), enabled, autostart);
            }
        }
    }

    private boolean beanEnabled(String beanName) {
        return ConfigProvider.getConfig()
                .getOptionalValue("summary.beans." + beanName + ".enabled", Boolean.class)
                .orElse(false);
    }

    private void tryStart(String beanName) {
        try {
            registry.start(beanName);
        } catch (RuntimeException e) {
            LOG.errorf(e, "could not start bean %s", beanName);
        }
    }
}
