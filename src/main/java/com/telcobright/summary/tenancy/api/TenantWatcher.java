package com.telcobright.summary.tenancy.api;

import com.telcobright.summary.registry.api.SummaryBeanRegistry;
import com.telcobright.summary.tenancy.internal.DoorbellListener;
import com.telcobright.summary.tenancy.internal.PrimeContextTreeSource;
import com.telcobright.summary.tenancy.spi.TenantTreeSource;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Decides WHICH tenant schemas this process serves, and keeps that true (brief S6).
 *
 * <ul>
 *   <li>{@code summary.tenants.mode: single} (the default) — one schema, the connection's own: a one-tenant
 *       deployment, as before;</li>
 *   <li>{@code summary.tenants.mode: tree} — every schema of the ROOT tenant's tree ({@code summary.tenants.root}),
 *       read from prime-context. The tree is read at the start, again at every DOORBELL (Kafka
 *       {@code config_event_loader_<root>}, rung by prime-context AFTER it rebuilt the tree — a reseller provisioned
 *       at run time is in the tree by then), and every {@code refresh-seconds} whether a doorbell came or not. A
 *       schema the tree gained is served — its tables made, its bookmarks decided, its workers started — with no
 *       restart; one the tree lost is no longer served (its bookmarks and tables stay).</li>
 * </ul>
 *
 * A tree that cannot be read changes nothing: the schemas already served go on, the failure is said, and the read
 * is tried again at the next doorbell or refresh. A schema that cannot be served (it does not exist yet, a table
 * cannot be made) is said and tried again at the next read; the other schemas are not held up by it.
 */
@ApplicationScoped
public class TenantWatcher {

    private static final Logger LOG = Logger.getLogger(TenantWatcher.class);
    /** A schema's name as it goes into SQL unquoted. A name the tree gives that is not one is never served. */
    private static final Pattern SCHEMA_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    /** How soon a tree that could not be read is asked again, when the refresh is slower than that. */
    private static final int RETRY_SECONDS = 15;

    private final SummaryBeanRegistry registry;
    private final boolean tree;
    private final String root;
    private final TenantTreeSource source;
    private final long debounceMillis;
    private final int refreshSeconds;
    private final DoorbellListener doorbell;
    private final AtomicBoolean reloadPending = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    private volatile boolean everLoaded;

    @Inject
    public TenantWatcher(SummaryBeanRegistry registry) {
        this(registry, ConfigProvider.getConfig());
    }

    private TenantWatcher(SummaryBeanRegistry registry, Config config) {
        this.registry = registry;
        this.tree = "tree".equalsIgnoreCase(text(config, "summary.tenants.mode", "single"));
        this.root = text(config, "summary.tenants.root", null);
        this.debounceMillis = config.getOptionalValue("summary.tenants.reload-debounce-ms", Long.class).orElse(1000L);
        this.refreshSeconds = config.getOptionalValue("summary.tenants.refresh-seconds", Integer.class).orElse(300);
        if (tree) {
            String baseUrl = text(config, "summary.tenants.prime-context.base-url", null);
            if (root == null || baseUrl == null) {
                throw new IllegalStateException("summary.tenants.mode is tree: summary.tenants.root and "
                        + "summary.tenants.prime-context.base-url must both be set (the root tenant's schema, and where prime-context listens)");
            }
            this.source = new PrimeContextTreeSource(baseUrl, root);
            this.doorbell = new DoorbellListener(
                    text(config, "summary.tenants.doorbell.topic-base", "config_event_loader") + "_" + root,
                    text(config, "summary.tenants.doorbell.bootstrap-servers", text(config, "summary.outbox.ping-bootstrap-servers", "127.0.0.1:9092")),
                    () -> requestReload("the doorbell"));
        } else {
            this.source = null;
            this.doorbell = null;
        }
    }

    /** A watcher over a given tree source, with no doorbell and no timer of its own (tests drive {@link #reloadNow()}). */
    public TenantWatcher(SummaryBeanRegistry registry, String root, TenantTreeSource source) {
        this.registry = registry;
        this.tree = true;
        this.root = root;
        this.source = source;
        this.debounceMillis = 0;
        this.refreshSeconds = 0;
        this.doorbell = null;
    }

    /** Serve what is to be served now, and keep watching (tree mode). Called once, when the workers may start. */
    public synchronized void start() {
        if (!tree) {
            registry.serve(SummaryBeanRegistry.OWN_SCHEMA);
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "summary-tenants");
            t.setDaemon(true);
            return t;
        });
        if (doorbell != null) {
            doorbell.start();                                  // listening BEFORE the first read: a ring during it is not lost
        }
        scheduler.execute(() -> reloadQuietly("the start"));
        int every = refreshSeconds > 0 ? refreshSeconds : 0;
        if (every > 0) {
            scheduler.scheduleWithFixedDelay(() -> reloadQuietly("the refresh"), every, every, TimeUnit.SECONDS);
        }
        LOG.infof("tenants: the tree of root %s — read at the start, at every doorbell, and every %s", root,
                every > 0 ? every + "s" : "doorbell only (no refresh)");
    }

    /** A doorbell rang (or a caller asks): read the tree soon. Several asks in a burst are ONE read. */
    public void requestReload(String why) {
        ScheduledExecutorService runner = scheduler;
        if (runner == null || !reloadPending.compareAndSet(false, true)) {
            return;
        }
        runner.schedule(() -> {
            reloadPending.set(false);
            reloadQuietly(why);
        }, debounceMillis, TimeUnit.MILLISECONDS);
    }

    private void reloadQuietly(String why) {
        try {
            reloadNow();
        } catch (Exception failure) {
            LOG.errorf(failure, "tenants: the tree of %s could not be read (%s) — the %d schema(s) served go on; asked again at the next "
                    + "doorbell or refresh", root, why, registry.servedSchemas().size());
            ScheduledExecutorService runner = scheduler;
            if (runner != null && !everLoaded && (refreshSeconds == 0 || refreshSeconds > RETRY_SECONDS)) {
                runner.schedule(() -> reloadQuietly("the retry of a first read"), RETRY_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Read the tree and make the served schemas equal to it. Returns the schemas served after the call. Throws
     * when the tree cannot be read — and then nothing was changed.
     */
    public synchronized Set<String> reloadNow() throws Exception {
        List<String> named = source.schemas();
        Set<String> wanted = new LinkedHashSet<>();
        for (String schema : named) {
            if (SCHEMA_NAME.matcher(schema).matches()) {
                wanted.add(schema);
            } else {
                LOG.errorf("tenants: the tree names '%s' — not a schema's name (letters, digits and _): it is NOT served", schema);
            }
        }
        if (wanted.isEmpty()) {
            throw new IllegalStateException("the tree of " + root + " names no schema that can be served");
        }
        everLoaded = true;
        for (String schema : wanted) {
            try {
                registry.serve(schema);                        // already served: only the workers it still lacks
            } catch (RuntimeException failure) {
                LOG.errorf(failure, "tenants: schema %s could NOT be served (is it provisioned? may summary_service create in it?) — "
                        + "the other schemas go on; it is tried again at the next read of the tree", schema);
            }
        }
        for (String schema : registry.servedSchemas()) {
            if (!wanted.contains(schema)) {
                registry.unserve(schema);                      // the tree lost it
            }
        }
        return registry.servedSchemas();
    }

    @PreDestroy
    public synchronized void stop() {
        if (doorbell != null) {
            doorbell.stop();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private static String text(Config config, String key, String otherwise) {
        return config.getOptionalValue(key, String.class).map(String::trim).filter(v -> !v.isEmpty()).orElse(otherwise);
    }
}
