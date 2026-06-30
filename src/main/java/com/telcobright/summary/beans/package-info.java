/**
 * The <b>public API</b> of the summary library — the high-level, fluent entry points a user assembles a
 * summary bean with. One builder per bean: {@link com.telcobright.summary.beans.DailySummaryBuilder},
 * {@link com.telcobright.summary.beans.HourlySummaryBuilder}, each
 * {@code create(mapper).table(..).serviceGroup(..).context(..).build()}.
 *
 * <p><b>Convention (enforced):</b> every summary bean — present and future — ships a builder here that extends
 * {@link com.telcobright.summary.beans.SummaryBeanBuilder} (the base contract). That base supplies the shared
 * fluent chain and a {@code final build()} that validates the common invariants, so all beans are configured the
 * same brief way and none can skip the checks.
 *
 * <p>How this differs from its neighbours:
 * <ul>
 *   <li>{@code beans} (here) — the public builder/factory API (what embedders import);</li>
 *   <li>{@code bean.spi} — the contracts a bean implements ({@code SummaryBean}, {@code SummaryEntity},
 *       {@code WindowSize});</li>
 *   <li>{@code summarybeans.<category>} — the bean implementations + their nested {@code internal}/{@code model}.</li>
 * </ul>
 *
 * <p>The running Quarkus service still wires beans via CDI + {@code summary.beans.<name>} YAML; these builders
 * are the programmatic path for embedders and tests (they coexist — see {@code docs/decisions.md}).
 */
package com.telcobright.summary.beans;
