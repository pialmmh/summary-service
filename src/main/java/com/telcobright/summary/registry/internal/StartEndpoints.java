package com.telcobright.summary.registry.internal;

import com.telcobright.summary.runtime.internal.UrlSecrets;
import org.eclipse.microprofile.config.Config;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What a start will DIAL, resolved from the active configuration BEFORE anything is dialled — and shown. A start
 * that names one tenant must never reach another's box: the service says, in one line each, the database URL, the
 * Kafka brokers and every configuration source's base URL it resolved, and where it listens.
 *
 * <p>Two switches use the list (both off in a deployment):
 * <ul>
 *   <li>{@code summary.endpoints.print-only=true} — print the list and exit; nothing is started, nothing is
 *       dialled. The exit code says whether every host is this box (0) or not (3); 4 = the hosts are, but the
 *       configuration refuses the start — the profile has a fault, the store's password is named by a variable
 *       that is not in the environment, its engine contradicts its URL, a password rides in the URL (a line says
 *       which, never a value);</li>
 *   <li>{@code summary.endpoints.local-only=true} — the LAB's rule (brief S11): the start is refused, in words,
 *       unless every host is THIS BOX: the word {@code localhost}, a loopback address, or an address one of this
 *       box's own interfaces holds (a real prime-context never listens on loopback; a lab's bridge or namespace
 *       gives it an address of this box). A host NAME is never looked up — the lookup itself would leave the box —
 *       and is refused; so is a listener on every interface. An address of another box is never dialled from a
 *       lab, not even for a read. (The key's first name, {@code summary.endpoints.loopback-only}, is the same
 *       switch.)</li>
 * </ul>
 * {@code tools/lab/run-lab.sh} does both: it shows the list first, and starts only a start that would stay on this box.
 */
public final class StartEndpoints {

    /** The mark of a machine-readable line: {@code ENDPOINT <what> <value> hosts=<h,h> <LOOPBACK|THIS-BOX|NOT-THIS-BOX|NOT-SET>}. */
    public static final String LINE_MARK = "ENDPOINT";

    /** The lab's key: a start that would reach anything but this box is refused. */
    public static final String LOCAL_ONLY_KEY = "summary.endpoints.local-only";
    /** The key's first name, from when the check knew the loopback names only. The same switch. */
    public static final String LOCAL_ONLY_FIRST_NAME = "summary.endpoints.loopback-only";

    /** One thing the start will reach: what it is, the configured value, and the hosts in it. */
    public record Endpoint(String what, String value, List<String> hosts) {

        public boolean set() {
            return value != null && !value.isBlank();
        }

        /** Every host is this box. A value with no host that can be read is NOT taken for this box. */
        public boolean thisBoxOnly() {
            return thisBoxOnly(StartEndpoints::isThisBox);
        }

        boolean thisBoxOnly(Predicate<String> isThisBox) {
            return !hosts.isEmpty() && hosts.stream().allMatch(isThisBox);
        }

        public String line() {
            return line(StartEndpoints::isThisBox);
        }

        String line(Predicate<String> isThisBox) {
            String verdict = !set() ? "NOT-SET" : thisBoxOnly(StartEndpoints::isLoopback) ? "LOOPBACK" : thisBoxOnly(isThisBox) ? "THIS-BOX" : "NOT-THIS-BOX";
            return LINE_MARK + " " + what + " " + (set() ? shown(value) : "-") + " hosts=" + (hosts.isEmpty() ? "-" : String.join(",", hosts)) + " " + verdict;
        }
    }

    private StartEndpoints() {
    }

    /** Everything this start would reach with the given beans enabled, in the order it would reach them. */
    public static List<Endpoint> resolve(Config config, List<String> enabledBeans) {
        List<Endpoint> endpoints = new ArrayList<>();
        endpoints.add(endpoint("store", text(config, "summary.store.url", null)));
        endpoints.add(endpoint("ping-kafka", text(config, "summary.outbox.ping-bootstrap-servers", "127.0.0.1:9092")));
        if ("tree".equalsIgnoreCase(text(config, "summary.tenants.mode", "single"))) {
            endpoints.add(endpoint("tree-prime-context", text(config, "summary.tenants.prime-context.base-url", null)));
            endpoints.add(endpoint("doorbell-kafka", text(config, "summary.tenants.doorbell.bootstrap-servers",
                    text(config, "summary.outbox.ping-bootstrap-servers", "127.0.0.1:9092"))));
        }
        Set<String> contexts = new LinkedHashSet<>();
        for (String bean : enabledBeans) {
            String context = text(config, "summary.beans." + bean.trim() + ".context", null);
            if (context != null) {
                contexts.add(context);
            }
        }
        for (String context : contexts) {
            endpoints.add(endpoint("context-" + context, text(config, "summary.contexts." + context + ".base-url", null)));
        }
        endpoints.add(endpoint("listens-on", text(config, "quarkus.http.host", "0.0.0.0") + ":" + text(config, "quarkus.http.port", "8080")));
        return endpoints;
    }

    /**
     * A value as it may be printed: a password in a URL is hidden, in every form {@link UrlSecrets} knows. (A store
     * URL that carries one is refused at the start anyway — a secret is never in a URL — but the endpoints are said
     * before that, and must not leak it.)
     */
    static String shown(String value) {
        return UrlSecrets.hidden(value);
    }

    /** Is this a lab start — {@code summary.endpoints.local-only=true} (or the key's first name)? */
    public static boolean isALabStart(Config config) {
        return config.getOptionalValue(LOCAL_ONLY_KEY, Boolean.class).orElse(false)
                || config.getOptionalValue(LOCAL_ONLY_FIRST_NAME, Boolean.class).orElse(false);
    }

    /** The endpoints that are set and not on this box. */
    public static List<Endpoint> notThisBox(List<Endpoint> endpoints) {
        return notThisBox(endpoints, StartEndpoints::isThisBox);
    }

    static List<Endpoint> notThisBox(List<Endpoint> endpoints, Predicate<String> isThisBox) {
        return endpoints.stream().filter(e -> e.set() && !e.thisBoxOnly(isThisBox)).toList();
    }

    /** The lab's rule: refuse, in words, a start that would reach or listen on anything but this box. Dials nothing, looks up no name. */
    public static void requireThisBoxOnly(List<Endpoint> endpoints) {
        requireThisBoxOnly(endpoints, StartEndpoints::isThisBox);
    }

    static void requireThisBoxOnly(List<Endpoint> endpoints, Predicate<String> isThisBox) {
        List<Endpoint> elsewhere = notThisBox(endpoints, isThisBox);
        if (!elsewhere.isEmpty()) {
            StringBuilder said = new StringBuilder("REFUSING TO START: " + LOCAL_ONLY_KEY + " is set (a lab start) and "
                    + elsewhere.size() + " endpoint(s) are not on this box —");
            for (Endpoint e : elsewhere) {
                said.append(' ').append(e.what()).append('=').append(shown(e.value())).append(';');
            }
            said.append(" a lab dials only this box — localhost, a loopback address, or an address one of its own interfaces holds;"
                    + " a host name is never looked up, and a listener is bound to one address — and never another box, not even for a read."
                    + " Was the lab's own profile read? The PROFILE line says which one was.");
            throw new IllegalStateException(said.toString());
        }
    }

    static Endpoint endpoint(String what, String value) {
        return new Endpoint(what, value, value == null || value.isBlank() ? List.of() : hostsOf(value));
    }

    /**
     * The hosts in a configured value: a JDBC URL ({@code jdbc:postgresql://h:5432/db}, also MySQL's
     * {@code h1:3306,h2:3306} list), an HTTP base URL, or a {@code host:port[,host:port]} list (Kafka, a listener).
     * Empty when no host can be read — which is never taken for "local".
     */
    public static List<String> hostsOf(String value) {
        String text = value.trim();
        int scheme = text.indexOf("://");                      // jdbc:postgresql://…, jdbc:mysql://…, http://…
        boolean url = scheme >= 0;
        if (url) {
            text = text.substring(scheme + 3);
            for (char end : new char[] {'/', '?', '#'}) {
                int at = text.indexOf(end);
                if (at >= 0) text = text.substring(0, at);
            }
        }
        List<String> hosts = new ArrayList<>();
        for (String part : text.split(",")) {
            int user = url ? part.lastIndexOf('@') : -1;       // user[:password]@host — each host of a list may carry its own
            String host = hostOf(part.substring(user + 1).trim());
            if (host == null) {
                return List.of();                    // one part that cannot be read: the whole value is unread
            }
            hosts.add(host);
        }
        return hosts;
    }

    private static String hostOf(String hostAndPort) {
        if (hostAndPort.isEmpty()) {
            return null;
        }
        if (hostAndPort.startsWith("[")) {                               // [::1]:9092
            int close = hostAndPort.indexOf(']');
            return close < 0 ? null : hostAndPort.substring(1, close);
        }
        int colon = hostAndPort.lastIndexOf(':');
        String host = colon > 0 && hostAndPort.indexOf(':') == colon ? hostAndPort.substring(0, colon) : hostAndPort;
        return host.matches("[A-Za-z0-9._:-]+") ? host : null;
    }

    /** This box, by its loopback names: the word {@code localhost}, {@code 127.x.x.x}, {@code ::1}. No name is looked up. */
    public static boolean isLoopback(String host) {
        if (host != null && host.trim().equalsIgnoreCase("localhost")) {
            return true;
        }
        InetAddress address = addressOf(host);
        return address != null && address.isLoopbackAddress();
    }

    /**
     * This box: a loopback name, or an ADDRESS that one of this box's own interfaces holds (a lab's bridge, a
     * namespace's own address — where a real prime-context listens). A host NAME is never looked up — the lookup
     * itself would leave the box — so a name is not this box; nor is the any-address ({@code 0.0.0.0}).
     */
    public static boolean isThisBox(String host) {
        return isThisBox(host, StartEndpoints::heldByAnInterfaceOfThisBox);
    }

    static boolean isThisBox(String host, Predicate<InetAddress> heldByAnInterfaceOfThisBox) {
        if (isLoopback(host)) {
            return true;
        }
        InetAddress address = addressOf(host);
        return address != null && !address.isAnyLocalAddress() && heldByAnInterfaceOfThisBox.test(address);
    }

    private static boolean heldByAnInterfaceOfThisBox(InetAddress address) {
        try {
            return NetworkInterface.getByInetAddress(address) != null;
        } catch (SocketException | RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * The address a host is WRITTEN as — an IPv4 or IPv6 literal — or null when it is a name (or nothing readable).
     * A literal is parsed, never looked up: four numbers up to 255 are made into an address directly, and an IPv6
     * text is asked for in brackets, which the JDK parses or refuses without a lookup.
     */
    static InetAddress addressOf(String host) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        try {
            if (h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                String[] numbers = h.split("\\.");
                byte[] bytes = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int number = Integer.parseInt(numbers[i]);
                    if (number > 255) {
                        return null;
                    }
                    bytes[i] = (byte) number;
                }
                return InetAddress.getByAddress(bytes);
            }
            return h.contains(":") && h.matches("[0-9a-f:.]+") ? InetAddress.getByName("[" + h + "]") : null;
        } catch (UnknownHostException | RuntimeException notAnAddress) {
            return null;
        }
    }

    private static String text(Config config, String key, String otherwise) {
        return config.getOptionalValue(key, String.class).map(String::trim).filter(v -> !v.isEmpty()).orElse(otherwise);
    }
}
