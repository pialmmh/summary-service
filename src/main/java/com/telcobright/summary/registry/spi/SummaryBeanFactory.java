package com.telcobright.summary.registry.spi;

import com.telcobright.summary.bean.spi.SummaryBean;

/**
 * Builds a configured {@link SummaryBean} instance for ONE entity kind. There is one factory per entity
 * (e.g. CDR); each {@code enabledSummary} config naming that entity produces a distinct bean instance (its own
 * window/table/topic). A future entity (call-quality) ships a new factory + entity, no engine change.
 */
public interface SummaryBeanFactory {

    /** The entity id this factory builds, matched against {@link BeanConfig#entity()} (e.g. "cdr"). */
    String entity();

    SummaryBean<?> create(BeanConfig config);
}
