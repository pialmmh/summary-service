package com.telcobright.summary.runtime.internal;

import com.telcobright.summary.bean.spi.SqlDialect;
import io.agroal.api.AgroalDataSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;

import java.util.Map;

/**
 * The service's OWN pool for a test: made by the same code as the running service's ({@link StoreDataSource}), so
 * a test of the tree runs every schema over ONE pool of real, reused connections — where a connection keeps the
 * schema the last unit of work entered.
 */
public final class TestPools {

    private TestPools() {
    }

    public static AgroalDataSource pool(SqlDialect dialect, String url, String user, String password, int maxSize) {
        return StoreDataSource.open(new StoreConfig(dialect, url, user, password, 0, maxSize, 30));
    }

    /** How long a pooled connection may sit idle before it is asked whether it is there (S16). */
    public static java.time.Duration checkAfterIdle() {
        return StoreDataSource.CHECK_AFTER_IDLE;
    }

    /**
     * The service's store over a given profile and a given ENVIRONMENT (name → value) — for the rule of where the
     * password comes from. Nothing is dialled until a connection is asked for.
     */
    public static StoreDataSource store(Map<String, String> profile, Map<String, String> environment) {
        Config config = new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(profile, "a test's profile", 300)).build();
        return new StoreDataSource(() -> config, environment::get);
    }
}
