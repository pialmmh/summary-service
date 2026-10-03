package com.telcobright.summary.runtime.internal;

import com.telcobright.summary.bean.spi.SqlDialect;
import org.eclipse.microprofile.config.Config;

import java.util.Optional;

/**
 * The store of the active profile — where the summaries, the bookmarks and the outbox live:
 *
 * <pre>
 * summary:
 *   store:
 *     kind: postgresql                    # mysql | postgresql — may be left out: the URL names the engine
 *     url: jdbc:postgresql://127.0.0.1:7643/routesphere
 *     username: summary_service
 *     password: ""                        # the inline form
 *     min-size: 0                         # connections kept open
 *     max-size: 16
 *     acquisition-timeout-seconds: 30     # how long a worker waits for a connection before its drain fails
 * </pre>
 *
 * The engine is chosen HERE, at run time: one jar serves either. A {@code kind} that contradicts the URL is
 * refused in words (the profile must not say one engine and reach another).
 */
record StoreConfig(SqlDialect dialect, String url, String username, String password, int minSize, int maxSize,
                   int acquisitionTimeoutSeconds) {

    static final String PREFIX = "summary.store.";

    static StoreConfig from(Config config) {
        String url = text(config, "url").orElseThrow(() -> new IllegalStateException(
                "summary.store.url is not set — the active profile names no store (summary.store.kind, .url, .username)"));
        SqlDialect dialect = dialectOf(text(config, "kind").orElse(null), url);
        return new StoreConfig(dialect, url,
                text(config, "username").orElse(""),
                text(config, "password").orElse(""),
                config.getOptionalValue(PREFIX + "min-size", Integer.class).orElse(0),
                config.getOptionalValue(PREFIX + "max-size", Integer.class).orElse(16),
                config.getOptionalValue(PREFIX + "acquisition-timeout-seconds", Integer.class).orElse(30));
    }

    /** The engine the profile names; it must be the one the URL reaches. */
    static SqlDialect dialectOf(String kind, String url) {
        SqlDialect byUrl = SqlDialect.ofUrl(url);
        if (kind == null || kind.isBlank()) {
            if (byUrl == null) {
                throw new IllegalStateException("summary.store.url '" + url + "' is neither a jdbc:mysql: nor a jdbc:postgresql: URL "
                        + "— set summary.store.kind to mysql or postgresql");
            }
            return byUrl;
        }
        SqlDialect named = SqlDialect.ofKind(kind);
        if (byUrl != null && byUrl != named) {
            throw new IllegalStateException("REFUSING TO START the store: summary.store.kind is " + named.kind()
                    + " but summary.store.url is a " + byUrl.kind() + " URL (" + url + ")");
        }
        return named;
    }

    /** The JDBC driver of the engine, by name (the pool asks the driver itself, not the DriverManager). */
    String driverClassName() {
        return dialect == SqlDialect.POSTGRESQL ? "org.postgresql.Driver" : "com.mysql.cj.jdbc.Driver";
    }

    private static Optional<String> text(Config config, String key) {
        return config.getOptionalValue(PREFIX + key, String.class).map(String::trim).filter(v -> !v.isEmpty());
    }
}
