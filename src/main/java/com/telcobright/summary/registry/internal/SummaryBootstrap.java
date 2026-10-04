package com.telcobright.summary.registry.internal;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.config.internal.TenantProfileConfigSource;
import com.telcobright.summary.context.api.ContextRegistry;
import com.telcobright.summary.outbox.internal.OutboxReaper;
import com.telcobright.summary.ping.internal.PingListener;
import com.telcobright.summary.registry.api.SummaryBeanRegistry;
import com.telcobright.summary.runtime.internal.StoreDataSource;
import com.telcobright.summary.summarybeans.call.CallSummaries;
import com.telcobright.summary.tenancy.api.TenantWatcher;

import io.quarkus.runtime.Quarkus;
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
 * At startup: say what this start resolved — which tenant and which profile FILE (brief S10), then every endpoint
 * ({@link StartEndpoints}) — and refuse, in words, a start whose configuration cannot be right. Then read
 * {@code summary.enabledSummary}, find each named bean among the CDI-discovered {@link SummaryBean}s (the per-window
 * summary classes — {@code summarybeans/<category>/…}) and register it. Only when {@code summary.autostart=true} does it load the
 * beans' contexts, serve the tenant schemas ({@link TenantWatcher}: the connection's own, or every schema of the
 * root's tree — a worker per (schema, bean)), and start the ping listener and the reaper — so the app boots
 * cleanly with NO database, Kafka, config-manager or prime-context needed, and dials none of them; the architect
 * flips autostart on at cutover.
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
    private final TenantWatcher tenants;
    private final StoreDataSource store;
    private final boolean autostart;

    @Inject
    public SummaryBootstrap(SummaryBeanRegistry registry,
                            @Any Instance<SummaryBean<?>> discoveredBeans,
                            ContextRegistry contexts,
                            OutboxReaper reaper,
                            PingListener pingListener,
                            TenantWatcher tenants,
                            StoreDataSource store,
                            @ConfigProperty(name = "summary.autostart", defaultValue = "false") boolean autostart) {
        this.registry = registry;
        this.discoveredBeans = discoveredBeans;
        this.contexts = contexts;
        this.reaper = reaper;
        this.pingListener = pingListener;
        this.tenants = tenants;
        this.store = store;
        this.autostart = autostart;
    }

    void onStart(@Observes StartupEvent event) {
        Config config = ConfigProvider.getConfig();
        List<String> enabled = config.getOptionalValues("summary.enabledSummary", String.class).orElse(List.of());
        List<StartEndpoints.Endpoint> endpoints = StartEndpoints.resolve(config, enabled);
        if (config.getOptionalValue("summary.endpoints.print-only", Boolean.class).orElse(false)) {
            printWhatThisStartResolvedAndExit(config, endpoints);   // nothing is started, nothing is dialled
            return;
        }
        sayAndCheckWhatThisStartResolved(config, endpoints);        // a fault of the configuration refuses the start here, before anything is dialled
        registerBeans(enabled);
        if (!autostart) {
            LOG.infof("autostart off — %d bean(s) registered; no schema served, workers/ping/reaper NOT started, nothing dialled", enabled.size());
            return;
        }
        loadContextsOfRegisteredBeans();
        startServing();
    }

    private void registerBeans(List<String> enabled) {
        for (String name : enabled) {
            registerBean(name);
        }
    }

    /** The beans' shared contexts (config-manager): best-effort, not load-bearing — and dialled only when the workers start. */
    private void loadContextsOfRegisteredBeans() {
        for (SummaryBean<?> bean : registry.beans()) {
            if (bean.contextName() != null) {
                contexts.ensureLoaded(bean.contextName());
            }
        }
    }

    private void startServing() {
        tenants.start();        // serve the schemas: tables at first use, bookmarks, a worker per (schema, bean)
        pingListener.start();
        reaper.start();
    }

    /** The mark of the start's first line: which tenant, what named it, and which profile FILE was read (brief S10). */
    static final String PROFILE_MARK = "PROFILE";

    /**
     * BEFORE anything is dialled, the start's first lines: which profile file was read, then what it will dial — the
     * database, the brokers, each configuration source — and where it listens. They are said FIRST, so a refused
     * start has them in its log too. Then the checks, each a refusal in words that fails the start: a fault of the
     * profile, a lab start that would leave this machine ({@link StartEndpoints}), a fault of the store's configuration.
     */
    private void sayAndCheckWhatThisStartResolved(Config config, List<StartEndpoints.Endpoint> endpoints) {
        LOG.info(PROFILE_MARK + " " + profileReadFrom(config));
        endpoints.forEach(endpoint -> LOG.info(endpoint.line()));
        refuseAFaultOfTheProfile(config);
        if (config.getOptionalValue("summary.endpoints.loopback-only", Boolean.class).orElse(false)) {
            StartEndpoints.requireLoopbackOnly(endpoints);
        }
        LOG.info(store.checkAtStart(autostart));
    }

    /**
     * Print-only: the same lines on standard output (the lab script reads them), and an exit code — 0 = every host
     * is this machine and the configuration starts, 3 = a host is not, 4 = the configuration refuses the start.
     */
    private void printWhatThisStartResolvedAndExit(Config config, List<StartEndpoints.Endpoint> endpoints) {
        System.out.println(PROFILE_MARK + " " + profileReadFrom(config));
        endpoints.forEach(endpoint -> System.out.println(endpoint.line()));
        int elsewhere = StartEndpoints.notLoopback(endpoints).size();
        boolean configurationStarts = sayWhetherTheConfigurationStarts(config);
        System.out.println(StartEndpoints.LINE_MARK + "S " + endpoints.size() + " resolved, " + elsewhere + " not on this machine — print-only: nothing was started");
        Quarkus.asyncExit(elsewhere != 0 ? 3 : configurationStarts ? 0 : 4);
    }

    /** Which tenant this start serves, what named it, and which file its profile was read from — the profile source's own word. */
    private static String profileReadFrom(Config config) {
        return config.getOptionalValue(TenantProfileConfigSource.READ_FROM_KEY, String.class).orElse("no tenant profile was read");
    }

    /** What a person wrote wrong — a config directory that is none, a tenant with no profile anywhere, a file that cannot be parsed. */
    private static void refuseAFaultOfTheProfile(Config config) {
        config.getOptionalValue(TenantProfileConfigSource.FAULT_KEY, String.class).ifPresent(fault -> {
            throw new IllegalStateException("REFUSING TO START: " + fault);
        });
    }

    /**
     * Print-only: a {@code SECRET} line — where the store's password comes from, never its value — or, when the
     * configuration would refuse the start, the refusal as the start would say it ({@code REFUSING TO START: …}).
     */
    private boolean sayWhetherTheConfigurationStarts(Config config) {
        try {
            refuseAFaultOfTheProfile(config);
            System.out.println("SECRET " + store.checkAtStart(autostart));
            return true;
        } catch (IllegalStateException refusal) {
            System.out.println(refusal.getMessage());
            return false;
        }
    }

    @PreDestroy
    void onShutdown() {
        tenants.stop();        // the doorbell's consumer and the tree's timer
        pingListener.stop();   // close the Kafka consumer instead of dropping the socket
        reaper.stop();         // (workers are stopped by the registry's own @PreDestroy)
    }

    private void registerBean(String name) {
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
            LOG.infof("bean registered: name=%s entity=%s window=%s table=%s", name, bean.entityType(), bean.window(), bean.table());
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
