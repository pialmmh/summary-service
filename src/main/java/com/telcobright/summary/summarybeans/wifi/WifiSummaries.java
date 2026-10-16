package com.telcobright.summary.summarybeans.wifi;

import com.telcobright.summary.bean.spi.DdlPartitions;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.call.internal.CdrBlobMapper;
import com.telcobright.summary.summarybeans.wifi.internal.WifiDestinationSummaryBean;
import com.telcobright.summary.summarybeans.wifi.internal.WifiSessionSummaryBean;
import com.telcobright.summary.summarybeans.wifi.model.WifiDestinationSummary;
import com.telcobright.summary.summarybeans.wifi.model.WifiSessionSummary;
import org.eclipse.microprofile.config.ConfigProvider;

import java.time.ZoneId;

/**
 * Factory for the CONFIG-INSTANTIATED WiFi beans (SS-0006) — the call category's {@code CallSummaries.forWindow}
 * pattern: a yml entry under {@code summary.beans.<name>} with a {@code window:} and a {@code kind:} is materialised
 * through here under its OWN name — its own offset bookmark, worker and table. There is no catalog class per window.
 */
public final class WifiSummaries {

    private WifiSummaries() {
    }

    /** The bean of {@code kind} for any window under a custom name — what the bootstrap makes of a {@code kind:} key. */
    public static SummaryBean<?> forWindow(String name, WifiBeanKind kind, String window, String context) {
        return switch (kind) {
            case SESSION -> sessionForWindow(name, window, context);
            case DESTINATION -> destinationForWindow(name, window, context);
        };
    }

    /** A WiFi SESSION bean ({@code kind: wifi-session}; entity {@code cdr}, group 101, {@code sum_wifi_<window>_101}) for any window under a custom name. */
    public static SummaryBean<WifiSessionSummary> sessionForWindow(String name, String window, String context) {
        WindowSize windowSize = WindowSize.parse(window);
        return new WifiSessionSummaryBean(CdrBlobMapper.create(), name, context) {
            @Override
            public WindowSize window() {
                return windowSize;
            }
        };
    }

    /**
     * A WiFi DESTINATION bean ({@code kind: wifi-dst}; entity {@code wifi_flow}, {@code sum_wifi_dst_<window>}) for any
     * window under a custom name; the windows' starts are put on the root tenant's zone ({@code summary.zone},
     * Asia/Dhaka when the profile names none — {@link DdlPartitions#tenantZone}, as the voice beans' partitions).
     */
    public static SummaryBean<WifiDestinationSummary> destinationForWindow(String name, String window, String context) {
        return destinationForWindow(name, window, context, DdlPartitions.tenantZone(ConfigProvider.getConfig()));
    }

    /** The same, on a given zone (tests / an embedder). */
    public static SummaryBean<WifiDestinationSummary> destinationForWindow(String name, String window, String context, ZoneId tenantZone) {
        WindowSize windowSize = WindowSize.parse(window);
        return new WifiDestinationSummaryBean(CdrBlobMapper.create(), name, context, tenantZone) {
            @Override
            public WindowSize window() {
                return windowSize;
            }
        };
    }
}
