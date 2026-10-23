package com.telcobright.summary.registry.internal;

import com.telcobright.summary.config.internal.ProfileYamlLoader;
import com.telcobright.summary.registry.internal.StartEndpoints.Endpoint;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lab's rule (the architect, 2026-10-04, after a lab start dialled another tenant's config-manager): BEFORE a
 * service starts in a lab it shows the endpoints it resolved — the database URL, the Kafka bootstrap, each
 * configuration source's base URL — and it is not started unless every host is THIS BOX: localhost, a loopback
 * address, or (brief S11) an address one of this box's own interfaces holds — a real prime-context never listens
 * on loopback. An address of another box is never dialled from a lab, not even for a read.
 *
 * <p>Which addresses this box holds is the machine's own fact: the tests say it themselves ({@link #aBoxHolding}),
 * and one test asks the real interfaces.
 */
class StartEndpointsTest {

    /** A box whose interfaces hold exactly these addresses (beside its loopback). */
    private static Predicate<String> aBoxHolding(String... addresses) {
        return host -> StartEndpoints.isThisBox(host, address -> Arrays.asList(addresses).contains(address.getHostAddress()));
    }

    private static final Predicate<String> A_BOX_WITH_LOOPBACK_ONLY = aBoxHolding();

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
        assertTrue(StartEndpoints.notThisBox(endpoints, A_BOX_WITH_LOOPBACK_ONLY).isEmpty(), "every host is this machine: " + endpoints);
        assertDoesNotThrow(() -> StartEndpoints.requireThisBoxOnly(endpoints, A_BOX_WITH_LOOPBACK_ONLY));
    }

    @Test
    void a_listener_on_every_interface_is_not_a_lab_start() {
        Config lab = profileOf("btcl/lab");                                  // no quarkus.http.host: Quarkus binds 0.0.0.0

        List<Endpoint> elsewhere = StartEndpoints.notThisBox(StartEndpoints.resolve(lab, enabledOf(lab)));

        assertEquals(1, elsewhere.size());
        assertEquals("ENDPOINT listens-on 0.0.0.0:8080 hosts=0.0.0.0 NOT-THIS-BOX", elsewhere.get(0).line(),
                "the any-address is held by no interface: a listener on every interface is reachable from other boxes");
    }

    @Test
    void the_voice_tenants_profile_is_refused_in_a_lab_with_every_box_it_would_dial_named() {
        // tcbl/dev names CCL's database, Kafka broker and config-manager: exactly what a lab must never reach
        Config tcbl = profileOf("tcbl/dev", "quarkus.http.host", "127.0.0.1");
        List<Endpoint> endpoints = StartEndpoints.resolve(tcbl, enabledOf(tcbl));

        List<String> elsewhere = StartEndpoints.notThisBox(endpoints, A_BOX_WITH_LOOPBACK_ONLY).stream().map(Endpoint::what).toList();
        assertEquals(List.of("store", "ping-kafka", "context-mediationContext"), elsewhere);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> StartEndpoints.requireThisBoxOnly(endpoints, A_BOX_WITH_LOOPBACK_ONLY));
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

        assertEquals(List.of("tree-prime-context"), StartEndpoints.notThisBox(endpoints, A_BOX_WITH_LOOPBACK_ONLY).stream().map(Endpoint::what).toList());
        assertTrue(endpoints.stream().anyMatch(e -> e.line().equals("ENDPOINT doorbell-kafka 127.0.0.1:7692 hosts=127.0.0.1 LOOPBACK")),
                "the doorbell's broker is the ping's unless the profile names another");
    }

    @Test
    void a_store_that_is_not_set_dials_nothing_and_says_so() {
        Config none = new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(Map.of("quarkus.http.host", "localhost"), "p", 300)).build();

        List<Endpoint> endpoints = StartEndpoints.resolve(none, List.of());

        assertEquals("ENDPOINT store - hosts=- NOT-SET", endpoints.get(0).line());
        assertTrue(StartEndpoints.notThisBox(endpoints).isEmpty());
    }

    @Test
    void a_password_in_a_url_is_never_printed_with_the_endpoints() {
        // such a URL is refused at the start (a secret is never in a URL) — but the endpoints are shown first
        Endpoint store = StartEndpoints.endpoint("store", "jdbc:postgresql://127.0.0.1:7643/routesphere?user=x&password=s3cret-in-a-url&currentSchema=btcl");

        assertEquals("ENDPOINT store jdbc:postgresql://127.0.0.1:7643/routesphere?user=x&password=<hidden>&currentSchema=btcl hosts=127.0.0.1 LOOPBACK", store.line());
        assertFalse(StartEndpoints.endpoint("store", "jdbc:mysql://h:3306/db?PWD=s3cret-in-a-url").line().contains("s3cret-in-a-url"));
        assertEquals("ENDPOINT store jdbc:mysql://<hidden>@127.0.0.1:3306/telcobright hosts=127.0.0.1 LOOPBACK",
                StartEndpoints.endpoint("store", "jdbc:mysql://summary:s3cret-in-a-url@127.0.0.1:3306/telcobright").line(), "user:password@ before the host");
        assertFalse(StartEndpoints.endpoint("store", "jdbc:mysql://(host=127.0.0.1,port=3306,password=s3cret-in-a-url)/db").line().contains("s3cret-in-a-url"));
    }

    @Test
    void the_refusal_of_a_lab_start_names_the_box_and_never_a_password() {
        // the refusal is said BEFORE the store's own check: it must not be the place a password in a URL leaks
        List<Endpoint> endpoints = List.of(StartEndpoints.endpoint("store", "jdbc:postgresql://10.10.9.9:5432/routesphere?user=x&password=s3cret-in-a-url"),
                StartEndpoints.endpoint("tree-prime-context", "http://reader:s3cret-in-a-url@10.10.9.9:7091"));

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> StartEndpoints.requireThisBoxOnly(endpoints, A_BOX_WITH_LOOPBACK_ONLY));

        assertTrue(refused.getMessage().contains("store=jdbc:postgresql://10.10.9.9:5432/routesphere?user=x&password=<hidden>;"), refused.getMessage());
        assertTrue(refused.getMessage().contains("tree-prime-context=http://<hidden>@10.10.9.9:7091;"), refused.getMessage());
        assertFalse(refused.getMessage().contains("s3cret-in-a-url"), refused.getMessage());
    }

    @Test
    void the_hosts_of_every_kind_of_value_are_read() {
        assertEquals(List.of("127.0.0.1"), StartEndpoints.hostsOf("jdbc:postgresql://127.0.0.1:7643/routesphere?currentSchema=btcl"));
        assertEquals(List.of("103.95.96.77"), StartEndpoints.hostsOf("jdbc:mysql://103.95.96.77:3306/telcobright?useSSL=false&a=b"));
        assertEquals(List.of("db1", "db2"), StartEndpoints.hostsOf("jdbc:mysql://db1:3306,db2:3306/telcobright"));
        assertEquals(List.of("10.10.199.1"), StartEndpoints.hostsOf("http://10.10.199.1:7091"));
        assertEquals(List.of("prime.example"), StartEndpoints.hostsOf("https://user@prime.example/base"));
        assertEquals(List.of("10.10.9.9", "127.0.0.1"), StartEndpoints.hostsOf("jdbc:mysql://u:p@10.10.9.9:3306,u:p@127.0.0.1:3306/db"),
                "each host of a list may carry its own user: EVERY host is read, not only the last");
        assertEquals(List.of("127.0.0.1", "10.10.188.2"), StartEndpoints.hostsOf("127.0.0.1:7692, 10.10.188.2:9092"));
        assertEquals(List.of("::1"), StartEndpoints.hostsOf("[::1]:9092"));
        assertEquals(List.of("localhost"), StartEndpoints.hostsOf("localhost"));
    }

    @Test
    void the_loopback_names_are_this_box_and_a_value_that_cannot_be_read_never_is() {
        for (String local : new String[] {"127.0.0.1", "127.8.9.10", "localhost", "LOCALHOST", "::1", "0:0:0:0:0:0:0:1"}) {
            assertTrue(StartEndpoints.isLoopback(local), local);
            assertTrue(A_BOX_WITH_LOOPBACK_ONLY.test(local), local);
        }
        for (String box : new String[] {"103.95.96.78", "10.10.188.2", "172.17.191.1", "0.0.0.0", "localhost.evil.example", "1127.0.0.1", "", "127.0.0.1.example",
                "127.999.1.1", "127.0.0", null}) {
            assertFalse(StartEndpoints.isLoopback(box), box);
            assertFalse(A_BOX_WITH_LOOPBACK_ONLY.test(box), box);
        }
        Endpoint unread = StartEndpoints.endpoint("store", "jdbc:postgresql://exa mple/db");
        assertTrue(unread.hosts().isEmpty(), "no host could be read");
        assertFalse(unread.thisBoxOnly(), "an unread value is not taken for this box");
        assertEquals(1, StartEndpoints.notThisBox(List.of(unread)).size());
        assertFalse(StartEndpoints.endpoint("ping-kafka", "127.0.0.1:7692,103.95.96.78:9092").thisBoxOnly(A_BOX_WITH_LOOPBACK_ONLY), "ONE box in a list is enough");
    }

    // ---- brief S11: the lab key means "this box", not "loopback" ----

    @Test
    void an_address_one_of_this_boxs_own_interfaces_holds_is_this_box_and_another_boxs_is_not() {
        // a real prime-context never listens on loopback (its bind guard): a lab gives it an address of this box
        Predicate<String> thisBox = aBoxHolding("10.10.250.1");
        List<Endpoint> rehearsal = List.of(StartEndpoints.endpoint("store", "jdbc:postgresql://127.0.0.1:7843/routesphere"),
                StartEndpoints.endpoint("tree-prime-context", "http://10.10.250.1:17091"), StartEndpoints.endpoint("listens-on", "10.10.250.1:7671"));

        assertTrue(StartEndpoints.notThisBox(rehearsal, thisBox).isEmpty());
        assertDoesNotThrow(() -> StartEndpoints.requireThisBoxOnly(rehearsal, thisBox));
        assertEquals("ENDPOINT tree-prime-context http://10.10.250.1:17091 hosts=10.10.250.1 THIS-BOX", rehearsal.get(1).line(thisBox));
        assertEquals("ENDPOINT store jdbc:postgresql://127.0.0.1:7843/routesphere hosts=127.0.0.1 LOOPBACK", rehearsal.get(0).line(thisBox));

        // the SAME start on a box that does not hold that address: refused, the box named, before anything is dialled
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> StartEndpoints.requireThisBoxOnly(rehearsal, aBoxHolding("10.10.251.1")));
        assertTrue(refused.getMessage().startsWith("REFUSING TO START: summary.endpoints.local-only is set (a lab start) and 2 endpoint(s) are not on this box"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains("tree-prime-context=http://10.10.250.1:17091;") && refused.getMessage().contains("listens-on=10.10.250.1:7671;"),
                refused.getMessage());
        assertEquals("ENDPOINT tree-prime-context http://10.10.250.1:17091 hosts=10.10.250.1 NOT-THIS-BOX", rehearsal.get(1).line(aBoxHolding("10.10.251.1")));
        assertFalse(StartEndpoints.endpoint("ping-kafka", "10.10.250.1:7892,10.10.9.9:9092").thisBoxOnly(thisBox), "ONE other box in a list is enough");
    }

    @Test
    void a_host_name_is_never_looked_up_and_is_never_this_box() {
        // the lookup itself would leave the box. Only what is WRITTEN as an address is one; the interfaces are not even asked for a name
        Predicate<InetAddress> mustNotBeAsked = address -> {
            throw new AssertionError("the interfaces were asked for " + address);
        };
        for (String name : new String[] {"prime-context.internal", "db1", "localhost.evil.example", "999.1.1.1", "10.10.250", "10.10.250.1.example", "fe80::1%eth0", ""}) {
            assertEquals(null, StartEndpoints.addressOf(name), name + " is not an address written out");
            assertFalse(StartEndpoints.isThisBox(name, mustNotBeAsked), name);
        }
        assertEquals("10.10.250.1", StartEndpoints.addressOf("10.10.250.1").getHostAddress());
        assertTrue(StartEndpoints.addressOf("::1").isLoopbackAddress());
        assertEquals(null, StartEndpoints.addressOf("localhost"), "the word is this box by itself, not by a lookup");
    }

    @Test
    void the_any_address_is_never_this_box_even_when_the_interfaces_would_say_so() {
        for (String every : new String[] {"0.0.0.0", "::", "0:0:0:0:0:0:0:0"}) {
            assertFalse(StartEndpoints.isThisBox(every, address -> true), every + " listens on every interface: reachable from other boxes");
        }
    }

    @Test
    void the_real_interfaces_of_this_machine_are_this_box_and_an_address_no_box_holds_is_not() throws SocketException {
        for (NetworkInterface each : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InetAddress own : Collections.list(each.getInetAddresses())) {
                if (own instanceof Inet4Address) {
                    assertTrue(StartEndpoints.isThisBox(own.getHostAddress()), own.getHostAddress() + " is held by " + each.getName());
                }
            }
        }
        assertFalse(StartEndpoints.isThisBox("192.0.2.1"), "TEST-NET-1 (RFC 5737): no interface holds it");
        assertFalse(StartEndpoints.endpoint("store", "jdbc:postgresql://192.0.2.1:5432/routesphere").thisBoxOnly());
    }

    @Test
    void the_lab_key_is_local_only_and_its_first_name_is_the_same_switch() {
        assertTrue(StartEndpoints.isALabStart(configOf("summary.endpoints.local-only", "true")));
        assertTrue(StartEndpoints.isALabStart(configOf("summary.endpoints.loopback-only", "true")), "a kit that still sets the first name keeps its guard");
        assertFalse(StartEndpoints.isALabStart(configOf("summary.endpoints.local-only", "false")));
        assertFalse(StartEndpoints.isALabStart(configOf("summary.zone", "Asia/Dhaka")), "a deployment sets neither");
    }

    private static Config configOf(String key, String value) {
        return new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(Map.of(key, value), "a profile", 300)).build();
    }
}
