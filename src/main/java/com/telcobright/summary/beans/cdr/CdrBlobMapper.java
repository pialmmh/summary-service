package com.telcobright.summary.beans.cdr;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The ObjectMapper for decoding the outbox blob: CASE-INSENSITIVE properties (so camelCase Java fields match
 * billing's C# PascalCase), JavaTime for {@code StartTime}/{@code ConnectTime}, and lenient on unknown fields.
 */
public final class CdrBlobMapper {

    private CdrBlobMapper() {
    }

    /** A standalone mapper (tests / non-CDI). */
    public static ObjectMapper create() {
        return tune(new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    /** A configured COPY of an injected mapper (keeps the shared CDI mapper untouched). */
    public static ObjectMapper from(ObjectMapper base) {
        return tune(base.copy().registerModule(new JavaTimeModule()));
    }

    private static ObjectMapper tune(ObjectMapper mapper) {
        return mapper
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
