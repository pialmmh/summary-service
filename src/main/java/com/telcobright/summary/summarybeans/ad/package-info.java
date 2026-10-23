/**
 * The <b>ad</b> summary category (ad-is-a-call §4.1, §5): an ad view is a Call, so its record is a {@code cdr} row
 * of service group 30 that billing-core writes in each tier's own schema, and this category reads the SAME outbox
 * stream as the call and chargeable categories ({@code entity_type = 'cdr'}, blob v2 {@code {Cdr, Chargeables}}),
 * keeping the entries of group 30. It rolls them into {@code sum_ad_day_30} / {@code sum_ad_hr_30} — one pair per
 * tier schema — keyed on (tenant, partner, campaign, rule code, zone, site, app, media kind, outcome, bucket). A
 * reseller's summary is the pair in its own schema; {@code tup_tenant} is always that schema's name.
 *
 * <p>Same shape as every category: window singletons here ({@link com.telcobright.summary.summarybeans.ad.DailyAdSummary},
 * {@link com.telcobright.summary.summarybeans.ad.HourlyAdSummary}), machinery in {@code internal/}, the entity and
 * the ad's view of the blob in {@code model/}. The decoder is the call category's {@code CdrBlobMapper}
 * (case-insensitive, lenient) and the chargeable leg is the call category's {@code Chargeable}: one pinned blob
 * contract, read three ways.
 *
 * <p>SS-0008: this package is the AD kind ({@link com.telcobright.summary.summarybeans.ad.AdKind}): service group 30, its
 * two window classes, and the view as a call ({@code sum_voice_*_30}, a call-shaped bean of group 30).
 */
package com.telcobright.summary.summarybeans.ad;
