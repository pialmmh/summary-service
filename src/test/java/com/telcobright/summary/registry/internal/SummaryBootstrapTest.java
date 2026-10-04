package com.telcobright.summary.registry.internal;

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
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory;
import com.telcobright.summary.testkit.LogCapture;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

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
        public Optional<String> fetchTenantRoot(String baseUrl, String tenant) {
            contextsFetched.add(baseUrl + " " + tenant);
            return Optional.empty();
        }
    };
    private final FakeUnitOfWorkFactory database = new FakeUnitOfWorkFactory();
    private final OutboxReader reader = new OutboxReader(database, new SummaryEngine(), 1000, 1, 8);
    private final SummaryBeanRegistry registry = new SummaryBeanRegistry(reader, 3600);

    private SummaryBootstrap bootstrap(boolean autostart) {
        return bootstrap(autostart, TestPools.store(Map.of(), Map.of()));
    }

    private SummaryBootstrap bootstrap(boolean autostart, StoreDataSource store) {
        @SuppressWarnings("unchecked")
        Instance<SummaryBean<?>> noCatalogBeans = (Instance<SummaryBean<?>>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Instance.class}, (proxy, method, args) -> {
                    if (method.getName().equals("handles")) return List.of();
                    throw new UnsupportedOperationException(method.getName());
                });
        return new SummaryBootstrap(registry, noCatalogBeans, new ContextRegistry(countingStandIn), new OutboxReaper(reader, registry, "cdr", 60),
                new PingListener(registry, "cdr_summary_ping", "127.0.0.1:1"), new TenantWatcher(registry), store, autostart);
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
}
