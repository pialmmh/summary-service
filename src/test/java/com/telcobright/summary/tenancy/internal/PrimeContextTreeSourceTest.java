package com.telcobright.summary.tenancy.internal;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tree is asked of prime-context on the road config-manager serves — {@code POST /get-specific-tenant-root} —
 * a READ. The stand-in here listens on 127.0.0.1 only; no real prime-context is dialled by a test.
 */
class PrimeContextTreeSourceTest {

    private HttpServer primeContext;
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private volatile String answer = TenantTreeParserTest.tree();
    private volatile int status = 200;

    @BeforeEach
    void aStandInOnLoopback() throws IOException {
        primeContext = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        primeContext.createContext("/", exchange -> {
            asked.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
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
        List<String> schemas = new PrimeContextTreeSource(baseUrl(), "btcl").schemas();

        assertEquals(List.of("btcl", "res_44", "res_44_7", "res_45"), schemas);
        assertEquals(List.of("POST /get-specific-tenant-root?name=btcl"), asked, "one read road, nothing else — never a road that writes");
    }

    @Test
    void a_base_url_with_a_trailing_slash_asks_the_same_road() throws Exception {
        new PrimeContextTreeSource(baseUrl() + "/", "btcl").schemas();

        assertEquals(List.of("POST /get-specific-tenant-root?name=btcl"), asked);
    }

    @Test
    void the_tree_of_another_root_is_refused_never_served_by_mistake() {
        // prime-context serves ONE tree and ignores the name it is asked: a profile pointed at the wrong one must not serve its schemas
        IOException refused = assertThrows(IOException.class, () -> new PrimeContextTreeSource(baseUrl(), "link3").schemas());

        assertTrue(refused.getMessage().contains("serves the tree of 'btcl'") && refused.getMessage().contains("root is 'link3'"), refused.getMessage());
    }

    @Test
    void an_answer_that_is_not_200_is_a_failure_said_with_its_status() {
        status = 503;
        answer = "{\"error\":\"rebuilding\"}";

        IOException failed = assertThrows(IOException.class, () -> new PrimeContextTreeSource(baseUrl(), "btcl").schemas());

        assertTrue(failed.getMessage().contains("HTTP 503"), failed.getMessage());
    }

    @Test
    void a_prime_context_that_does_not_answer_is_a_failure_not_an_empty_tree() {
        String dead = baseUrl();
        primeContext.stop(0);

        assertThrows(IOException.class, () -> new PrimeContextTreeSource(dead, "btcl").schemas());
    }
}
