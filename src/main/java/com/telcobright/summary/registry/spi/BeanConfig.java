package com.telcobright.summary.registry.spi;

import com.telcobright.summary.bean.spi.WindowSize;

import java.time.ZoneId;

/**
 * One enabled summary's configuration, read from {@code summary.beans.<name>} in the active profile yml. A
 * {@link SummaryBeanFactory} for the named {@code entity} turns this into a live bean instance — so daily vs
 * hourly (vs 5-minute, weekly, …) are just different configs of the SAME entity bean class, differing by
 * {@code window} + {@code table}.
 *
 * @param name            the enabledSummary entry id (also the bean/worker name + config section)
 * @param entity          which entity bean factory builds it (e.g. "cdr")
 * @param topic           Kafka topic for the increment stream
 * @param correctionTopic correction topic, or null
 * @param table           target MySQL table
 * @param window          the time window (5min / hourly / daily / weekly / …)
 * @param serviceGroup    optional event filter (e.g. CDR service group 10/11); null = no filter
 * @param batchSize       events per DB transaction
 * @param zone            wall-clock zone the window is bucketed in
 */
public record BeanConfig(String name, String entity, String topic, String correctionTopic, String table,
                         WindowSize window, Integer serviceGroup, int batchSize, ZoneId zone) {
}
