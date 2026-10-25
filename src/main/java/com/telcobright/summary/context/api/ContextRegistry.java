package com.telcobright.summary.context.api;

import com.telcobright.summary.context.cdr.MediationContext;
import com.telcobright.summary.context.internal.ConfigManagerClient;
import com.telcobright.summary.context.spi.SummaryContext;
import com.telcobright.summary.treeroads.api.TreeRoadBearer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Loads each {@code summary.contexts.<name>} ONCE (lazily, on first request) and shares it read-only across
 * the beans that reference it. v1 supports {@code source: config-manager} → a {@link MediationContext}. Loading
 * is best-effort and non-fatal (the CDR build does not depend on it per the pinned contract).
 *
 * <p>HARDEN-2: a context's block may name the variable that holds the token for prime-context's tree roads
 * ({@code summary.contexts.<name>.token-ref: env:<VAR>}); the context is then asked with that bearer ({@link TreeRoadBearer}).
 * None named: asked with no Authorization header, as before. The start has said and checked it already ({@code SummaryBootstrap}).
 */
@ApplicationScoped
public class ContextRegistry {

    private static final Logger LOG = Logger.getLogger(ContextRegistry.class);

    private final ConfigManagerClient configManager;
    private final Supplier<Config> configuration;
    private final Function<String, String> environment;
    private final Map<String, SummaryContext> contexts = new ConcurrentHashMap<>();

    @Inject
    public ContextRegistry(ConfigManagerClient configManager) {
        this(configManager, ConfigProvider::getConfig, System::getenv);
    }

    /** Over a given configuration and environment (a test's, an embedder's): where a context's token variable is read. */
    public ContextRegistry(ConfigManagerClient configManager, Supplier<Config> configuration, Function<String, String> environment) {
        this.configManager = configManager;
        this.configuration = configuration;
        this.environment = environment;
    }

    /** Load the named context if not already loaded (idempotent); returns it (possibly an unloaded marker). */
    public SummaryContext ensureLoaded(String name) {
        return contexts.computeIfAbsent(name, this::load);
    }

    public SummaryContext get(String name) {
        return contexts.get(name);
    }

    private SummaryContext load(String name) {
        Config config = configuration.get();
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
        TreeRoadBearer bearer = TreeRoadBearer.of(config, "summary.contexts." + name + ".", environment);
        Optional<String> raw = configManager.fetchTenantRoot(baseUrl, tenant, bearer);
        LOG.infof("context %s: loaded=%s tenant=%s", name, raw.isPresent(), tenant);
        return new MediationContext(name, tenant, raw.isPresent(), raw.orElse(null));
    }
}
