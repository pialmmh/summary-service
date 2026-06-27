package com.telcobright.summary.context.api;

import com.telcobright.summary.context.cdr.MediationContext;
import com.telcobright.summary.context.internal.ConfigManagerClient;
import com.telcobright.summary.context.spi.SummaryContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Loads each {@code summary.contexts.<name>} ONCE (lazily, on first request) and shares it read-only across
 * the beans that reference it. v1 supports {@code source: config-manager} → a {@link MediationContext}. Loading
 * is best-effort and non-fatal (the CDR build does not depend on it per the pinned contract).
 */
@ApplicationScoped
public class ContextRegistry {

    private static final Logger LOG = Logger.getLogger(ContextRegistry.class);

    private final ConfigManagerClient configManager;
    private final Map<String, SummaryContext> contexts = new ConcurrentHashMap<>();

    @Inject
    public ContextRegistry(ConfigManagerClient configManager) {
        this.configManager = configManager;
    }

    /** Load the named context if not already loaded (idempotent); returns it (possibly an unloaded marker). */
    public SummaryContext ensureLoaded(String name) {
        return contexts.computeIfAbsent(name, this::load);
    }

    public SummaryContext get(String name) {
        return contexts.get(name);
    }

    private SummaryContext load(String name) {
        Config config = ConfigProvider.getConfig();
        String source = config.getOptionalValue("summary.contexts." + name + ".source", String.class).orElse("");
        if (!"config-manager".equals(source)) {
            LOG.warnf("context %s has unsupported source '%s' — skipping", name, source);
            return new MediationContext(name, null, false, null);
        }
        String baseUrl = config.getOptionalValue("summary.contexts." + name + ".base-url", String.class).orElse(null);
        String tenant = config.getOptionalValue("summary.contexts." + name + ".tenant", String.class).orElse(null);
        if (baseUrl == null || tenant == null) {
            LOG.warnf("context %s missing base-url/tenant — not loaded", name);
            return new MediationContext(name, tenant, false, null);
        }
        Optional<String> raw = configManager.fetchTenantRoot(baseUrl, tenant);
        LOG.infof("context %s: loaded=%s tenant=%s", name, raw.isPresent(), tenant);
        return new MediationContext(name, tenant, raw.isPresent(), raw.orElse(null));
    }
}
