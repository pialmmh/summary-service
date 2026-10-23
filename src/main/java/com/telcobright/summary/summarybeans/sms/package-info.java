/**
 * The <b>sms</b> summary category — reserved for future SMS counter / summary beans. No beans yet.
 *
 * <p>When populated it mirrors {@link com.telcobright.summary.summarybeans.call}: a high-level bean class per
 * time window (e.g. {@code DailySmsSummary}, {@code HourlySmsSummary}) lives directly in this package, with the
 * shared machinery nested in {@code sms.internal} and the entity / inbound blob shapes in {@code sms.model}.
 * Each window-bean is activated by listing its name in {@code summary.enabledSummary}.
 *
 * <p>SS-0008: it will be the SMS KIND — {@code SmsKind implements bean.spi.SummaryKind} in this package (its word
 * {@code sms}, its service group(s), its entities, its bean set), one line in {@code registry/internal/KnownKinds}, and
 * {@code sms} in the {@code summary.kinds} of a tenant that sums it. The steps: {@code docs/ad-as-call/SS-0008-done.md} §8.
 */
package com.telcobright.summary.summarybeans.sms;
