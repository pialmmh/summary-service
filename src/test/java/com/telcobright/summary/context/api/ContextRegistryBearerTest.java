package com.telcobright.summary.context.api;

import com.sun.net.httpserver.HttpServer;
import com.telcobright.summary.context.cdr.MediationContext;
import com.telcobright.summary.context.internal.ConfigManagerClient;
import com.telcobright.summary.testkit.LogCapture;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HARDEN-2 — a bean's context is read on prime-context's tree road ({@code POST /get-specific-tenant-root}, the road config-manager
 * served). It carries {@code Authorization: Bearer <token>} when the context's block names the token's variable
 * ({@code summary.contexts.<name>.token-ref: env:<VAR>}), and NO Authorization header when it names none — as before. The
 * stand-in listens on 127.0.0.1 only; no real prime-context is dialled by a test.
 */
class ContextRegistryBearerTest {

    private static final String TOKEN = "pct_tree-road-token-0123456789abcdef";

    private HttpServer primeContext;
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private volatile int status = 200;

    @BeforeEach
    void aStandInOnLoopback() throws IOException {
        primeContext = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        primeContext.createContext("/", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            asked.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " | " + (authorization == null ? "(no Authorization header)" : authorization));
            byte[] body = (status == 200 ? "{\"name\":\"btcl\",\"dbName\":\"btcl\"}" : "{\"error\":\"UNAUTHORIZED\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        primeContext.start();
    }

    @AfterEach
    void stop() {
        primeContext.stop(0);
    }

    @Test
    void a_contexts_bearer_rides_its_tree_road() {
        MediationContext context = (MediationContext) registry("env:PRIME_CONTEXT_TOKEN", Map.of("PRIME_CONTEXT_TOKEN", TOKEN)).ensureLoaded("mediationContext");

        assertTrue(context.loaded(), "loaded");
        assertEquals(List.of("POST /get-specific-tenant-root?name=btcl | Bearer " + TOKEN), asked);
    }

    @Test
    void a_context_that_names_no_variable_is_asked_with_no_authorization_header_as_before() {
        registry(null, Map.of()).ensureLoaded("mediationContext");

        assertEquals(List.of("POST /get-specific-tenant-root?name=btcl | (no Authorization header)"), asked);
    }

    @Test
    void a_refusal_is_said_with_the_bearers_variable_never_its_value() {
        status = 401;
        try (LogCapture said = LogCapture.of(ConfigManagerClient.class)) {
            MediationContext context = (MediationContext) registry("env:PRIME_CONTEXT_TOKEN", Map.of("PRIME_CONTEXT_TOKEN", TOKEN)).ensureLoaded("mediationContext");

            assertFalse(context.loaded(), "not loaded — best-effort, as before");
            assertEquals(List.of("config-manager btcl -> HTTP 401 (context not loaded) — tree roads: bearer from PRIME_CONTEXT_TOKEN "
                    + "(summary.contexts.mediationContext.token-ref)"), said.records(Level.WARNING));
            assertTrue(said.lines().stream().noneMatch(line -> line.contains(TOKEN)), "never the value");
        }
        assertEquals(List.of("POST /get-specific-tenant-root?name=btcl | Bearer " + TOKEN), asked, "it WAS shown");
    }

    /** The service's registry over a profile whose context names {@code tokenRef} (null: the key is absent), with that environment. */
    private ContextRegistry registry(String tokenRef, Map<String, String> environment) {
        Map<String, String> keys = new HashMap<>(Map.of("summary.contexts.mediationContext.source", "config-manager",
                "summary.contexts.mediationContext.base-url", "http://127.0.0.1:" + primeContext.getAddress().getPort(),
                "summary.contexts.mediationContext.tenant", "btcl"));
        if (tokenRef != null) {
            keys.put("summary.contexts.mediationContext.token-ref", tokenRef);
        }
        Config config = new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(keys, "a test's profile", 300)).build();
        return new ContextRegistry(new ConfigManagerClient(), () -> config, environment::get);
    }

    @Test
    void the_services_own_registry_reads_the_variable_from_the_process_environment() {
        // PATH: a variable every process has (not a secret) — the service's registry must read the REAL environment by the name
        String url = "http://127.0.0.1:" + primeContext.getAddress().getPort();
        String[] keys = {"summary.contexts.mediationContext.source", "config-manager", "summary.contexts.mediationContext.base-url", url,
                "summary.contexts.mediationContext.tenant", "btcl", "summary.contexts.mediationContext.token-ref", "env:PATH"};
        for (int i = 0; i < keys.length; i += 2) System.setProperty(keys[i], keys[i + 1]);
        try {
            assertEquals(url, ConfigProvider.getConfig().getValue("summary.contexts.mediationContext.base-url", String.class), "only the stand-in is dialled");
            new ContextRegistry(new ConfigManagerClient()).ensureLoaded("mediationContext");
        } finally {
            for (int i = 0; i < keys.length; i += 2) System.clearProperty(keys[i]);
        }
        assertEquals(1, asked.size());
        assertTrue(asked.get(0).endsWith("| Bearer " + System.getenv("PATH").trim()), "the context's variable, from the process's own environment");
    }
}
