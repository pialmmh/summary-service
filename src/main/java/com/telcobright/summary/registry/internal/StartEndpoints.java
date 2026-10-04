package com.telcobright.summary.registry.internal;

import com.telcobright.summary.runtime.internal.UrlSecrets;
import org.eclipse.microprofile.config.Config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a start will DIAL, resolved from the active configuration BEFORE anything is dialled — and shown. A start
 * that names one tenant must never reach another's box: the service says, in one line each, the database URL, the
 * Kafka brokers and every configuration source's base URL it resolved, and where it listens.
 *
 * <p>Two switches use the list (both off in a deployment):
 * <ul>
 *   <li>{@code summary.endpoints.print-only=true} — print the list and exit; nothing is started, nothing is
 *       dialled. The exit code says whether every host is this machine (0) or not (3); 4 = the hosts are, but the
 *       store's configuration refuses the start — its password is named by a variable that is not in the
 *       environment, its engine contradicts its URL, a password rides in the URL (a {@code SECRET} line says
 *       which, never a value);</li>
 *   <li>{@code summary.endpoints.loopback-only=true} — the LAB's rule: the start is refused, in words, unless every
 *       host is {@code 127.0.0.1} / {@code localhost}. An address of a box is never dialled from a lab, not even
 *       for a read.</li>
 * </ul>
 * {@code tools/lab/run-lab.sh} does both: it shows the list first, and starts only a start that would stay on this machine.
 */
public final class StartEndpoints {

    /** The mark of a machine-readable line: {@code ENDPOINT <what> <value> hosts=<h,h> <LOOPBACK|NOT-LOOPBACK|NOT-SET>}. */
    public static final String LINE_MARK = "ENDPOINT";

    /** One thing the start will reach: what it is, the configured value, and the hosts in it. */
    public record Endpoint(String what, String value, List<String> hosts) {

        public boolean set() {
            return value != null && !value.isBlank();
        }

        /** Every host is this machine. A value with no host that can be read is NOT taken for local. */
        public boolean loopbackOnly() {
            return !hosts.isEmpty() && hosts.stream().allMatch(StartEndpoints::isLoopback);
        }

        public String line() {
            String verdict = !set() ? "NOT-SET" : loopbackOnly() ? "LOOPBACK" : "NOT-LOOPBACK";
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

    /** The endpoints that are set and not on this machine. */
    public static List<Endpoint> notLoopback(List<Endpoint> endpoints) {
        return endpoints.stream().filter(e -> e.set() && !e.loopbackOnly()).toList();
    }

    /** The lab's rule: refuse, in words, a start that would reach or listen on anything but this machine. */
    public static void requireLoopbackOnly(List<Endpoint> endpoints) {
        List<Endpoint> elsewhere = notLoopback(endpoints);
        if (!elsewhere.isEmpty()) {
            StringBuilder said = new StringBuilder("REFUSING TO START: summary.endpoints.loopback-only is set (a lab start) and "
                    + elsewhere.size() + " endpoint(s) are not on this machine —");
            for (Endpoint e : elsewhere) {
                said.append(' ').append(e.what()).append('=').append(shown(e.value())).append(';');
            }
            said.append(" a lab never dials a box, not even for a read. Start the tenant's lab profile, or name loopback addresses.");
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

    /** This machine, by its loopback names: {@code 127.x.x.x}, {@code localhost}, {@code ::1}. Nothing is resolved by DNS. */
    public static boolean isLoopback(String host) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1") || h.matches("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    }

    private static String text(Config config, String key, String otherwise) {
        return config.getOptionalValue(key, String.class).map(String::trim).filter(v -> !v.isEmpty()).orElse(otherwise);
    }
}
