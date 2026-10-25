package com.telcobright.summary.registry.internal;

import com.sun.net.httpserver.HttpServer;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.context.api.ContextRegistry;
import com.telcobright.summary.context.internal.ConfigManagerClient;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.outbox.api.OutboxReader;
import com.telcobright.summary.outbox.internal.OutboxReaper;
import com.telcobright.summary.ping.internal.PingListener;
import com.telcobright.summary.registry.api.SummaryBeanRegistry;
import com.telcobright.summary.runtime.internal.StoreDataSource;
import com.telcobright.summary.runtime.internal.TestPools;
import com.telcobright.summary.tenancy.api.TenantWatcher;
import com.telcobright.summary.testkit.Await;
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory;
import com.telcobright.summary.testkit.LogCapture;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A start with {@code summary.autostart} off (the default) registers the beans and dials NOTHING: no database, no
 * broker, no prime-context — and no config-manager. (It used to load each bean's context from config-manager while
 * it registered the bean, whatever autostart said.) The config-manager here is a counting stand-in: no address is
 * dialled by this test.
 */
class SummaryBootstrapTest {

    private final List<String> contextsFetched = new CopyOnWriteArrayList<>();
    private final ConfigManagerClient countingStandIn = new ConfigManagerClient() {
        @Override
        public Optional<String> fetchTenantRoot(String baseUrl, String tenant, com.telcobright.summary.treeroads.api.TreeRoadBearer bearer) {
            contextsFetched.add(baseUrl + " " + tenant);
            return Optional.empty();
        }
    };
    private final FakeUnitOfWorkFactory database = new FakeUnitOfWorkFactory();
    private final OutboxReader reader = new OutboxReader(database, new SummaryEngine(), 1000, 1, 8);
    private final SummaryBeanRegistry registry = new SummaryBeanRegistry(reader, 3600);
    /** The environment a start reads a token's variable from (HARDEN-2) — a test's own; by default no variable is set. */
    private Function<String, String> environment = name -> null;

    private SummaryBootstrap bootstrap(boolean autostart) {
        return bootstrap(autostart, TestPools.store(Map.of(), Map.of()));
    }

    @SuppressWarnings("unchecked")
    private Instance<SummaryBean<?>> noCatalogBeans() {
        return (Instance<SummaryBean<?>>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Instance.class}, (proxy, method, args) -> {
                    if (method.getName().equals("handles")) return List.of();
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private SummaryBootstrap bootstrap(boolean autostart, StoreDataSource store) {
        SummaryBootstrap bootstrap = new SummaryBootstrap(registry, noCatalogBeans(), new ContextRegistry(countingStandIn), new OutboxReaper(reader, registry, 60),
                new PingListener(registry, "cdr_summary_ping", "127.0.0.1:1"), new TenantWatcher(registry), store, autostart);
        bootstrap.environment = name -> environment.apply(name);
        return bootstrap;
    }

    // ---- brief S9 ----

    private static final String PG = "jdbc:postgresql://127.0.0.1:1/routesphere";       // never dialled: the start is refused, or autostart is off

    @Test
    void a_password_named_by_a_variable_that_is_not_in_the_environment_refuses_the_start_before_anything_else() {
        StoreDataSource store = TestPools.store(Map.of("summary.store.url", PG, "summary.store.password-ref", "env:SUMMARY_TEST_NOT_SET"), Map.of());

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> bootstrap(true, store).onStart(null));

        assertTrue(refused.getMessage().startsWith("REFUSING TO START") && refused.getMessage().contains("SUMMARY_TEST_NOT_SET is not set"), refused.getMessage());
        assertTrue(registry.beanNames().isEmpty(), "not a bean was registered");
        assertTrue(registry.servedSchemas().isEmpty(), "no schema is served, no worker started");
        assertEquals(List.of(), contextsFetched, "and nothing was dialled");
        assertTrue(database.store.executedSql().isEmpty());
    }

    @Test
    void workers_that_are_to_start_with_no_store_in_the_profile_refuse_the_start_before_anything_else() {
        // the most likely cause: the start did not name its tenant, and the profile it fell on has no store
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> bootstrap(true).onStart(null));

        assertTrue(refused.getMessage().startsWith("REFUSING TO START") && refused.getMessage().contains("names no store")
                && refused.getMessage().contains("SUMMARY_ACTIVE_TENANT"), refused.getMessage());
        assertTrue(registry.beanNames().isEmpty(), "not a bean was registered");
        assertTrue(registry.servedSchemas().isEmpty(), "no schema is served, no worker started");
        assertEquals(List.of(), contextsFetched, "and nothing was dialled");
        assertTrue(database.store.executedSql().isEmpty());
    }

    @Test
    void a_store_whose_engine_contradicts_its_url_refuses_the_start_it_is_not_left_to_the_tenants_thread_to_try_again() {
        StoreDataSource store = TestPools.store(Map.of("summary.store.kind", "postgresql", "summary.store.url", "jdbc:mysql://127.0.0.1:1/telcobright"), Map.of());

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> bootstrap(false, store).onStart(null));

        assertTrue(refused.getMessage().contains("kind is postgresql but summary.store.url is a mysql URL"), refused.getMessage());
        assertTrue(registry.beanNames().isEmpty(), "refused whether the workers were to start or not");
    }

    @Test
    void with_the_variable_in_the_environment_the_start_goes_on_and_says_the_variables_name_never_its_value() {
        StoreDataSource store = TestPools.store(Map.of("summary.store.url", PG, "summary.store.password-ref", "env:SUMMARY_TEST_PASSWORD"),
                Map.of("SUMMARY_TEST_PASSWORD", "the-secret-value-itself"));

        try (LogCapture said = LogCapture.of(SummaryBootstrap.class)) {
            bootstrap(false, store).onStart(null);

            assertTrue(said.lines().contains("the store's password: from the environment variable SUMMARY_TEST_PASSWORD (summary.store.password-ref)"),
                    "the start says where the password comes from: " + said.lines());
            assertTrue(said.lines().stream().noneMatch(line -> line.contains("the-secret-value-itself")), "never the value");
        }
        assertFalse(registry.beanNames().isEmpty(), "the start went on: the beans are registered");
    }

    // ---- brief S10, S11: the start's first lines ----

    @Test
    void the_starts_first_line_says_which_tenant_it_serves_what_named_it_and_which_profile_file_was_read() {
        try (LogCapture said = LogCapture.of(SummaryBootstrap.class)) {
            bootstrap(false).onStart(null);

            List<String> lines = said.lines();
            assertTrue(lines.get(0).startsWith("PROFILE tenant tcbl, profile dev (the first enabled entry of the jar's config/tenants.yml): "
                    + "THE JAR'S OWN config/tenants/tcbl/dev/profile-dev.yml (no such file under the working directory "), "the FIRST line: " + lines.get(0));
            assertTrue(lines.get(1).startsWith("ENDPOINT store "), "then every endpoint, before anything is dialled: " + lines.get(1));
            assertTrue(lines.indexOf("the store: none is configured (nothing is to be served: summary.autostart is off)") > lines.indexOf(lines.stream()
                    .filter(line -> line.startsWith("ENDPOINT listens-on ")).findFirst().orElseThrow()), "and only then the checks: " + lines);
        }
    }

    @Test
    void a_fault_of_the_profile_refuses_the_start_in_words_and_the_first_lines_are_still_said() {
        // what the profile source found wrong (a config directory that is none, a tenant with no profile, a file that cannot be parsed)
        System.setProperty("summary.profile.fault", "SUMMARY_CONFIG_DIR names /nowhere, which is not a directory");
        try (LogCapture said = LogCapture.of(SummaryBootstrap.class)) {
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> bootstrap(false).onStart(null));

            assertEquals("REFUSING TO START: SUMMARY_CONFIG_DIR names /nowhere, which is not a directory", refused.getMessage());
            assertTrue(said.lines().get(0).startsWith("PROFILE "), "a refused start has its first lines in the log too: " + said.lines());
            assertTrue(said.lines().stream().anyMatch(line -> line.startsWith("ENDPOINT store ")));
        } finally {
            System.clearProperty("summary.profile.fault");
        }
        assertTrue(registry.beanNames().isEmpty(), "refused before a bean was registered — with the workers off too");
        assertEquals(List.of(), contextsFetched, "and nothing was dialled");
    }

    @Test
    void a_lab_start_is_refused_when_an_endpoint_is_another_boxs_by_the_key_or_by_its_first_name() {
        // the unit test's profile is the jar's tcbl/dev: CCL's database, broker and config-manager — exactly what a lab must never reach
        for (String key : new String[] {"summary.endpoints.local-only", "summary.endpoints.loopback-only"}) {
            System.setProperty(key, "true");
            try {
                IllegalStateException refused = assertThrows(IllegalStateException.class, () -> bootstrap(false).onStart(null), key);

                assertTrue(refused.getMessage().startsWith("REFUSING TO START: summary.endpoints.local-only is set (a lab start)"), refused.getMessage());
                assertTrue(refused.getMessage().contains("103.95.96.77:3306"), "the box is named: " + refused.getMessage());
            } finally {
                System.clearProperty(key);
            }
            assertTrue(registry.beanNames().isEmpty(), "refused before a bean was registered");
            assertEquals(List.of(), contextsFetched, "and nothing was dialled");
        }
    }

    // ---- print-only: what a lab script reads BEFORE it starts anything ----

    /** The lines a print-only start writes to standard output, and the code it exits with. */
    private record Printed(List<String> lines, List<Integer> exitCodes) {
    }

    /** A print-only start of the unit test's profile (the jar's tcbl/dev), with these keys set over it for its length. */
    private Printed printOnly(boolean autostart, StoreDataSource store, String... keysAndValues) {
        List<String> keys = new ArrayList<>(List.of("summary.endpoints.print-only"));
        System.setProperty("summary.endpoints.print-only", "true");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            System.setProperty(keysAndValues[i], keysAndValues[i + 1]);
            keys.add(keysAndValues[i]);
        }
        PrintStream standardOutput = System.out;
        ByteArrayOutputStream heard = new ByteArrayOutputStream();
        List<Integer> exitCodes = new ArrayList<>();
        try {
            System.setOut(new PrintStream(heard, true, StandardCharsets.UTF_8));
            SummaryBootstrap printing = bootstrap(autostart, store);
            printing.exit = exitCodes::add;
            printing.onStart(null);
        } finally {
            System.setOut(standardOutput);
            keys.forEach(System::clearProperty);
        }
        return new Printed(heard.toString(StandardCharsets.UTF_8).lines().toList(), exitCodes);
    }

    /** Every endpoint of the unit test's profile moved onto this machine (port 1: nothing is dialled by a print-only start). */
    private static final String[] ON_THIS_BOX = {"summary.store.url", "jdbc:mysql://127.0.0.1:1/telcobright", "summary.outbox.ping-bootstrap-servers", "127.0.0.1:1",
            "summary.contexts.mediationContext.base-url", "http://127.0.0.1:1", "quarkus.http.host", "127.0.0.1"};

    private static String[] with(String[] keysAndValues, String... more) {
        List<String> all = new ArrayList<>(List.of(keysAndValues));
        all.addAll(List.of(more));
        return all.toArray(String[]::new);
    }

    @Test
    void print_only_says_the_profile_and_every_endpoint_starts_nothing_and_exits_with_3_when_a_host_is_another_boxs() {
        Printed printed = printOnly(true, TestPools.store(Map.of(), Map.of()));

        assertTrue(printed.lines().get(0).startsWith("PROFILE tenant tcbl, profile dev "), "the first line: " + printed.lines());
        assertTrue(printed.lines().stream().anyMatch(line -> line.startsWith("ENDPOINT store jdbc:mysql://103.95.96.77:3306/") && line.endsWith(" hosts=103.95.96.77 NOT-THIS-BOX")),
                "each endpoint, with its hosts and whether they are this box: " + printed.lines());
        assertEquals("ENDPOINTS 4 resolved, 4 not on this box — print-only: nothing was started", printed.lines().get(printed.lines().size() - 1),
                "CCL's database, broker and config-manager, and a listener on every interface");
        assertEquals(List.of(3), printed.exitCodes(), "3 = a host is not this box: a lab script does not start it");
        assertTrue(registry.beanNames().isEmpty() && registry.servedSchemas().isEmpty(), "nothing was started, though autostart is on");
        assertEquals(List.of(), contextsFetched, "and nothing was dialled");
    }

    @Test
    void print_only_exits_with_0_when_every_host_is_this_box_and_the_configuration_starts() {
        StoreDataSource store = TestPools.store(Map.of("summary.store.url", "jdbc:mysql://127.0.0.1:1/telcobright"), Map.of());

        Printed printed = printOnly(true, store, ON_THIS_BOX);

        assertEquals(List.of(0), printed.exitCodes(), printed.lines().toString());
        assertTrue(printed.lines().contains("SECRET the store's password: none is configured"), "where the password comes from, never a value: " + printed.lines());
        assertTrue(printed.lines().stream().filter(line -> line.startsWith("ENDPOINT ")).allMatch(line -> line.endsWith(" LOOPBACK")), printed.lines().toString());
        assertTrue(registry.beanNames().isEmpty(), "nothing was started");
    }

    @Test
    void print_only_exits_with_4_and_says_the_refusal_when_the_configuration_would_refuse_the_start() {
        // every host is this box — but the start itself would be refused: a lab script must not start it either
        Printed profileFault = printOnly(false, TestPools.store(Map.of(), Map.of()), with(ON_THIS_BOX, "summary.profile.fault", "SUMMARY_CONFIG_DIR names /nowhere, which is not a directory"));
        assertEquals(List.of(4), profileFault.exitCodes(), profileFault.lines().toString());
        assertTrue(profileFault.lines().contains("REFUSING TO START: SUMMARY_CONFIG_DIR names /nowhere, which is not a directory"), "said as the start would say it: " + profileFault.lines());

        StoreDataSource noVariable = TestPools.store(Map.of("summary.store.url", PG, "summary.store.password-ref", "env:SUMMARY_TEST_NOT_SET"), Map.of());
        Printed secretMissing = printOnly(true, noVariable, ON_THIS_BOX);
        assertEquals(List.of(4), secretMissing.exitCodes(), secretMissing.lines().toString());
        assertTrue(secretMissing.lines().stream().anyMatch(line -> line.startsWith("REFUSING TO START: the environment variable SUMMARY_TEST_NOT_SET is not set")),
                secretMissing.lines().toString());
        assertTrue(registry.beanNames().isEmpty(), "nothing was started");
    }

    // ---- SS-0006 item 4: the kinds ----

    @Test
    void a_config_instantiated_bean_with_a_kind_is_a_wifi_bean_and_an_unknown_kind_refuses_that_bean_at_the_start_in_words() {
        // over the unit test's profile: three enabled names, two WiFi kinds and one nobody knows
        String[] keys = {"summary.enabledSummary", "dailyWifiSessionSummary,hourlyWifiDstSummary,badWifi",
                "summary.beans.dailyWifiSessionSummary.window", "daily", "summary.beans.dailyWifiSessionSummary.kind", "wifi-session",
                "summary.beans.hourlyWifiDstSummary.window", "hourly", "summary.beans.hourlyWifiDstSummary.kind", "wifi-dst",
                "summary.beans.badWifi.window", "daily", "summary.beans.badWifi.kind", "wifi-nope",
                "summary.kinds", "call,wifi"};   // SS-0008: the WiFi beans are the wifi kind's — named, or the start is refused
        for (int i = 0; i < keys.length; i += 2) System.setProperty(keys[i], keys[i + 1]);
        try (LogCapture said = LogCapture.of(SummaryBootstrap.class)) {
            bootstrap(false).onStart(null);

            assertEquals(java.util.Set.of("dailyWifiSessionSummary", "hourlyWifiDstSummary"), registry.beanNames(), "the two kinds registered; the unknown one is not");
            assertEquals("sum_wifi_day_101", registry.beans().stream().filter(b -> b.name().equals("dailyWifiSessionSummary")).findFirst().orElseThrow().table());
            assertEquals("wifi_flow", registry.beans().stream().filter(b -> b.name().equals("hourlyWifiDstSummary")).findFirst().orElseThrow().entityType());
            assertTrue(said.records(java.util.logging.Level.SEVERE).stream().anyMatch(line -> line.startsWith("could not activate summary bean 'badWifi': ")
                    && line.contains("summary.beans.badWifi.kind is 'wifi-nope' — it must be wifi-session or wifi-dst")), "refused in words: " + said.records(java.util.logging.Level.SEVERE));
            assertTrue(said.lines().stream().anyMatch(line -> line.equals("bean registered: name=dailyWifiSessionSummary entity=cdr window=daily table=sum_wifi_day_101")), said.lines().toString());
            assertTrue(said.lines().stream().anyMatch(line -> line.equals("bean registered: name=hourlyWifiDstSummary entity=wifi_flow window=hourly table=sum_wifi_dst_hr")));
        } finally {
            for (int i = 0; i < keys.length; i += 2) System.clearProperty(keys[i]);
        }
        assertEquals(List.of(), contextsFetched, "and nothing was dialled");
    }

    @Test
    void with_autostart_off_the_beans_are_registered_and_nothing_is_dialled() {
        bootstrap(false).onStart(null);

        // the active profile (tcbl/dev) has two config-instantiated beans; each names the context "mediationContext"
        assertTrue(registry.beanNames().containsAll(List.of("dailyCallSummarySg11", "hourlyCallSummarySg11")), "registered: " + registry.beanNames());
        assertTrue(registry.beans().stream().allMatch(bean -> "mediationContext".equals(bean.contextName())), "each would load its context from config-manager");
        assertEquals(List.of(), contextsFetched, "config-manager was not asked: nothing is dialled until the workers start");
        assertTrue(registry.servedSchemas().isEmpty(), "no schema is served");
        assertFalse(registry.isRunning("dailyCallSummarySg11"), "no worker runs");
        assertTrue(database.store.executedSql().isEmpty() && database.schemasEntered().isEmpty(), "the database was not touched");
    }

    // ---- HARDEN-2: the bearer each tree road is asked with ----

    private static final String TREE_TOKEN = "pct_tree-road-token-0123456789abcdef";
    private static final String CONTEXT_TOKEN = "pct_context-road-token-fedcba9876543210";

    /** A tree-mode start over the unit test's profile (whose beans name the context mediationContext), both blocks naming a variable. */
    private static final String[] BOTH_BLOCKS_NAME_A_VARIABLE = {"summary.tenants.mode", "tree", "summary.tenants.root", "btcl",
            "summary.tenants.prime-context.base-url", "http://127.0.0.1:1", "summary.tenants.prime-context.token-ref", "env:SUMMARY_TEST_TREE_TOKEN",
            "summary.contexts.mediationContext.token-ref", "env:SUMMARY_TEST_CONTEXT_TOKEN"};

    private static void set(String... keysAndValues) {
        for (int i = 0; i < keysAndValues.length; i += 2) System.setProperty(keysAndValues[i], keysAndValues[i + 1]);
    }

    private static void clear(String... keysAndValues) {
        for (int i = 0; i < keysAndValues.length; i += 2) System.clearProperty(keysAndValues[i]);
    }

    @Test
    void the_start_says_the_bearer_of_each_tree_road_by_its_variables_name_never_its_value() {
        environment = Map.of("SUMMARY_TEST_TREE_TOKEN", TREE_TOKEN, "SUMMARY_TEST_CONTEXT_TOKEN", CONTEXT_TOKEN)::get;
        SummaryBootstrap start = bootstrap(false);                       // made before the keys: its own watcher is the one-schema one
        set(BOTH_BLOCKS_NAME_A_VARIABLE);
        try (LogCapture said = LogCapture.of(SummaryBootstrap.class)) {
            start.onStart(null);

            List<String> lines = said.lines();
            int store = lines.indexOf("the store: none is configured (nothing is to be served: summary.autostart is off)");
            int tree = lines.indexOf("tree roads: bearer from SUMMARY_TEST_TREE_TOKEN (summary.tenants.prime-context.token-ref)");
            int context = lines.indexOf("tree roads: bearer from SUMMARY_TEST_CONTEXT_TOKEN (summary.contexts.mediationContext.token-ref)");
            assertTrue(store >= 0 && tree > store && context > store, "each tree road's bearer, said with the checks: " + lines);
            assertTrue(lines.stream().noneMatch(line -> line.contains(TREE_TOKEN) || line.contains(CONTEXT_TOKEN)), "never a value");
        } finally {
            clear(BOTH_BLOCKS_NAME_A_VARIABLE);
        }
        assertFalse(registry.beanNames().isEmpty(), "the start went on");
        assertEquals(List.of(), contextsFetched, "and nothing was dialled (autostart is off)");
    }

    @Test
    void a_tree_road_token_named_by_an_empty_variable_refuses_the_start_in_words_before_anything_else() {
        environment = Map.of("SUMMARY_TEST_TREE_TOKEN", "", "SUMMARY_TEST_CONTEXT_TOKEN", CONTEXT_TOKEN)::get;
        SummaryBootstrap start = bootstrap(true, TestPools.store(Map.of("summary.store.url", PG), Map.of()));
        set(BOTH_BLOCKS_NAME_A_VARIABLE);
        try {
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> start.onStart(null));

            assertTrue(refused.getMessage().startsWith("REFUSING TO START: the environment variable SUMMARY_TEST_TREE_TOKEN is empty — "
                    + "summary.tenants.prime-context.token-ref names it"), refused.getMessage());
            assertFalse(refused.getMessage().contains(CONTEXT_TOKEN));
        } finally {
            clear(BOTH_BLOCKS_NAME_A_VARIABLE);
        }
        assertTrue(registry.beanNames().isEmpty(), "not a bean was registered");
        assertTrue(registry.servedSchemas().isEmpty(), "no schema is served, no worker started");
        assertEquals(List.of(), contextsFetched, "and nothing was dialled");
    }

    @Test
    void a_profile_that_names_no_token_says_no_bearer_for_each_tree_road_and_reads_no_variable() {
        environment = name -> {
            throw new AssertionError("no variable is read: " + name);
        };
        try (LogCapture said = LogCapture.of(SummaryBootstrap.class)) {
            bootstrap(false).onStart(null);

            assertTrue(said.lines().contains("tree roads: no bearer (summary.contexts.mediationContext.token-ref is not set)"), said.lines().toString());
            assertTrue(said.lines().stream().noneMatch(line -> line.contains("summary.tenants.prime-context")), "one schema: no tree is read, nothing said of it");
        }
    }

    @Test
    void print_only_says_each_tree_roads_bearer_and_exits_with_4_when_its_variable_is_empty() {
        String[] contextNamesAVariable = with(ON_THIS_BOX, "summary.contexts.mediationContext.token-ref", "env:SUMMARY_TEST_CONTEXT_TOKEN");
        StoreDataSource store = TestPools.store(Map.of("summary.store.url", "jdbc:mysql://127.0.0.1:1/telcobright"), Map.of());

        environment = Map.of("SUMMARY_TEST_CONTEXT_TOKEN", CONTEXT_TOKEN)::get;
        Printed starts = printOnly(true, store, contextNamesAVariable);
        assertEquals(List.of(0), starts.exitCodes(), starts.lines().toString());
        assertTrue(starts.lines().contains("SECRET tree roads: bearer from SUMMARY_TEST_CONTEXT_TOKEN (summary.contexts.mediationContext.token-ref)"),
                starts.lines().toString());
        assertTrue(starts.lines().stream().noneMatch(line -> line.contains(CONTEXT_TOKEN)), "never the value");

        environment = Map.of("SUMMARY_TEST_CONTEXT_TOKEN", "")::get;
        Printed refused = printOnly(true, store, contextNamesAVariable);
        assertEquals(List.of(4), refused.exitCodes(), refused.lines().toString());
        assertTrue(refused.lines().stream().anyMatch(line -> line.startsWith("REFUSING TO START: the environment variable SUMMARY_TEST_CONTEXT_TOKEN is empty")),
                refused.lines().toString());
        assertTrue(registry.beanNames().isEmpty(), "nothing was started");
    }

    @Test
    void the_token_is_in_no_log_line_at_the_start_nor_when_prime_context_refuses_it_on_either_road() throws IOException {
        List<String> shown = new CopyOnWriteArrayList<>();
        HttpServer refusing = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);   // prime-context with the guard on, refusing
        refusing.createContext("/", exchange -> {
            shown.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "{\"error\":\"UNAUTHORIZED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        refusing.start();
        String url = "http://127.0.0.1:" + refusing.getAddress().getPort();
        String[] keys = {"summary.tenants.mode", "tree", "summary.tenants.root", "btcl", "summary.tenants.prime-context.base-url", url,
                "summary.tenants.prime-context.token-ref", "env:SUMMARY_TEST_TREE_TOKEN", "summary.tenants.doorbell.bootstrap-servers", "127.0.0.1:1",
                "summary.contexts.mediationContext.base-url", url, "summary.contexts.mediationContext.token-ref", "env:SUMMARY_TEST_TREE_TOKEN"};
        environment = Map.of("SUMMARY_TEST_TREE_TOKEN", TREE_TOKEN)::get;
        SummaryBootstrap start = bootstrap(false);                       // made before the keys: its own watcher is the one-schema one
        set(keys);
        TenantWatcher watcher = null;
        try (LogCapture everything = LogCapture.ofEverything()) {
            start.onStart(null);                                         // the start's lines
            Config config = ConfigProvider.getConfig();
            watcher = new TenantWatcher(registry, config, environment);
            watcher.start();                                             // the tree road refuses: said as an ERROR, with its exception
            new ContextRegistry(new ConfigManagerClient(), () -> config, environment).ensureLoaded("mediationContext");   // refused: a WARN

            assertTrue(Await.until(() -> everything.linesWithTheirExceptions().stream().anyMatch(line -> line.contains("could not be read")), 10_000),
                    "the refused tree read was logged");
            List<String> lines = everything.linesWithTheirExceptions();
            assertTrue(lines.contains("tree roads: bearer from SUMMARY_TEST_TREE_TOKEN (summary.tenants.prime-context.token-ref)"), "said at start");
            assertTrue(lines.stream().anyMatch(line -> line.contains("HTTP 401") && line.contains("tree roads: bearer from SUMMARY_TEST_TREE_TOKEN")),
                    "each refusal says which bearer was shown");
            assertTrue(lines.stream().noneMatch(line -> line.contains(TREE_TOKEN)), "the token is in no log line");
        } finally {
            if (watcher != null) watcher.stop();
            refusing.stop(0);
            clear(keys);
        }
        assertTrue(shown.size() >= 2 && shown.stream().allMatch(("Bearer " + TREE_TOKEN)::equals), "and it WAS shown on both roads: " + shown.size());
    }

    @Test
    void the_service_start_reads_a_tokens_variable_from_the_process_environment() {
        // PATH: a variable every process has (not a secret). The bootstrap is made as the service makes it: its environment untouched
        SummaryBootstrap start = new SummaryBootstrap(registry, noCatalogBeans(), new ContextRegistry(countingStandIn), new OutboxReaper(reader, registry, 60),
                new PingListener(registry, "cdr_summary_ping", "127.0.0.1:1"), new TenantWatcher(registry), TestPools.store(Map.of(), Map.of()), false);
        System.setProperty("summary.contexts.mediationContext.token-ref", "env:PATH");
        try (LogCapture said = LogCapture.of(SummaryBootstrap.class)) {
            start.onStart(null);

            assertTrue(said.lines().contains("tree roads: bearer from PATH (summary.contexts.mediationContext.token-ref)"), said.lines().toString());
        } finally {
            System.clearProperty("summary.contexts.mediationContext.token-ref");
        }
        assertEquals(List.of(), contextsFetched, "nothing was dialled");
    }
}
