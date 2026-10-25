package com.telcobright.summary.tenancy.api;

import com.sun.net.httpserver.HttpServer;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.outbox.api.OutboxReader;
import com.telcobright.summary.registry.api.SummaryBeanRegistry;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HARDEN-2 — in {@code summary.tenants.mode: tree} the root's tree is read on prime-context's tree road with the bearer the block
 * names ({@code summary.tenants.prime-context.token-ref: env:<VAR>}); a block that names none reads it with no Authorization header,
 * as before; a named variable that is empty refuses the watcher (and so the start) in words. The watcher here is the service's own,
 * made from a profile — its source is the real {@code PrimeContextTreeSource}; the stand-in listens on 127.0.0.1 only.
 */
class TenantWatcherBearerTest {

    private static final String TOKEN = "pct_tree-road-token-0123456789abcdef";
    private static final String TREE = "{\"dbName\":\"btcl\",\"children\":{\"res_44\":{\"dbName\":\"res_44\",\"parent\":\"btcl\",\"children\":{}}}}";

    private final SummaryBeanRegistry registry = new SummaryBeanRegistry(new OutboxReader(new FakeUnitOfWorkFactory(), new SummaryEngine(), 1000, 50, 8), 1);
    private HttpServer primeContext;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    {
        registry.register(AdTestSupport.dailyBean());
    }

    @BeforeEach
    void aStandInOnLoopback() throws IOException {
        primeContext = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        primeContext.createContext("/get-specific-tenant-root", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(authorization == null ? "(no Authorization header)" : authorization);
            byte[] body = TREE.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        primeContext.start();
    }

    @AfterEach
    void stop() {
        registry.stopAll();
        primeContext.stop(0);
    }

    @Test
    void in_tree_mode_the_tree_is_read_with_the_bearer_its_block_names() throws Exception {
        TenantWatcher watcher = new TenantWatcher(registry, treeProfile("env:PRIME_CONTEXT_TOKEN"), Map.of("PRIME_CONTEXT_TOKEN", TOKEN)::get);

        assertEquals(Set.of("btcl", "res_44"), watcher.reloadNow());
        assertEquals(List.of("Bearer " + TOKEN), authorizations);
    }

    @Test
    void a_tree_whose_block_names_no_variable_is_read_with_no_authorization_header_as_before() throws Exception {
        TenantWatcher watcher = new TenantWatcher(registry, treeProfile(null), name -> {
            throw new AssertionError("the environment is not read: " + name);
        });

        assertEquals(Set.of("btcl", "res_44"), watcher.reloadNow());
        assertEquals(List.of("(no Authorization header)"), authorizations);
    }

    @Test
    void a_tree_token_named_by_an_empty_variable_refuses_the_watcher_in_words() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new TenantWatcher(registry, treeProfile("env:PRIME_CONTEXT_TOKEN"), Map.of("PRIME_CONTEXT_TOKEN", "")::get));

        assertTrue(refused.getMessage().startsWith("REFUSING TO START: the environment variable PRIME_CONTEXT_TOKEN is empty"), refused.getMessage());
        assertEquals(List.of(), authorizations, "nothing was dialled");
    }

    /** A tree-mode profile with the stand-in as prime-context, naming {@code tokenRef} (null: the key is absent). */
    private Config treeProfile(String tokenRef) {
        Map<String, String> keys = new HashMap<>(Map.of("summary.tenants.mode", "tree", "summary.tenants.root", "btcl",
                "summary.tenants.prime-context.base-url", "http://127.0.0.1:" + primeContext.getAddress().getPort(),
                "summary.tenants.doorbell.bootstrap-servers", "127.0.0.1:1"));
        if (tokenRef != null) {
            keys.put("summary.tenants.prime-context.token-ref", tokenRef);
        }
        return new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(keys, "a test's profile", 300)).build();
    }

    @Test
    void the_services_own_watcher_reads_the_variable_from_the_process_environment() throws Exception {
        // PATH: a variable every process has (not a secret) — the service's watcher must read the REAL environment by the name
        String url = "http://127.0.0.1:" + primeContext.getAddress().getPort();
        String[] keys = {"summary.tenants.mode", "tree", "summary.tenants.root", "btcl", "summary.tenants.prime-context.base-url", url,
                "summary.tenants.prime-context.token-ref", "env:PATH", "summary.tenants.doorbell.bootstrap-servers", "127.0.0.1:1"};
        for (int i = 0; i < keys.length; i += 2) System.setProperty(keys[i], keys[i + 1]);
        try {
            assertEquals(url, ConfigProvider.getConfig().getValue("summary.tenants.prime-context.base-url", String.class), "only the stand-in is dialled");
            new TenantWatcher(registry).reloadNow();
        } finally {
            for (int i = 0; i < keys.length; i += 2) System.clearProperty(keys[i]);
        }
        assertEquals(1, authorizations.size());
        assertTrue(authorizations.get(0).equals("Bearer " + System.getenv("PATH").trim()), "the block's variable, from the process's own environment");
    }
}
