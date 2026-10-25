package com.telcobright.summary.tenancy.internal;

import com.sun.net.httpserver.HttpServer;
import com.telcobright.summary.treeroads.api.TreeRoadBearer;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tree is asked of prime-context on the road config-manager serves — {@code POST /get-specific-tenant-root} —
 * a READ. The stand-in here listens on 127.0.0.1 only; no real prime-context is dialled by a test.
 *
 * <p>HARDEN-2: the road carries {@code Authorization: Bearer <token>} when the profile's block names the token's variable
 * ({@code summary.tenants.prime-context.token-ref: env:<VAR>}), and NO Authorization header when it names none — as before.
 */
class PrimeContextTreeSourceTest {

    private HttpServer primeContext;
    private static final String TOKEN = "pct_tree-road-token-0123456789abcdef";
    private static final String BLOCK = "summary.tenants.prime-context.";
    private static final TreeRoadBearer NO_BEARER = TreeRoadBearer.none(BLOCK);

    private final List<String> asked = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private volatile String answer = TenantTreeParserTest.tree();
    private volatile int status = 200;

    @BeforeEach
    void aStandInOnLoopback() throws IOException {
        primeContext = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        primeContext.createContext("/", exchange -> {
            asked.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(authorization == null ? "(no Authorization header)" : authorization);
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
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

    private String baseUrl() {
        return "http://127.0.0.1:" + primeContext.getAddress().getPort();
    }

    @Test
    void the_tree_is_read_with_one_post_on_the_road_config_manager_serves() throws Exception {
        List<String> schemas = new PrimeContextTreeSource(baseUrl(), "btcl", NO_BEARER).schemas();

        assertEquals(List.of("btcl", "res_44", "res_44_7", "res_45"), schemas);
        assertEquals(List.of("POST /get-specific-tenant-root?name=btcl"), asked, "one read road, nothing else — never a road that writes");
    }

    @Test
    void a_base_url_with_a_trailing_slash_asks_the_same_road() throws Exception {
        new PrimeContextTreeSource(baseUrl() + "/", "btcl", NO_BEARER).schemas();

        assertEquals(List.of("POST /get-specific-tenant-root?name=btcl"), asked);
    }

    @Test
    void the_tree_of_another_root_is_refused_never_served_by_mistake() {
        // prime-context serves ONE tree and ignores the name it is asked: a profile pointed at the wrong one must not serve its schemas
        IOException refused = assertThrows(IOException.class, () -> new PrimeContextTreeSource(baseUrl(), "link3", NO_BEARER).schemas());

        assertTrue(refused.getMessage().contains("serves the tree of 'btcl'") && refused.getMessage().contains("root is 'link3'"), refused.getMessage());
    }

    @Test
    void an_answer_that_is_not_200_is_a_failure_said_with_its_status() {
        status = 503;
        answer = "{\"error\":\"rebuilding\"}";

        IOException failed = assertThrows(IOException.class, () -> new PrimeContextTreeSource(baseUrl(), "btcl", NO_BEARER).schemas());

        assertTrue(failed.getMessage().contains("HTTP 503"), failed.getMessage());
    }

    @Test
    void a_prime_context_that_does_not_answer_is_a_failure_not_an_empty_tree() {
        String dead = baseUrl();
        primeContext.stop(0);

        assertThrows(IOException.class, () -> new PrimeContextTreeSource(dead, "btcl", NO_BEARER).schemas());
    }

    // ---- HARDEN-2: the bearer ----

    @Test
    void the_bearer_its_block_names_rides_the_tree_road() throws Exception {
        new PrimeContextTreeSource(baseUrl(), "btcl", bearerOf("env:PRIME_CONTEXT_TOKEN", Map.of("PRIME_CONTEXT_TOKEN", TOKEN))).schemas();

        assertEquals(List.of("POST /get-specific-tenant-root?name=btcl"), asked);
        assertEquals(List.of("Bearer " + TOKEN), authorizations);
    }

    @Test
    void with_no_bearer_named_the_tree_road_is_asked_with_no_authorization_header_as_before() throws Exception {
        new PrimeContextTreeSource(baseUrl(), "btcl", bearerOf(null, Map.of())).schemas();

        assertEquals(List.of("(no Authorization header)"), authorizations);
    }

    @Test
    void a_refusal_of_the_tree_road_says_which_bearer_was_shown_never_its_value() {
        status = 401;
        answer = "{\"error\":\"UNAUTHORIZED\"}";

        IOException refused = assertThrows(IOException.class, () -> new PrimeContextTreeSource(baseUrl(), "btcl",
                bearerOf("env:PRIME_CONTEXT_TOKEN", Map.of("PRIME_CONTEXT_TOKEN", TOKEN))).schemas());
        IOException unauthenticated = assertThrows(IOException.class, () -> new PrimeContextTreeSource(baseUrl(), "btcl", NO_BEARER).schemas());

        assertTrue(refused.getMessage().contains("HTTP 401") && refused.getMessage().contains(
                "tree roads: bearer from PRIME_CONTEXT_TOKEN (summary.tenants.prime-context.token-ref)"), refused.getMessage());
        assertFalse(refused.getMessage().contains(TOKEN), "never the value: " + refused.getMessage());
        assertTrue(unauthenticated.getMessage().contains("tree roads: no bearer (summary.tenants.prime-context.token-ref is not set)"),
                unauthenticated.getMessage());
    }

    /** The bearer the block names in a profile that holds {@code tokenRef} (null: the key is absent), read from {@code environment}. */
    private static TreeRoadBearer bearerOf(String tokenRef, Map<String, String> environment) {
        Map<String, String> profile = tokenRef == null ? Map.of() : Map.of(BLOCK + "token-ref", tokenRef);
        return TreeRoadBearer.of(new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(profile, "a test's profile", 300)).build(),
                BLOCK, environment::get);
    }
}
