package com.telcobright.summary.summarybeans.wifi.internal;

import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.wifi.model.WifiDestinationSummary;
import com.telcobright.summary.summarybeans.wifi.model.WifiFlowRow;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Builds one {@link WifiDestinationSummary} delta from ONE flow row (one device × one remote endpoint in one window).
 *
 * <p><b>The key:</b> the gateway, the site, the zone, the remote address, the protocol and the port, as the row
 * names them ({@code WifiFlowOutboxRow}); key texts are cut to their column widths so a fresh build keys as its
 * reloaded row. <b>The bucket:</b> the window's start — an instant in UTC on the wire — put on the ROOT tenant's wall
 * clock (the zone of {@code DdlPartitions.tenantZone}, Asia/Dhaka unless the profile says otherwise; the architect's
 * ruling 7) and cut to the bean's window: the voice beans' rows are the tenant's wall clock, so are these.
 *
 * <p><b>The sums:</b> the bytes and the packets up and down as the row carries them (absent = 0); {@code flows} 1.
 * The MAC is not in the key and not counted (ruling 8); the segment, the window's length and the produced-at are read
 * past.
 */
final class WifiDestinationSummaryBuilder {

    // ---- the widths of the key's texts: ONE number each, the cut below AND the table (SumWifiDestinationDdl) ----
    static final int GW_WIDTH = 64;
    static final int SITE_WIDTH = 64;
    static final int ZONE_WIDTH = 64;
    /** {@code tup_remote}: a dotted IPv4 is 15 characters; 45 holds the longest textual IPv6 too. */
    static final int REMOTE_WIDTH = 45;

    private WifiDestinationSummaryBuilder() {
    }

    static WifiDestinationSummary build(WifiFlowRow row, Instant windowStart, WindowSize window, ZoneId tenantZone) {
        WifiDestinationSummary s = new WifiDestinationSummary();
        stampKey(s, row);
        s.tup_starttime = window.bucketStart(windowStart.atZone(tenantZone).toLocalDateTime());
        stampSums(s, row);
        return s;
    }

    private static void stampKey(WifiDestinationSummary s, WifiFlowRow row) {
        s.tup_gw = clip(orEmpty(row.gw()), GW_WIDTH);
        s.tup_site = clip(orEmpty(row.site()), SITE_WIDTH);
        s.tup_zone = clip(orEmpty(row.zone()), ZONE_WIDTH);
        s.tup_remote = clip(orEmpty(row.remote()), REMOTE_WIDTH);
        s.tup_proto = row.proto() == null ? 0 : row.proto();
        s.tup_port = row.port() == null ? 0 : row.port();
    }

    private static void stampSums(WifiDestinationSummary s, WifiFlowRow row) {
        s.bytes_up = nz(row.bytes_up());
        s.bytes_dn = nz(row.bytes_dn());
        s.packets_up = nz(row.packets_up());
        s.packets_dn = nz(row.packets_dn());
        s.flows = 1;
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    private static String orEmpty(String v) {
        return v == null ? "" : v.trim();
    }

    private static String clip(String v, int maxLength) {
        return v.length() <= maxLength ? v : v.substring(0, maxLength);
    }
}
