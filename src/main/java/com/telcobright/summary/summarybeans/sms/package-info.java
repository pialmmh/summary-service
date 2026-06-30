/**
 * The <b>sms</b> summary category — reserved for future SMS counter / summary beans. No beans yet.
 *
 * <p>When populated it mirrors {@link com.telcobright.summary.summarybeans.call}: a high-level bean class per
 * time window (e.g. {@code DailySmsSummary}, {@code HourlySmsSummary}) lives directly in this package, with the
 * shared machinery nested in {@code sms.internal} and the entity / inbound blob shapes in {@code sms.model}.
 * Each window-bean is activated by listing its name in {@code summary.enabledSummary}.
 */
package com.telcobright.summary.summarybeans.sms;
