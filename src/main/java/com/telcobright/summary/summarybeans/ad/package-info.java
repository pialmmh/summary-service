/**
 * The <b>ad</b> summary category (design AD-AS-CALL §2.9, 2026-09-29; net-new): rolls up EVERY tier of every ad
 * call ({@code entity_type = 'ad_cdr'}, the same v2 envelope as voice — {@code {Cdr, Chargeables:[tier…]}}) into
 * {@code sum_ad_day_30} / {@code sum_ad_hr_30}, keyed on (tenant, partner, campaign, rule code, zone, site, app,
 * media kind, outcome, bucket): one row per TIER, so a reseller's summary is the rows where {@code tup_tenant}
 * = its database, and an advertiser's the rows where {@code tup_partnerid} is its id.
 *
 * <p>Same shape as every category: window singletons here ({@link com.telcobright.summary.summarybeans.ad.DailyAdSummary},
 * {@link com.telcobright.summary.summarybeans.ad.HourlyAdSummary}), machinery in {@code internal/}, the entity and
 * the blob shapes in {@code model/}. The decoder is the call category's {@code CdrBlobMapper} (case-insensitive,
 * lenient); the shapes inside the envelope are the ad's own.
 */
package com.telcobright.summary.summarybeans.ad;
