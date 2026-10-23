/**
 * The <b>call</b> summary category. This package holds only the HIGH-LEVEL beans — one per time window
 * ({@link com.telcobright.summary.summarybeans.call.DailySummary},
 * {@link com.telcobright.summary.summarybeans.call.HourlySummary}): each is a small CDI {@code @Singleton}
 * carrying its constructor / startup wiring and fixing its window; the engine drives their
 * build &rarr; merge &rarr; write loop.
 *
 * <p>The internal complexity is nested out of the way:
 * <ul>
 *   <li>{@code call.internal} — the shared {@code CallSummaryBean} machinery (blob decode, customer-leg pick,
 *       service-group filter, per-record build), the {@code CallSummaryBuilder}, the blob {@code CdrBlobMapper},
 *       and the self-provisioning {@code SumVoiceDdl};</li>
 *   <li>{@code call.model} — the {@code CallSummary} row entity and the inbound blob shapes ({@code Cdr} /
 *       {@code Chargeable} / {@code CdrBlobEntry}, blob v2 with permanent v1 tolerance) — ALSO consumed by the
 *       {@code call.chargeable} roll-up and the ad and WiFi kinds (one pinned blob contract, one decoder);</li>
 *   <li>{@code call.chargeable} — the chargeable roll-up ({@code sum_chargeable_*}), the call kind's second bean set
 *       (moved here from {@code summarybeans/chargeable} by SS-0008).</li>
 * </ul>
 *
 * <p>SS-0008: this package is the CALL kind ({@link com.telcobright.summary.summarybeans.call.CallKind}) — the model the
 * ad and WiFi kinds follow; a call-shaped bean of group 30 or 101 is the ad's or the WiFi's view, the same code.
 *
 * <p>Extra INSTANCES of a window (e.g. a second service group) are config-instantiated via
 * {@link com.telcobright.summary.summarybeans.call.CallSummaries} (§12g). Add a window = add one tiny bean
 * class here; add a category = add a sibling package under {@code summarybeans} with the same shape.
 */
package com.telcobright.summary.summarybeans.call;
