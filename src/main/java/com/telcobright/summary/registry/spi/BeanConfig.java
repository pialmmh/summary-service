package com.telcobright.summary.registry.spi;

import com.telcobright.summary.bean.spi.WindowSize;

/**
 * One enabled summary's configuration, read from {@code summary.beans.<name>} in the active profile yml. A
 * {@link SummaryBeanFactory} for the named {@code entity} turns this into a live bean instance — so daily vs
 * hourly (vs 5-minute, weekly, …) are just different configs of the SAME entity bean class, differing by
 * {@code window} + {@code table}.
 *
 * @param name         the enabledSummary entry id (also the bean/worker name + offset bookmark)
 * @param entity       the outbox {@code entity_type} this bean consumes (e.g. "cdr")
 * @param window       the time window (5min / hourly / daily / weekly / …)
 * @param table        target MySQL table
 * @param serviceGroup optional record filter (e.g. CDR service group 10/11); null = no filter
 * @param context      the shared read-only context this bean needs (e.g. "mediationContext"), or null
 */
public record BeanConfig(String name, String entity, WindowSize window, String table, Integer serviceGroup,
                         String context) {
}
