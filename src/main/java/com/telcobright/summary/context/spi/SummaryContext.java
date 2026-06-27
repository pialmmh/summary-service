package com.telcobright.summary.context.spi;

/**
 * A shared, read-only context a bean may need — loaded ONCE and shared across the beans that reference it.
 * For CDR it is the {@code MediationContext} fetched from config-manager (the same data billing loads), so
 * summaries can build identically. A future entity declares its own context type on this marker.
 */
public interface SummaryContext {

    String name();

    /** True if the context actually loaded (config-manager reachable). */
    boolean loaded();
}
