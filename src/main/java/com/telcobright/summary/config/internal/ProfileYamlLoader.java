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
            return new Yaml().load(in);
        } catch (IOException e) {
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
