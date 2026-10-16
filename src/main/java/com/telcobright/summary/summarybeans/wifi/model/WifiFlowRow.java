package com.telcobright.summary.summarybeans.wifi.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One row of a {@code wifi_flow} outbox blob — a {@code wifi_flow_window} row as billing-core INSERTED it and carried
 * it to this service (BC-0008 §2; the contract page's §2b; {@code mediation/cdr/WifiFlowOutboxRow.java:12-14}, whose
 * component names these are, read case-insensitively): the window's start ({@code window_start}, an ISO-8601 instant
 * in UTC as the gateway sends it), its length, the gateway, the zone, the site, the segment, the device's MAC, the
 * remote address (dotted IPv4 text), the service port and the protocol, and the RAW deltas of the window — packets and
 * bytes up and down. A null ({@code zone}, {@code site}, {@code seg}, {@code produced_at}) is left out of the JSON, so
 * every field is boxed; a key this service does not read ({@code window_sec}, {@code produced_at}) rides along, and
 * an unknown key is ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WifiFlowRow(
        String window_start,
        Integer window_sec,
        String gw,
        String zone,
        String site,
        Integer seg,
        String mac,
        String remote,
        Integer port,
        Integer proto,
        Long packets_up,
        Long packets_dn,
        Long bytes_up,
        Long bytes_dn,
        String produced_at
) {
}
