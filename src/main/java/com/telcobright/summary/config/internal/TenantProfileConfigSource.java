package com.telcobright.summary.config.internal;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.Map;
import java.util.Set;

/**
 * Feeds the active tenant's flattened profile yml into Quarkus config (the routesphere pattern). Ordinal 275
 * so it overrides application.properties for the keys it provides (datasource, summary.*), while
 * application.properties keeps owning the active-tenant selection. Registered via ServiceLoader
 * (META-INF/services). The DB credentials are inline in the profile yml (no OpenBao), matching billing-core.
 */
public class TenantProfileConfigSource implements ConfigSource {

    private static final String TENANTS_FILE = "config/tenants.yml";
    private static final int ORDINAL = 275;

    private final Map<String, String> properties;

    public TenantProfileConfigSource() {
        this.properties = ProfileYamlLoader.activeTenant(TENANTS_FILE)
                .map(ProfileYamlLoader::loadProfile)
                .orElseGet(Map::of);
    }

    @Override
    public Map<String, String> getProperties() {
        return properties;
    }

    @Override
    public Set<String> getPropertyNames() {
        return properties.keySet();
    }

    @Override
    public String getValue(String propertyName) {
        return properties.get(propertyName);
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
