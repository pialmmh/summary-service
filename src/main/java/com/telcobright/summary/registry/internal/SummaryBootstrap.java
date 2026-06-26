package com.telcobright.summary.registry.internal;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.WindowSize;
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

import java.time.ZoneId;
import java.util.List;

/**
 * At startup, reads {@code summary.enabledSummary} (the ONLY beans that run), builds each named bean from its
 * {@code summary.beans.<name>} config via the matching {@link SummaryBeanFactory}, registers it, and — only if
 * {@code summary.autostart=true} — starts its worker. With autostart OFF (the default) the app boots cleanly
 * with NO Kafka/MySQL needed; the architect starts beans via the registry at cutover.
 */
@ApplicationScoped
public class SummaryBootstrap {

    private static final Logger LOG = Logger.getLogger(SummaryBootstrap.class);

    private final SummaryBeanRegistry registry;
    private final Instance<SummaryBeanFactory> factories;
    private final boolean autostart;

    @Inject
    public SummaryBootstrap(SummaryBeanRegistry registry,
                            @Any Instance<SummaryBeanFactory> factories,
                            @ConfigProperty(name = "summary.autostart", defaultValue = "false") boolean autostart) {
        this.registry = registry;
        this.factories = factories;
        this.autostart = autostart;
    }

    void onStart(@Observes StartupEvent event) {
        Config config = ConfigProvider.getConfig();
        List<String> enabled = config.getOptionalValues("summary.enabledSummary", String.class).orElse(List.of());
        for (String name : enabled) {
            configureBean(config, name);
        }
    }

    private void configureBean(Config config, String name) {
        try {
            BeanConfig beanConfig = readBeanConfig(config, name);
            SummaryBean<?> bean = factoryFor(beanConfig.entity()).create(beanConfig);
            registry.register(bean);
            if (autostart) {
                registry.start(name);
            } else {
                LOG.infof("bean registered (not started): name=%s entity=%s window=%s table=%s autostart=false",
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
                config.getValue(prefix + "topic", String.class),
                config.getOptionalValue(prefix + "correction-topic", String.class).orElse(null),
                config.getValue(prefix + "table", String.class),
                WindowSize.parse(config.getValue(prefix + "window", String.class)),
                config.getOptionalValue(prefix + "service-group", Integer.class).orElse(null),
                config.getOptionalValue(prefix + "batch-size", Integer.class).orElse(1000),
                ZoneId.of(config.getOptionalValue(prefix + "zone", String.class).orElse("Asia/Dhaka")));
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
