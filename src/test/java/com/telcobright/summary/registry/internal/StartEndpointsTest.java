package com.telcobright.summary.registry.internal;

import com.telcobright.summary.config.internal.ProfileYamlLoader;
import com.telcobright.summary.registry.internal.StartEndpoints.Endpoint;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lab's rule (the architect, 2026-10-04, after a lab start dialled another tenant's config-manager): BEFORE a
 * service starts in a lab it shows the endpoints it resolved — the database URL, the Kafka bootstrap, each
 * configuration source's base URL — and it is not started unless every host is 127.0.0.1 or localhost. An address
 * of a box is never dialled from a lab, not even for a read.
 */
class StartEndpointsTest {

    private static Config profileOf(String tenantSlashProfile, String... more) {
        String[] chosen = tenantSlashProfile.split("/");
        Map<String, String> properties = new HashMap<>(ProfileYamlLoader.loadProfile(new ProfileYamlLoader.ActiveTenant(chosen[0], chosen[1])));
        for (int i = 0; i < more.length; i += 2) properties.put(more[i], more[i + 1]);
        return new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(properties, "the profile", 300)).build();
    }

    private static List<String> enabledOf(Config config) {
        return Arrays.asList(config.getValue("summary.enabledSummary", String.class).split(","));
    }

    @Test
    void the_lab_profile_resolves_to_this_machine_only_when_its_listener_is_bound_to_it() {
        Config lab = profileOf("btcl/lab", "quarkus.http.host", "127.0.0.1", "quarkus.http.port", "7671");

        List<Endpoint> endpoints = StartEndpoints.resolve(lab, enabledOf(lab));

        assertEquals("ENDPOINT store jdbc:postgresql://127.0.0.1:7643/routesphere hosts=127.0.0.1 LOOPBACK", endpoints.get(0).line());
        assertEquals("ENDPOINT ping-kafka 127.0.0.1:7692 hosts=127.0.0.1 LOOPBACK", endpoints.get(1).line());
        assertTrue(StartEndpoints.notLoopback(endpoints).isEmpty(), "every host is this machine: " + endpoints);
        assertDoesNotThrow(() -> StartEndpoints.requireLoopbackOnly(endpoints));
    }

    @Test
    void a_listener_on_every_interface_is_not_a_lab_start() {
        Config lab = profileOf("btcl/lab");                                  // no quarkus.http.host: Quarkus binds 0.0.0.0

        List<Endpoint> elsewhere = StartEndpoints.notLoopback(StartEndpoints.resolve(lab, enabledOf(lab)));

        assertEquals(1, elsewhere.size());
        assertEquals("ENDPOINT listens-on 0.0.0.0:8080 hosts=0.0.0.0 NOT-LOOPBACK", elsewhere.get(0).line());
    }

    @Test
    void the_voice_tenants_profile_is_refused_in_a_lab_with_every_box_it_would_dial_named() {
        // tcbl/dev names CCL's database, Kafka broker and config-manager: exactly what a lab must never reach
        Config tcbl = profileOf("tcbl/dev", "quarkus.http.host", "127.0.0.1");
        List<Endpoint> endpoints = StartEndpoints.resolve(tcbl, enabledOf(tcbl));

        List<String> elsewhere = StartEndpoints.notLoopback(endpoints).stream().map(Endpoint::what).toList();
        assertEquals(List.of("store", "ping-kafka", "context-mediationContext"), elsewhere);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> StartEndpoints.requireLoopbackOnly(endpoints));
        assertTrue(refused.getMessage().startsWith("REFUSING TO START"), refused.getMessage());
        for (String box : new String[] {"103.95.96.77:3306", "103.95.96.78:9092", "http://103.95.96.78:7072"}) {
            assertTrue(refused.getMessage().contains(box), "the refusal names " + box + ": " + refused.getMessage());
        }
        assertTrue(refused.getMessage().contains("not even for a read"), refused.getMessage());
    }

    @Test
    void a_context_is_listed_only_when_an_enabled_bean_names_it() {
        Config tcbl = profileOf("tcbl/dev");

        assertTrue(StartEndpoints.resolve(tcbl, List.of("dailyChargeableSummary")).stream().anyMatch(e -> e.what().equals("context-mediationContext")));
        assertFalse(StartEndpoints.resolve(tcbl, List.of()).stream().anyMatch(e -> e.what().startsWith("context-")),
                "no bean enabled: its config-manager would not be dialled, so it is not listed");
    }

    @Test
    void a_tree_start_also_lists_prime_context_and_the_doorbells_broker() {
        Config tree = profileOf("btcl/lab", "summary.tenants.mode", "tree", "summary.tenants.prime-context.base-url", "http://10.10.199.1:7091",
                "quarkus.http.host", "127.0.0.1");

        List<Endpoint> endpoints = StartEndpoints.resolve(tree, enabledOf(tree));

        assertEquals(List.of("tree-prime-context"), StartEndpoints.notLoopback(endpoints).stream().map(Endpoint::what).toList());
        assertTrue(endpoints.stream().anyMatch(e -> e.line().equals("ENDPOINT doorbell-kafka 127.0.0.1:7692 hosts=127.0.0.1 LOOPBACK")),
                "the doorbell's broker is the ping's unless the profile names another");
    }

    @Test
    void a_store_that_is_not_set_dials_nothing_and_says_so() {
        Config none = new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(Map.of("quarkus.http.host", "localhost"), "p", 300)).build();

        List<Endpoint> endpoints = StartEndpoints.resolve(none, List.of());

        assertEquals("ENDPOINT store - hosts=- NOT-SET", endpoints.get(0).line());
        assertTrue(StartEndpoints.notLoopback(endpoints).isEmpty());
    }

    @Test
    void a_password_in_a_url_is_never_printed_with_the_endpoints() {
        // such a URL is refused at the start (a secret is never in a URL) — but the endpoints are shown first
        Endpoint store = StartEndpoints.endpoint("store", "jdbc:postgresql://127.0.0.1:7643/routesphere?user=x&password=s3cret-in-a-url&currentSchema=btcl");

        assertEquals("ENDPOINT store jdbc:postgresql://127.0.0.1:7643/routesphere?user=x&password=<hidden>&currentSchema=btcl hosts=127.0.0.1 LOOPBACK", store.line());
        assertFalse(StartEndpoints.endpoint("store", "jdbc:mysql://h:3306/db?PWD=s3cret-in-a-url").line().contains("s3cret-in-a-url"));
    }

    @Test
    void the_hosts_of_every_kind_of_value_are_read() {
        assertEquals(List.of("127.0.0.1"), StartEndpoints.hostsOf("jdbc:postgresql://127.0.0.1:7643/routesphere?currentSchema=btcl"));
        assertEquals(List.of("103.95.96.77"), StartEndpoints.hostsOf("jdbc:mysql://103.95.96.77:3306/telcobright?useSSL=false&a=b"));
        assertEquals(List.of("db1", "db2"), StartEndpoints.hostsOf("jdbc:mysql://db1:3306,db2:3306/telcobright"));
        assertEquals(List.of("10.10.199.1"), StartEndpoints.hostsOf("http://10.10.199.1:7091"));
        assertEquals(List.of("prime.example"), StartEndpoints.hostsOf("https://user@prime.example/base"));
        assertEquals(List.of("127.0.0.1", "10.10.188.2"), StartEndpoints.hostsOf("127.0.0.1:7692, 10.10.188.2:9092"));
        assertEquals(List.of("::1"), StartEndpoints.hostsOf("[::1]:9092"));
        assertEquals(List.of("localhost"), StartEndpoints.hostsOf("localhost"));
    }

    @Test
    void only_the_loopback_names_are_this_machine_and_a_value_that_cannot_be_read_never_is() {
        for (String local : new String[] {"127.0.0.1", "127.8.9.10", "localhost", "LOCALHOST", "::1"}) assertTrue(StartEndpoints.isLoopback(local), local);
        for (String box : new String[] {"103.95.96.78", "10.10.188.2", "172.17.191.1", "0.0.0.0", "localhost.evil.example", "1127.0.0.1", "", "127.0.0.1.example"}) {
            assertFalse(StartEndpoints.isLoopback(box), box);
        }
        Endpoint unread = StartEndpoints.endpoint("store", "jdbc:postgresql://exa mple/db");
        assertTrue(unread.hosts().isEmpty(), "no host could be read");
        assertFalse(unread.loopbackOnly(), "an unread value is not taken for local");
        assertEquals(1, StartEndpoints.notLoopback(List.of(unread)).size());
        assertFalse(StartEndpoints.endpoint("ping-kafka", "127.0.0.1:7692,103.95.96.78:9092").loopbackOnly(), "ONE box in a list is enough");
    }
}
