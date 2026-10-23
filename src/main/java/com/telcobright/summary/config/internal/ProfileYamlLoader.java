package com.telcobright.summary.config.internal;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The routesphere-like loader: finds the tenant and profile a start serves, then loads + FLATTENS
 * {@code config/tenants/<tenant>/<profile>/profile-<profile>.yml} into dot-notation properties (e.g.
 * {@code summary.store.url}, {@code summary.beans.dailyCallSummary.table-suffix}).
 *
 * <p><b>Where a profile is read from (brief S10).</b> A deployment keeps its configuration OUTSIDE the jar, in one
 * directory — the one that holds {@code config/tenants/…}:
 * <pre>
 *   &lt;the directory&gt;/config/tenants/&lt;tenant&gt;/&lt;profile&gt;/profile-&lt;profile&gt;.yml     the profile
 *   &lt;the directory&gt;/config/tenants.yml                                         (optional) which tenant it serves
 * </pre>
 * The directory is named by ONE key — {@code -Dsummary.config.dir=…}, or the unit's {@code SUMMARY_CONFIG_DIR} —
 * and is the working directory when the key is not set (where Quarkus also reads {@code config/application.properties}).
 * A file found there WINS; else the jar's own is read. So the jar is not rebuilt for a tenant or for a value, a root
 * the jar does not name has its profile, and no box's address has to be committed here. Which file was read is said
 * in the start's first line ({@link Loaded#readFrom()}).
 *
 * <p>Nothing here throws into the configuration system (the logging is not up yet, and a start must be refused in
 * WORDS): what a person wrote wrong — a directory that is none, a tenant with no profile anywhere, a file that cannot
 * be parsed — is a {@link Loaded#fault()}, and the bootstrap refuses the start with it.
 */
public final class ProfileYamlLoader {

    public record ActiveTenant(String name, String profile) {
    }

    /** The tenant and profile a start serves, and WHAT named them. */
    public record Selection(ActiveTenant tenant, String namedBy) {
    }

    /** The directory a deployment keeps its configuration in (it holds {@code config/tenants/…}), and what named it. */
    public record ConfigDir(Path path, String namedBy) {

        /** True when a key named it; false when it is the working directory, by default. */
        boolean named() {
            return namedBy != null;
        }

        /** In words, for the start's first line. */
        String said() {
            return named() ? namedBy + " = " + absolute(path) : "the working directory " + absolute(path);
        }
    }

    /**
     * A start's profile as it was read: its keys; one line that says which tenant, what named it and which FILE the
     * keys came from; and — when the start must be refused — why.
     */
    public record Loaded(Map<String, String> properties, String readFrom, String fault) {

        static Loaded refused(String readFrom, String fault) {
            return new Loaded(Map.of(), readFrom, fault);
        }
    }

    private ProfileYamlLoader() {
    }

    /** Names the tenant and profile a START serves, over the registry file: {@code <tenant>/<profile>}. */
    public static final String ACTIVE_TENANT_PROPERTY = "summary.active-tenant";
    public static final String ACTIVE_TENANT_ENV = "SUMMARY_ACTIVE_TENANT";

    /** Names the directory a deployment keeps its configuration in — the one that HOLDS {@code config/tenants/…}. */
    public static final String CONFIG_DIR_PROPERTY = "summary.config.dir";
    public static final String CONFIG_DIR_ENV = "SUMMARY_CONFIG_DIR";

    static final String TENANTS_FILE = "config/tenants.yml";

    /** The profile of this start: the directory by its key, the tenant by its key or the registry, the file or the jar's. */
    public static Loaded loadActive() {
        return loadActive(configDir(), System.getProperty(ACTIVE_TENANT_PROPERTY), System.getenv(ACTIVE_TENANT_ENV));
    }

    static Loaded loadActive(ConfigDir directory, String tenantByProperty, String tenantByEnvironment) {
        if (directory.named() && !Files.isDirectory(directory.path())) {
            return Loaded.refused("no profile was read", directory.namedBy() + " names " + absolute(directory.path())
                    + ", which is not a directory. It must be the directory that holds config/tenants/<tenant>/<profile>/profile-<profile>.yml");
        }
        Optional<Selection> selection;
        try {
            selection = selection(directory, tenantByProperty, tenantByEnvironment);
        } catch (IllegalArgumentException notTenantSlashProfile) {
            return Loaded.refused("no profile was read", notTenantSlashProfile.getMessage());
        }
        if (selection.isEmpty()) {
            return new Loaded(Map.of(), "no tenant is named (-D" + ACTIVE_TENANT_PROPERTY + ", " + ACTIVE_TENANT_ENV
                    + ") and no entry of the registry file is enabled: NO tenant configuration", null);
        }
        return load(selection.get(), directory);
    }

    /** The directory named by {@code -Dsummary.config.dir}, else by {@code SUMMARY_CONFIG_DIR}, else the working directory. */
    public static ConfigDir configDir() {
        return configDir(System.getProperty(CONFIG_DIR_PROPERTY), System.getenv(CONFIG_DIR_ENV));
    }

    static ConfigDir configDir(String byProperty, String byEnvironment) {
        if (isSet(byProperty)) {
            return new ConfigDir(Path.of(byProperty.trim()), "-D" + CONFIG_DIR_PROPERTY);
        }
        if (isSet(byEnvironment)) {
            return new ConfigDir(Path.of(byEnvironment.trim()), CONFIG_DIR_ENV);
        }
        return new ConfigDir(Path.of(""), null);
    }

    /**
     * The tenant and profile this start serves: the one the START names — {@code -Dsummary.active-tenant=btcl/lab},
     * or the unit's {@code SUMMARY_ACTIVE_TENANT=btcl/lab} — else the first enabled entry of the registry file: the
     * deployment's own ({@code <directory>/config/tenants.yml}) when it is there, else the jar's. A deployment's
     * registry that enables nothing serves nothing — the jar's is not asked behind its back. A value that is not
     * {@code <tenant>/<profile>} is a mistake that must be seen: it is refused.
     */
    static Optional<Selection> selection(ConfigDir directory, String byProperty, String byEnvironment) {
        if (isSet(byProperty)) {
            return Optional.of(new Selection(parseSelection(byProperty), "named by -D" + ACTIVE_TENANT_PROPERTY));
        }
        if (isSet(byEnvironment)) {
            return Optional.of(new Selection(parseSelection(byEnvironment), "named by " + ACTIVE_TENANT_ENV));
        }
        Path registry = directory.path().resolve(TENANTS_FILE);
        if (Files.isRegularFile(registry)) {
            return firstEnabled(parsedFile(registry).root()).map(tenant -> new Selection(tenant, "the first enabled entry of " + absolute(registry)));
        }
        return activeTenant(TENANTS_FILE).map(tenant -> new Selection(tenant, "the first enabled entry of the jar's " + TENANTS_FILE));
    }

    static ActiveTenant parseSelection(String chosen) {
        String[] parts = chosen.trim().split("/");
        if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9_-]+") || !parts[1].matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("the active tenant is '" + chosen + "' — it must be <tenant>/<profile>, e.g. btcl/lab");
        }
        return new ActiveTenant(parts[0], parts[1]);
    }

    /** The first tenant flagged enabled in a registry file of the jar, if any. */
    public static Optional<ActiveTenant> activeTenant(String tenantsResource) {
        return firstEnabled(parsedResource(tenantsResource).root());
    }

    private static Optional<ActiveTenant> firstEnabled(Map<String, Object> registry) {
        if (registry == null || !(registry.get("tenants") instanceof List<?> tenants)) {
            return Optional.empty();
        }
        for (Object entry : tenants) {
            if (entry instanceof Map<?, ?> tenant && asBoolean(tenant.get("enabled"))) {
                return Optional.of(new ActiveTenant(String.valueOf(tenant.get("name")), String.valueOf(tenant.get("profile"))));
            }
        }
        return Optional.empty();
    }

    /** A tenant's profile as the jar holds it (no directory is looked into): its keys, flattened. */
    public static Map<String, String> loadProfile(ActiveTenant tenant) {
        return loadProfile(tenant, null);
    }

    /** A tenant's profile with {@code directory} as the deployment's (null: the jar's own only): its keys, flattened. */
    public static Map<String, String> loadProfile(ActiveTenant tenant, Path directory) {
        return load(new Selection(tenant, "asked for"), directory == null ? null : new ConfigDir(directory, null)).properties();
    }

    /**
     * The profile of {@code selection}: the FILE {@code <directory>/config/tenants/<tenant>/<profile>/profile-<profile>.yml}
     * when it is there — it wins — else the jar's own. Neither there, or one that cannot be parsed: a fault.
     */
    static Loaded load(Selection selection, ConfigDir directory) {
        ActiveTenant tenant = selection.tenant();
        String resource = "config/tenants/" + tenant.name() + "/" + tenant.profile() + "/profile-" + tenant.profile() + ".yml";
        String who = "tenant " + tenant.name() + ", profile " + tenant.profile() + " (" + selection.namedBy() + "): ";
        Path file = directory == null ? null : directory.path().resolve(resource);
        String notInTheDirectory = directory == null ? "" : " (no such file under " + directory.said() + ")";

        boolean aFileIsThere = file != null && Files.isRegularFile(file);
        Parsed parsed = aFileIsThere ? parsedFile(file) : parsedResource(resource);
        String where = aFileIsThere ? "the file " + absolute(file) : "THE JAR'S OWN " + resource + notInTheDirectory;
        if (parsed.missing()) {
            return Loaded.refused(who + "NO profile file", "the start serves " + tenant.name() + "/" + tenant.profile()
                    + " and no profile file is there: looked for " + (file == null ? "" : absolute(file) + ", then for ") + "the jar's " + resource);
        }
        if (parsed.root() == null) {
            return Loaded.refused(who + where + " — NOT READ", "the profile " + where + " could not be read: " + parsed.fault());
        }
        Map<String, String> flat = new LinkedHashMap<>();
        flatten("", parsed.root(), flat);
        return new Loaded(flat, who + where, null);
    }

    /** A YAML document as it was read: its root, or — when there is none — whether it is missing or why it is unreadable. */
    private record Parsed(Map<String, Object> root, boolean missing, String fault) {
    }

    private static Parsed parsedFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return parsed(in);
        } catch (IOException | RuntimeException e) {
            return new Parsed(null, false, String.valueOf(e.getMessage()).lines().findFirst().orElse(e.toString()));
        }
    }

    private static Parsed parsedResource(String resource) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = ProfileYamlLoader.class.getClassLoader();
        }
        try (InputStream in = loader.getResourceAsStream(resource)) {
            return in == null ? new Parsed(null, true, null) : parsed(in);
        } catch (IOException | RuntimeException e) {
            // malformed yml (stray tab, bad indent) must NOT hard-fail inside the ConfigSource ServiceLoader
            return new Parsed(null, false, String.valueOf(e.getMessage()).lines().findFirst().orElse(e.toString()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Parsed parsed(InputStream in) {
        Object loaded = new Yaml().load(in);
        return loaded instanceof Map<?, ?> map ? new Parsed((Map<String, Object>) map, false, null)
                : new Parsed(null, false, "its root is not a map of keys");       // a scalar or a list at the root
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

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static String absolute(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
