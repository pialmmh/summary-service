package com.telcobright.summary.runtime.internal;

import com.telcobright.summary.bean.spi.SqlDialect;
import com.telcobright.summary.engine.spi.SummaryStoreException;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.AgroalConnectionPoolConfiguration.ConnectionValidator;
import io.agroal.api.configuration.AgroalConnectionPoolConfiguration.ExceptionSorter;
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
 *
 * <p>A database that goes away and comes back (a restart, a failover) ends every connection the pool holds. The
 * pool asks a connection whether it is there before it hands it out, and throws one away at its first fatal error
 * ({@link #fatalErrorsOf}) — so the first drain after the database answers again writes, with no restart (brief S16).
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
     * AT A START, before anything is dialled: is the store's CONFIGURATION one the service can start with? A fault
     * of the profile refuses the start here, in words — an engine that contradicts the URL, a password named by an
     * environment variable that is not set, a rule of the secret broken ({@link StoreSecret}), or workers that are
     * to start with no store at all. (A store that does not ANSWER is another matter: that is tried again.)
     * Returns one line that says where the password comes from, WITHOUT its value.
     *
     * @param workersWillStart {@code summary.autostart}: with it off and no store configured the app still boots
     */
    public String checkAtStart(boolean workersWillStart) {
        Config active = configuration.get();
        boolean storeNamed = active.getOptionalValue(StoreConfig.PREFIX + "url", String.class).filter(url -> !url.isBlank()).isPresent();
        if (!storeNamed) {
            if (workersWillStart) {
                throw new IllegalStateException("REFUSING TO START: summary.autostart is on and the active profile names no store "
                        + "(summary.store.url is not set) — did the start name its tenant? SUMMARY_ACTIVE_TENANT=<tenant>/<profile>");
            }
            return "the store: none is configured (nothing is to be served: summary.autostart is off)";
        }
        try {
            StoreConfig.from(active, environment);              // the engine against the URL; the secret's rules; the named variable
        } catch (IllegalArgumentException | IllegalStateException fault) {
            throw refusal(fault);
        }
        return StoreSecret.sourceOf(active).said();
    }

    /** Every fault of the store's configuration is said the same way: ONE refusal, in words (print-only reads it too). */
    private static IllegalStateException refusal(RuntimeException fault) {
        String words = String.valueOf(fault.getMessage());
        if (fault instanceof IllegalStateException already && words.startsWith("REFUSING TO START")) {
            return already;
        }
        return new IllegalStateException("REFUSING TO START: " + words, fault);
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

    /** How long the pool waits for a connection to answer "are you there?" before it is taken for dead. */
    static final int VALIDATION_SECONDS = 5;

    /**
     * The errors after which a connection is thrown away, not given back to the pool: the connection classes
     * ({@code 08…}: the link failed, the connection is closed) and, on PostgreSQL, the server's own ending of the
     * session ({@code 57P01} admin shutdown, {@code 57P02} crash shutdown, {@code 57P03} cannot connect now). Without
     * it a pool hands out the same dead connections for ever, and every worker fails until a restart — what the bed
     * met after its database was restarted (X-0020, X-2).
     */
    static ExceptionSorter fatalErrorsOf(SqlDialect engine) {
        return failure -> {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                    String state = sql.getSQLState();
                    if (state.startsWith("08") || (engine == SqlDialect.POSTGRESQL && state.startsWith("57P"))) {
                        return true;
                    }
                }
            }
            return false;
        };
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
                        // a connection the SERVER ended (a restart, a failover, an idle kill) is never handed out again
                        // (brief S16): asked before every hand-out, and thrown away at its first fatal error
                        .connectionValidator(ConnectionValidator.defaultValidatorWithTimeout(VALIDATION_SECONDS))
                        .validateOnBorrow(true)
                        .exceptionSorter(fatalErrorsOf(store.dialect()))
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
