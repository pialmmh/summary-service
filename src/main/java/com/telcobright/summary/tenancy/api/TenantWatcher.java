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
 * is tried again at the next doorbell or refresh. A schema that cannot be served (the store does not answer, the
 * schema does not exist yet, a table cannot be made) is said and tried again SOON ({@code retry-seconds}, each try
 * a little later, up to the refresh); the other schemas are not held up by it. That holds for the one schema of a
 * one-tenant deployment too: a store that is down when the service starts does not fail the start and does not
 * leave it idle — it is served when the store answers.
 */
@ApplicationScoped
public class TenantWatcher {

    private static final Logger LOG = Logger.getLogger(TenantWatcher.class);
    /** A schema's name as it goes into SQL unquoted. A name the tree gives that is not one is never served. */
    private static final Pattern SCHEMA_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    /** The longest wait between two tries when no refresh is configured. */
    private static final long LONGEST_RETRY_MILLIS = 300_000;

    private final SummaryBeanRegistry registry;
    private final boolean tree;
    private final String root;
    private final TenantTreeSource source;
    private final long debounceMillis;
    private final int refreshSeconds;
    private final long retryMillis;
    private final DoorbellListener doorbell;
    private final AtomicBoolean reloadPending = new AtomicBoolean(false);
    private final AtomicBoolean retryPending = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    private int triesInARow;

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
        this.retryMillis = config.getOptionalValue("summary.tenants.retry-seconds", Integer.class).orElse(15) * 1000L;
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

    /** A watcher over a given tree source, with no doorbell and no refresh (a test drives {@link #reloadNow()}). */
    public TenantWatcher(SummaryBeanRegistry registry, String root, TenantTreeSource source) {
        this(registry, root, source, 15_000);
    }

    /**
     * The same, trying again {@code retryMillis} after a read that left something unserved (once {@link #start}ed).
     * {@code source == null} = a one-tenant deployment: the one schema is the connection's own.
     */
    public TenantWatcher(SummaryBeanRegistry registry, String root, TenantTreeSource source, long retryMillis) {
        this.registry = registry;
        this.tree = source != null;
        this.root = root;
        this.source = source;
        this.debounceMillis = 0;
        this.refreshSeconds = 0;
        this.retryMillis = retryMillis;
        this.doorbell = null;
    }

    /**
     * Serve what is to be served, and keep it served. Called once, when the workers may start. Nothing is served on
     * the caller's thread: a store or a prime-context that does not answer never fails the start — it is tried again.
     */
    public synchronized void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "summary-tenants");
            t.setDaemon(true);
            return t;
        });
        if (doorbell != null) {
            doorbell.start();                                  // listening BEFORE the first read: a ring during it is not lost
        }
        scheduler.execute(() -> reloadQuietly("the start"));
        if (refreshSeconds > 0) {
            scheduler.scheduleWithFixedDelay(() -> reloadQuietly("the refresh"), refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
        }
        String again = refreshSeconds > 0 ? "every " + refreshSeconds + "s" : "at no fixed time (no refresh)";
        if (tree) {
            LOG.infof("tenants: the tree of root %s — read at the start, at every doorbell, and %s", root, again);
        } else {
            LOG.infof("tenants: ONE schema, the connection's own — served at the start, looked at again %s", again);
        }
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
            LOG.errorf(failure, "tenants: the tree of %s could not be read (%s) — the %d schema(s) served go on; asked again soon, "
                    + "and at the next doorbell or refresh", root, why, registry.servedSchemas().size());
            tryAgainSoon();
        }
    }

    /**
     * Read what is to be served and make the served schemas equal to it. Returns the schemas served after the call.
     * Throws when the tree cannot be read — and then nothing was changed.
     */
    public synchronized Set<String> reloadNow() throws Exception {
        Set<String> wanted = wantedSchemas();
        boolean everyOneServed = serveEach(wanted);
        stopWhatIsNoLongerWanted(wanted);
        if (everyOneServed) {
            triesInARow = 0;
        } else {
            tryAgainSoon();
        }
        return registry.servedSchemas();
    }

    /** One schema — the connection's own — without a tree; else every schema the tree names that IS a schema's name. */
    private Set<String> wantedSchemas() throws Exception {
        if (!tree) {
            return Set.of(SummaryBeanRegistry.OWN_SCHEMA);
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String schema : source.schemas()) {
            if (SCHEMA_NAME.matcher(schema).matches()) {
                wanted.add(schema);
            } else {
                LOG.errorf("tenants: the tree names '%s' — not a schema's name (letters, digits and _): it is NOT served", schema);
            }
        }
        if (wanted.isEmpty()) {
            throw new IllegalStateException("the tree of " + root + " names no schema that can be served");
        }
        return wanted;
    }

    /** Serve each wanted schema (one already served only gets the workers it lacks). False when one could not be. */
    private boolean serveEach(Set<String> wanted) {
        boolean every = true;
        for (String schema : wanted) {
            try {
                registry.serve(schema);
            } catch (RuntimeException failure) {
                every = false;
                LOG.errorf(failure, "tenants: schema %s could NOT be served (does the store answer? is the schema provisioned? may the "
                        + "service create in it?) — the other schemas go on; it is tried again soon", said(schema));
            }
        }
        return every;
    }

    private void stopWhatIsNoLongerWanted(Set<String> wanted) {
        for (String schema : registry.servedSchemas()) {
            if (!wanted.contains(schema)) {
                registry.unserve(schema);                      // the tree lost it
            }
        }
    }

    /** One more read after a wait that grows with each try in a row, up to the refresh. Several asks are ONE try. */
    private void tryAgainSoon() {
        ScheduledExecutorService runner = scheduler;
        if (runner == null || !retryPending.compareAndSet(false, true)) {
            return;                                            // not started (a test drives the reads), or a try is already waiting
        }
        triesInARow++;
        long longest = refreshSeconds > 0 ? refreshSeconds * 1000L : LONGEST_RETRY_MILLIS;
        long wait = Math.min(retryMillis * triesInARow, Math.max(retryMillis, longest));
        runner.schedule(() -> {
            retryPending.set(false);
            reloadQuietly("a retry");
        }, wait, TimeUnit.MILLISECONDS);
    }

    private static String said(String schema) {
        return schema.equals(SummaryBeanRegistry.OWN_SCHEMA) ? "(the connection's own)" : schema;
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
