/**
 * The <b>chargeable</b> summary category (net-new, work order 2026-07-02 §4 — no legacy counterpart): rolls up
 * EVERY {@code acc_chargeable} leg of every cdr blob entry into {@code sum_chargeable_day} / {@code
 * sum_chargeable_hr}, keyed on (servicegroup, servicefamily, assignedDirection, product, currency, prefix,
 * bucket) so customer revenue and supplier cost stay separate rows.
 *
 * <p>It consumes the SAME {@code cdr} outbox stream as {@code summarybeans.call} and reuses that category's
 * blob model ({@code CdrBlobEntry}/{@code Chargeable}) and mapper — one pinned blob contract, one decoder.
 * Same shape as every category: window singletons here, machinery in {@code internal/}, the entity in
 * {@code model/}.
 */
package com.telcobright.summary.summarybeans.chargeable;
