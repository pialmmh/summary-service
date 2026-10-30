package com.telcobright.summary.registry.internal;

import org.eclipse.microprofile.config.Config;

import java.util.Locale;
import java.util.UUID;

/**
 * The service CATEGORY this summary-service is deployed for (ARCH-0102 / SS-0009): {@code summary.category}, one lower-case word
 * ({@code ad}, {@code wifi}; later {@code call}, {@code sms}). The owner's ruling: one billing pair per category, each on its
 * category's own records database — so on one set of brokers two summary-services listen, and each one's consumer groups carry its
 * category's name: {@code summary-service-<category>-ping-<uuid>}, {@code summary-service-<category>-doorbell-<uuid>}. The groups
 * stay unique per start (a ping and a ring are broadcasts: every instance hears every one). No category named = the names of
 * before ({@code summary-ping-<uuid>}, {@code summary-doorbell-<uuid>}): no deployment changes on the day.
 */
public final class ServiceCategory {

    public static final String KEY = "summary.category";

    private ServiceCategory() {
    }

    /** The profile's word, trimmed and lower-cased; {@code ""} when none is named. */
    public static String of(Config config) {
        return normalized(config.getOptionalValue(KEY, String.class).orElse(""));
    }

    /** The consumer group of a listener with the given role ({@code ping}, {@code doorbell}) — unique per call. */
    public static String consumerGroup(String category, String role) {
        String word = normalized(category);
        String prefix = word.isEmpty() ? "summary-" + role : "summary-service-" + word + "-" + role;
        return prefix + "-" + UUID.randomUUID();
    }

    private static String normalized(String category) {
        return category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
    }
}
