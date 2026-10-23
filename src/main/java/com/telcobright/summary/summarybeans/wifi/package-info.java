/**
 * The <b>wifi</b> summary category (SS-0006; the WiFi architect's spec §3; the owner, 2026-10-16: "summary beans for WiFi
 * per hour/day like a call, including the average session duration and the data volume per destination IP"). Two
 * bean pairs, each config-instantiated per window from the profile ({@code summary.beans.<name>.kind}, through
 * {@link com.telcobright.summary.summarybeans.wifi.WifiSummaries}):
 * <ul>
 *   <li>the SESSION per hour / day — {@code kind: wifi-session}: the {@code cdr} outbox's records of service group 101
 *       (the WiFi session is a Call), keyed by the record's own COLUMNS (the BRAS — {@code InMgwId}, SS-0007 — site,
 *       zone, grant tier, package, unit, cause family), summed into {@code sum_wifi_hr_101} / {@code sum_wifi_day_101}
 *       with the sessions, the connected ones, the seconds, the bytes up and down, the cost — and the DERIVED average
 *       session duration. The rows summarised before SS-0007 keep {@code tup_gw = ''} (the column's default, given to
 *       an older table at its first use);</li>
 *   <li>the volume per DESTINATION per hour / day — {@code kind: wifi-dst}: the {@code wifi_flow} outbox's rows (the
 *       gateway's flow windows billing-core ingested), keyed by gateway, site, zone, remote address, protocol and
 *       port, summed into {@code sum_wifi_dst_hr} / {@code sum_wifi_dst_day} with the bytes, the packets and the flows.
 *       No money there; no distinct devices (not additive — a report query on {@code wifi_flow_window}).</li>
 * </ul>
 * Same shape as every category: the entities in {@code model/}, the machinery in {@code internal/}; the blob decoder
 * is the call category's {@code CdrBlobMapper} (case-insensitive, lenient) and the session's input is the call
 * category's {@code RatedCall}. The tables are the service's own on both engines ({@code SummaryTableSpec},
 * partitioned by day on MySQL, kept up to date).
 *
 * <p>SS-0008: this package is the WIFI kind ({@link com.telcobright.summary.summarybeans.wifi.WifiKind}): service group 101,
 * the entities {@code cdr} and {@code wifi_flow}, the two shapes above (its fine-tuning key {@code summary.beans.<name>.kind}),
 * and the session as a call ({@code sum_voice_*_101}, a call-shaped bean of group 101).
 */
package com.telcobright.summary.summarybeans.wifi;
