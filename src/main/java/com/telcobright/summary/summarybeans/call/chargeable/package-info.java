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
 *
 * <p>SS-0008: a part of the CALL kind ({@link com.telcobright.summary.summarybeans.call.CallKind}) — moved here from
 * {@code summarybeans/chargeable}, unchanged. It reads every leg of every record, so it is the one bean whose input crosses
 * the kinds: it sums the legs of the kinds the tenant enabled.
 */
package com.telcobright.summary.summarybeans.call.chargeable;
