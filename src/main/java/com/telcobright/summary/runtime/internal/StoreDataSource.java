package com.telcobright.summary.runtime.internal;

import com.telcobright.summary.bean.spi.SqlDialect;
import com.telcobright.summary.engine.spi.SummaryStoreException;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import io.agroal.api.security.NamePrincipal;
import io.agroal.api.security.SimplePassword;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The store's connection pool, built by the service itself from the active profile ({@link StoreConfig}) at the
 * FIRST use — so the app boots with no database reachable, and the engine (MySQL or PostgreSQL) is the profile's
 * choice at run time, not the build's. One pool for the whole process: on PostgreSQL every tenant is a schema of
 * the one switch database, on MySQL a database of the one server, entered per unit of work.
 */
@ApplicationScoped
public class StoreDataSource {

    private static final Logger LOG = Logger.getLogger(StoreDataSource.class);

    private final Supplier<Config> configuration;
    private final Function<String, String> environment;
    private volatile StoreConfig config;
    private volatile AgroalDataSource pool;

    /** The service's wiring: the active configuration, the process's environment. */
    public StoreDataSource() {
        this(ConfigProvider::getConfig, System::getenv);
    }

    /** Over a given configuration and environment (a test's). */
    StoreDataSource(Supplier<Config> configuration, Function<String, String> environment) {
        this.configuration = configuration;
        this.environment = environment;
    }

    /**
     * AT A START, before anything is dialled: where the store's password comes from, said in one line WITHOUT its
     * value. A profile that names an environment variable ({@code password-ref: env:NAME}) which is not set — or one
     * that breaks the secret's rules ({@link StoreSecret}) — refuses the start here, in words. A profile with no
     * store at all is no fault (the app still boots with nothing configured).
     */
    public String secretAtStart() {
        Config active = configuration.get();
        StoreSecret.refuseAPasswordInTheUrl(active.getOptionalValue(StoreConfig.PREFIX + "url", String.class).orElse(null));
        StoreSecret.Source source = StoreSecret.sourceOf(active);
        StoreSecret.passwordOf(active, environment);            // a named variable that is not set refuses the start
        return source.said();
    }

    /** The engine the active profile names. Reads the profile; opens nothing. */
    public SqlDialect dialect() {
        return config().dialect();
    }

    /** The pool, made on the first call. */
    public DataSource get() {
        AgroalDataSource ready = pool;
        if (ready != null) {
            return ready;
        }
        synchronized (this) {
            if (pool == null) {
                pool = open(config());
            }
            return pool;
        }
    }

    private StoreConfig config() {
        StoreConfig known = config;
        if (known != null) {
            return known;
        }
        synchronized (this) {
            if (config == null) {
                config = StoreConfig.from(configuration.get(), environment);
            }
            return config;
        }
    }

    /** The pool of a store (also what a test asks for directly). */
    static AgroalDataSource open(StoreConfig store) {
        AgroalDataSourceConfigurationSupplier configuration = new AgroalDataSourceConfigurationSupplier()
                .metricsEnabled(false)
                .connectionPoolConfiguration(pool -> pool
                        .initialSize(0)                                         // nothing is dialled until a worker needs it
                        .minSize(store.minSize())
                        .maxSize(store.maxSize())
                        .acquisitionTimeout(Duration.ofSeconds(store.acquisitionTimeoutSeconds()))
                        .connectionFactoryConfiguration(factory -> {
                            factory.jdbcUrl(store.url()).connectionProviderClassName(store.driverClassName()).autoCommit(true);
                            if (!store.username().isEmpty()) {
                                factory.principal(new NamePrincipal(store.username())).credential(new SimplePassword(store.password()));
                            }
                            return factory;
                        }));
        try {
            AgroalDataSource opened = AgroalDataSource.from(configuration);
            LOG.infof("store: %s at %s as %s (pool %d..%d, a worker waits %ds for a connection)", store.dialect().kind(),
                    store.url(), store.username().isEmpty() ? "(no user)" : store.username(), store.minSize(), store.maxSize(),
                    store.acquisitionTimeoutSeconds());
            return opened;
        } catch (SQLException e) {
            throw new SummaryStoreException("the store's pool could not be made for " + store.url(), e);
        }
    }

    @PreDestroy
    void close() {
        AgroalDataSource open = pool;
        if (open != null) {
            open.close();
        }
    }
}
