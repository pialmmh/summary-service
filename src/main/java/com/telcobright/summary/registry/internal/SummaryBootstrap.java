package com.telcobright.summary.registry.internal;

import com.telcobright.summary.bean.spi.CrossKindReader;
import com.telcobright.summary.bean.spi.DdlPartitions;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryKind;
import com.telcobright.summary.config.internal.TenantProfileConfigSource;
import com.telcobright.summary.context.api.ContextRegistry;
import com.telcobright.summary.outbox.internal.OutboxReaper;
import com.telcobright.summary.ping.internal.PingListener;
import com.telcobright.summary.registry.api.SummaryBeanRegistry;
import com.telcobright.summary.runtime.internal.StoreDataSource;
import com.telcobright.summary.tenancy.api.TenantWatcher;
import com.telcobright.summary.treeroads.api.TreeRoadBearer;

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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntConsumer;

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
 * (§12g, {@link ConfigInstantiatedBeans}) — an extra instance under its own name: a call bean (e.g. the SG11 pair
 * beside the SG10 catalog beans), or, with {@code kind: wifi-session | wifi-dst}, a WiFi bean (SS-0006).
 *
 * <p>SS-0008 — the KINDS ({@code bean/spi/SummaryKind}: call, ad, wifi): the tenant's profile switches them on
 * ({@code summary.kinds}, {@link TenantKinds}; none named = the call alone, as before). Every enabled bean is of one kind
 * ({@link KnownKinds#kindOf}); a bean of a kind the tenant did not enable REFUSES the start, in words, before any bean is
 * registered — left out instead, its outbox rows would be trimmed past it and its summaries lost. The call kind's chargeable
 * roll-up ({@link CrossKindReader}) is told the enabled kinds' groups, and the rows of the other kinds are counted
 * ({@link KindGate}: {@code KIND_NOT_ENABLED}, once per tenant, kind and day).
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
    private final KindGate kindGate;
    private final boolean autostart;
    /** How a print-only start ends: the process exits with the code. (A test hears the code instead.) */
    IntConsumer exit = Quarkus::asyncExit;
    /** Where a start reads the variable of a tree road's token (HARDEN-2): the process's own environment. (A test gives its own.) */
    Function<String, String> environment = System::getenv;

    @Inject
    public SummaryBootstrap(SummaryBeanRegistry registry,
                            @Any Instance<SummaryBean<?>> discoveredBeans,
                            ContextRegistry contexts,
                            OutboxReaper reaper,
                            PingListener pingListener,
                            TenantWatcher tenants,
                            StoreDataSource store,
                            KindGate kindGate,
                            @ConfigProperty(name = "summary.autostart", defaultValue = "false") boolean autostart) {
        this.registry = registry;
        this.discoveredBeans = discoveredBeans;
        this.contexts = contexts;
        this.reaper = reaper;
        this.pingListener = pingListener;
        this.tenants = tenants;
        this.store = store;
        this.kindGate = kindGate;
        this.autostart = autostart;
    }

    /** The bootstrap of before SS-0008's gate (tests): its own gate, which no reader hears. */
    public SummaryBootstrap(SummaryBeanRegistry registry, Instance<SummaryBean<?>> discoveredBeans, ContextRegistry contexts, OutboxReaper reaper,
                            PingListener pingListener, TenantWatcher tenants, StoreDataSource store, boolean autostart) {
        this(registry, discoveredBeans, contexts, reaper, pingListener, tenants, store, new KindGate(), autostart);
    }

    void onStart(@Observes StartupEvent event) {
        Config config = ConfigProvider.getConfig();
        List<String> enabled = config.getOptionalValues("summary.enabledSummary", String.class).orElse(List.of());
        List<StartEndpoints.Endpoint> endpoints = StartEndpoints.resolve(config, enabled);
        if (config.getOptionalValue("summary.endpoints.print-only", Boolean.class).orElse(false)) {
            printWhatThisStartResolvedAndExit(config, endpoints, enabled);   // nothing is started, nothing is dialled
            return;
        }
        sayAndCheckWhatThisStartResolved(config, endpoints, enabled);   // a fault of the configuration refuses the start here, before anything is dialled
        registerTheBeansOfTheEnabledKinds(config, enabled);         // a bean of a kind the tenant did not enable refuses the start here
        if (!autostart) {
            LOG.infof("autostart off — %d bean(s) registered; no schema served, workers/ping/reaper NOT started, nothing dialled", enabled.size());
            return;
        }
        loadContextsOfRegisteredBeans();
        startServing();
    }

    /**
     * SS-0008: the tenant's kinds; a bean of a kind not enabled refuses the start BEFORE any bean is registered; then the
     * beans, the chargeable roll-up told the enabled kinds' groups, the gate told what to count, and the kinds said.
     */
    private void registerTheBeansOfTheEnabledKinds(Config config, List<String> enabled) {
        TenantKinds kinds = tenantKinds(config);
        List<SummaryBean<?>> beans = resolveBeans(enabled);
        refuseBeansOfKindsNotEnabled(beans, kinds);
        beans.forEach(this::register);
        readOnlyTheGroupsOfTheEnabledKinds(beans, kinds);
        kindGate.countFor(kinds, firstBeanOfTheCdrEntity(beans), DdlPartitions.tenantZone(config));
        LOG.info(kindsLine(kinds));
    }

    /** {@code summary.kinds} — a word that is no kind refuses the start, in words. */
    private static TenantKinds tenantKinds(Config config) {
        try {
            return TenantKinds.of(config);
        } catch (IllegalArgumentException noSuchKind) {
            throw new IllegalStateException("REFUSING TO START: " + noSuchKind.getMessage());
        }
    }

    /** The beans the enabled names make, in the profile's order; a name that makes none is said and skipped, as before. */
    private List<SummaryBean<?>> resolveBeans(List<String> enabled) {
        List<SummaryBean<?>> beans = new ArrayList<>();
        for (String name : enabled) {
            SummaryBean<?> bean = resolveBean(name);
            if (bean != null) {
                beans.add(bean);
            }
        }
        return beans;
    }

    /**
     * Every bean must be of a kind this tenant enabled. One that is not refuses the start, naming each such bean and its kind:
     * left out instead, it would not hold the outbox, so its rows would be trimmed past it and its summaries lost.
     */
    private static void refuseBeansOfKindsNotEnabled(List<SummaryBean<?>> beans, TenantKinds kinds) {
        List<String> refused = new ArrayList<>();
        for (SummaryBean<?> bean : beans) {
            SummaryKind kind = kindOrNull(bean);
            if (kind == null || !kinds.enables(kind)) {
                refused.add(bean.name() + " (" + (kind == null ? "of no kind" : "kind " + kind.word()) + ")");
            }
        }
        if (!refused.isEmpty()) {
            throw new IllegalStateException("REFUSING TO START: summary.enabledSummary names beans of kinds this tenant did not enable — "
                    + String.join(", ", refused) + "; the tenant's kinds: " + kinds.said() + ". Name their kinds in " + TenantKinds.KEY
                    + " (the kinds: " + String.join(", ", KnownKinds.words()) + "), or take the beans out of summary.enabledSummary");
        }
    }

    private static SummaryKind kindOrNull(SummaryBean<?> bean) {
        try {
            return KnownKinds.kindOf(bean);
        } catch (IllegalArgumentException ofNoKind) {
            return null;
        }
    }

    /** The beans whose input crosses the kinds (the chargeable roll-up) sum only the legs of the enabled kinds' groups. */
    private static void readOnlyTheGroupsOfTheEnabledKinds(List<SummaryBean<?>> beans, TenantKinds kinds) {
        for (SummaryBean<?> bean : beans) {
            if (bean instanceof CrossKindReader reader) {
                reader.readOnly(kinds::enablesServiceGroup);
            }
        }
    }

    /** The bean whose drains count the rows of the kinds not enabled: the first of the {@code cdr} entity the profile enables. */
    private static String firstBeanOfTheCdrEntity(List<SummaryBean<?>> beans) {
        return beans.stream().filter(bean -> "cdr".equals(bean.entityType())).map(SummaryBean::name).findFirst().orElse(null);
    }

    /** {@code KINDS call, ad, wifi (summary.kinds) — call = service groups 10, 11 …; ad = …} and, when some are not enabled, what becomes of their rows. */
    private static String kindsLine(TenantKinds kinds) {
        List<String> reads = new ArrayList<>();
        KnownKinds.all().stream().filter(kinds::enables).forEach(kind -> reads.add(TenantKinds.readsOf(kind)));
        String line = "KINDS " + kinds.said() + " — " + String.join("; ", reads);
        if (kinds.notEnabled().isEmpty()) {
            return line;
        }
        List<String> others = kinds.notEnabled().stream().map(TenantKinds::readsOf).toList();
        return line + ". NOT enabled: " + String.join("; ", others) + " — their cdr rows are summed by no bean here; they are counted "
                + "and said (" + KindGate.MARK + ", once per tenant, kind and day)";
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
     * profile, a lab start that would leave this box ({@link StartEndpoints}), a fault of the store's configuration, a
     * tree road's token named by a variable that is not set or empty (HARDEN-2).
     */
    private void sayAndCheckWhatThisStartResolved(Config config, List<StartEndpoints.Endpoint> endpoints, List<String> enabled) {
        LOG.info(PROFILE_MARK + " " + profileReadFrom(config));
        endpoints.forEach(endpoint -> LOG.info(endpoint.line()));
        refuseAFaultOfTheProfile(config);
        if (StartEndpoints.isALabStart(config)) {
            StartEndpoints.requireThisBoxOnly(endpoints);
        }
        LOG.info(store.checkAtStart(autostart));
        treeRoadBearerLines(config, enabled).forEach(line -> LOG.info(line));
    }

    /**
     * HARDEN-2 — each block of this start that asks one of prime-context's tree roads ({@link StartEndpoints#treeRoadBlocks}): its
     * bearer, read now from the environment by the variable's NAME — a named variable that is not set or empty refuses the start
     * here, in words — and its line: {@code tree roads: bearer from <VAR> (<key>)} or {@code tree roads: no bearer (<key> is not
     * set)}. Never the value.
     */
    private List<String> treeRoadBearerLines(Config config, List<String> enabled) {
        return StartEndpoints.treeRoadBlocks(config, enabled).stream()
                .map(block -> TreeRoadBearer.of(config, block, environment).said())
                .toList();
    }

    /**
     * Print-only: the same lines on standard output (the lab script reads them), and an exit code — 0 = every host
     * is this box and the configuration starts, 3 = a host is not this box, 4 = the configuration refuses the start.
     */
    private void printWhatThisStartResolvedAndExit(Config config, List<StartEndpoints.Endpoint> endpoints, List<String> enabled) {
        System.out.println(PROFILE_MARK + " " + profileReadFrom(config));
        endpoints.forEach(endpoint -> System.out.println(endpoint.line()));
        int elsewhere = StartEndpoints.notThisBox(endpoints).size();
        boolean configurationStarts = sayWhetherTheConfigurationStarts(config, enabled);
        System.out.println(StartEndpoints.LINE_MARK + "S " + endpoints.size() + " resolved, " + elsewhere + " not on this box — print-only: nothing was started");
        exit.accept(elsewhere != 0 ? 3 : configurationStarts ? 0 : 4);
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
     * Print-only: the {@code SECRET} lines — where the store's password and each tree road's token come from, never a value — or,
     * when the configuration would refuse the start, the refusal as the start would say it ({@code REFUSING TO START: …}).
     */
    private boolean sayWhetherTheConfigurationStarts(Config config, List<String> enabled) {
        try {
            refuseAFaultOfTheProfile(config);
            TenantKinds kinds = tenantKinds(config);
            refuseBeansOfKindsNotEnabled(resolveBeans(enabled), kinds);
            System.out.println(kindsLine(kinds));
            System.out.println("SECRET " + store.checkAtStart(autostart));
            treeRoadBearerLines(config, enabled).forEach(line -> System.out.println("SECRET " + line));
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

    /** The bean an enabled name makes — a catalog class, else config-instantiated — or null, said, when it makes none. */
    private SummaryBean<?> resolveBean(String name) {
        try {
            SummaryBean<?> bean = findByName(name);
            if (bean == null) {
                bean = configInstantiated(name);
            }
            if (bean == null) {
                LOG.errorf("enabledSummary '%s' matches no SummaryBean class and has no summary.beans.%s.window "
                        + "config to instantiate from", name, name);
                return null;
            }
            bean.table();   // fail-fast probe: throws if table-suffix is missing/invalid (caught + logged below)
            return bean;
        } catch (RuntimeException e) {
            LOG.errorf(e, "could not activate summary bean '%s': %s", name, e.getMessage());   // the cause in the line itself: a refused kind says its words here
            return null;
        }
    }

    private void register(SummaryBean<?> bean) {
        registry.register(bean);
        LOG.infof("bean registered: name=%s entity=%s window=%s table=%s", bean.name(), bean.entityType(), bean.window(), bean.table());
    }

    /**
     * The config-instantiated path (§12g): an enabled name with no catalog class but a
     * {@code summary.beans.<name>.window} key becomes an EXTRA instance under its own name — a call bean, or a WiFi
     * bean by its {@code kind} ({@link ConfigInstantiatedBeans}); an unknown kind throws in words and is said by the
     * caller, which activates no bean under that name.
     */
    private SummaryBean<?> configInstantiated(String name) {
        return ConfigInstantiatedBeans.from(ConfigProvider.getConfig(), name);
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
