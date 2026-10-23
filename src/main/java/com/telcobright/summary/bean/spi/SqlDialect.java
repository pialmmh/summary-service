package com.telcobright.summary.bean.spi;

import java.util.Locale;

/**
 * The database engine a summary store runs on, chosen per profile ({@code summary.store.kind}). The engine, the
 * cache and the entities are the same on both; what differs is the EDGE, and every difference is keyed on this:
 * a table's DDL ({@link TableDdl}), the bookmark's upsert and head-init, how a tenant's schema is entered, and
 * how the session reads a string literal.
 */
public enum SqlDialect {

    /** A tenant is a DATABASE; summary tables are RANGE-partitioned by day, the full set inside the CREATE. */
    MYSQL,

    /** A tenant is a SCHEMA of one switch database (ad-is-a-call §3); summary tables are plain. */
    POSTGRESQL;

    /** The profile's word for the engine: {@code mysql} or {@code postgresql}. Anything else is refused in words. */
    public static SqlDialect ofKind(String kind) {
        String word = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        return switch (word) {
            case "mysql" -> MYSQL;
            case "postgresql" -> POSTGRESQL;
            default -> throw new IllegalArgumentException("summary.store.kind is '" + kind + "' — it must be mysql or postgresql");
        };
    }

    /** The engine a JDBC URL names ({@code jdbc:mysql:…}, {@code jdbc:postgresql:…}); null when it names neither. */
    public static SqlDialect ofUrl(String jdbcUrl) {
        String url = jdbcUrl == null ? "" : jdbcUrl.trim().toLowerCase(Locale.ROOT);
        if (url.startsWith("jdbc:mysql:")) {
            return MYSQL;
        }
        return url.startsWith("jdbc:postgresql:") ? POSTGRESQL : null;
    }

    /** The word the profile uses for this engine. */
    public String kind() {
        return name().toLowerCase(Locale.ROOT);
    }
}
