package com.telcobright.summary.config.internal;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.Map;
import java.util.Set;

/**
 * Feeds the active tenant's flattened profile yml into Quarkus config (the routesphere pattern). Ordinal 275
 * so it overrides application.properties for the keys it provides (summary.*). The active tenant is the one the
 * start names ({@code summary.active-tenant} / {@code SUMMARY_ACTIVE_TENANT} = {@code <tenant>/<profile>}), else
 * the first enabled entry of {@code config/tenants.yml}. Registered via ServiceLoader (META-INF/services).
 *
 * <p><b>It answers by name and LISTS NOTHING</b> ({@link #getPropertyNames()} is empty — the MicroProfile contract
 * allows a source to list a subset). The reason is a leak between tenants: at BUILD time Quarkus records every
 * property a config source lists as a run-time default INSIDE the jar. The build sees the registry's tenant; a
 * start that names another tenant would then run with the build tenant's value for every key its own profile does
 * not set — that tenant's config-manager, its Kafka broker. A source that lists nothing is not recorded, so a jar
 * carries no tenant's values but in its profile files, and a key a profile does not set is simply not set. The
 * service reads every key by its name; nothing iterates them.
 */
public class TenantProfileConfigSource implements ConfigSource {

    private static final String TENANTS_FILE = "config/tenants.yml";
    private static final int ORDINAL = 275;
    private static final String SERVICE_KEYS = "summary.";

    private final Map<String, String> properties;

    public TenantProfileConfigSource() {
        // the tenant this start was told to serve (summary.active-tenant / SUMMARY_ACTIVE_TENANT), else the
        // registry file's first enabled entry
        this.properties = ProfileYamlLoader.selectedTenant()
                .or(() -> ProfileYamlLoader.activeTenant(TENANTS_FILE))
                .map(ProfileYamlLoader::loadProfile)
                .orElseGet(Map::of);
    }

    /** Over a given profile (a test's). */
    TenantProfileConfigSource(Map<String, String> profile) {
        this.properties = Map.copyOf(profile);
    }

    /** Nothing is listed — see the class comment: a listed key would be baked into the jar as every tenant's default. */
    @Override
    public Map<String, String> getProperties() {
        return Map.of();
    }

    /** Nothing is listed — see the class comment. */
    @Override
    public Set<String> getPropertyNames() {
        return Set.of();
    }

    /**
     * A profile is the SERVICE's configuration: only {@code summary.*} is answered. Quarkus asks a source for its
     * own keys by name while it builds, and records what it gets as the jar's run-time default — a {@code quarkus.*}
     * key in the build tenant's profile would become every tenant's. Quarkus's keys (the listener's address, the
     * log) belong to application.properties or to the unit that starts the service.
     */
    @Override
    public String getValue(String propertyName) {
        return propertyName != null && propertyName.startsWith(SERVICE_KEYS) ? properties.get(propertyName) : null;
    }

    /** The active profile's keys and values, for a test or a diagnosis — never for the configuration system. */
    public Map<String, String> profile() {
        return properties;
    }

    @Override
    public String getName() {
        return "summary-tenant-profile";
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }
}
