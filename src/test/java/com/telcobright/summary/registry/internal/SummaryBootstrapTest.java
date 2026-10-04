package com.telcobright.summary.registry.internal;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.context.api.ContextRegistry;
import com.telcobright.summary.context.internal.ConfigManagerClient;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.outbox.api.OutboxReader;
import com.telcobright.summary.outbox.internal.OutboxReaper;
import com.telcobright.summary.ping.internal.PingListener;
import com.telcobright.summary.registry.api.SummaryBeanRegistry;
import com.telcobright.summary.tenancy.api.TenantWatcher;
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        @SuppressWarnings("unchecked")
        Instance<SummaryBean<?>> noCatalogBeans = (Instance<SummaryBean<?>>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Instance.class}, (proxy, method, args) -> {
                    if (method.getName().equals("handles")) return List.of();
                    throw new UnsupportedOperationException(method.getName());
                });
        return new SummaryBootstrap(registry, noCatalogBeans, new ContextRegistry(countingStandIn), new OutboxReaper(reader, registry, "cdr", 60),
                new PingListener(registry, "cdr_summary_ping", "127.0.0.1:1"), new TenantWatcher(registry), autostart);
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
