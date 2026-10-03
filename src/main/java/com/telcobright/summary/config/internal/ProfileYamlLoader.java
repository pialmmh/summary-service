package com.telcobright.summary.config.internal;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The routesphere-like loader: reads {@code config/tenants.yml} to find the active tenant + profile, then
 * loads + FLATTENS {@code config/tenants/<tenant>/<profile>/profile-<profile>.yml} into dot-notation
 * properties (e.g. {@code quarkus.datasource.jdbc.url}, {@code summary.beans.dailyCallSummary.table}). Missing files
 * yield empty results — the app still boots (it just has no tenant config), never a hard failure here.
 */
public final class ProfileYamlLoader {

    public record ActiveTenant(String name, String profile) {
    }

    private ProfileYamlLoader() {
    }

    /** Names the tenant and profile a START serves, over the registry file: {@code <tenant>/<profile>}. */
    public static final String ACTIVE_TENANT_PROPERTY = "summary.active-tenant";
    public static final String ACTIVE_TENANT_ENV = "SUMMARY_ACTIVE_TENANT";

    /**
     * The tenant and profile this START was told to serve — {@code -Dsummary.active-tenant=btcl/lab}, or the
     * unit's {@code SUMMARY_ACTIVE_TENANT=btcl/lab} — when it was told. One jar holds every tenant's profile and
     * no profile key is fixed at build time any more, so the same jar serves a MySQL tenant or a PostgreSQL one;
     * this is how a deployment (and a test) says which, without rebuilding with another {@code tenants.yml}.
     * A value that is not {@code <tenant>/<profile>} is a mistake that must be seen: it is refused.
     */
    public static Optional<ActiveTenant> selectedTenant() {
        String chosen = System.getProperty(ACTIVE_TENANT_PROPERTY);
        if (chosen == null || chosen.isBlank()) {
            chosen = System.getenv(ACTIVE_TENANT_ENV);
        }
        return chosen == null || chosen.isBlank() ? Optional.empty() : Optional.of(parseSelection(chosen));
    }

    static ActiveTenant parseSelection(String chosen) {
        String[] parts = chosen.trim().split("/");
        if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9_-]+") || !parts[1].matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("the active tenant is '" + chosen + "' — it must be <tenant>/<profile>, e.g. btcl/lab");
        }
        return new ActiveTenant(parts[0], parts[1]);
    }

    /** The first tenant flagged enabled in the registry file, if any. */
    public static Optional<ActiveTenant> activeTenant(String tenantsResource) {
        Map<String, Object> root = loadYaml(tenantsResource);
        if (root == null || !(root.get("tenants") instanceof List<?> tenants)) {
            return Optional.empty();
        }
        for (Object entry : tenants) {
            if (entry instanceof Map<?, ?> tenant && asBoolean(tenant.get("enabled"))) {
                return Optional.of(new ActiveTenant(String.valueOf(tenant.get("name")), String.valueOf(tenant.get("profile"))));
            }
        }
        return Optional.empty();
    }

    /** The active tenant's profile yml, flattened to dot-notation config properties. */
    public static Map<String, String> loadProfile(ActiveTenant tenant) {
        String path = "config/tenants/" + tenant.name() + "/" + tenant.profile() + "/profile-" + tenant.profile() + ".yml";
        Map<String, Object> root = loadYaml(path);
        if (root == null) {
            // the logging is not up yet when the ConfigSource is made: stderr is the only channel
            System.err.println("summary-service: the active tenant " + tenant.name() + "/" + tenant.profile() + " has no profile file "
                    + path + " — the service starts with NO tenant configuration");
            return Map.of();
        }
        Map<String, String> flat = new LinkedHashMap<>();
        flatten("", root, flat);
        return flat;
    }

    private static Map<String, Object> loadYaml(String resource) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = ProfileYamlLoader.class.getClassLoader();
        }
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map<?, ?> map)) {
                return null;   // scalar/list root -> treated as no config, not a boot crash
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        } catch (IOException | RuntimeException e) {
            // malformed yml (stray tab, bad indent) must NOT hard-fail boot inside the ConfigSource
            // ServiceLoader; Quarkus logging is not up yet at this point, so stderr is the only channel
            System.err.println("summary-service: could not parse " + resource + " — ignoring it: " + e);
            return null;
        }
    }

    private static void flatten(String prefix, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((k, v) -> flatten(prefix.isEmpty() ? String.valueOf(k) : prefix + "." + k, v, out));
        } else if (node instanceof List<?> list) {
            if (isScalarList(list)) {
                // a list of scalars (e.g. enabledSummary) -> comma-joined, so MicroProfile getValues reads it
                out.put(prefix, list.stream().map(String::valueOf).collect(Collectors.joining(",")));
            } else {
                for (int i = 0; i < list.size(); i++) {
                    flatten(prefix + "[" + i + "]", list.get(i), out);
                }
            }
        } else if (node != null) {
            out.put(prefix, String.valueOf(node));
        }
    }

    private static boolean isScalarList(List<?> list) {
        return list.stream().noneMatch(e -> e instanceof Map || e instanceof List);
    }

    private static boolean asBoolean(Object value) {
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }
}
