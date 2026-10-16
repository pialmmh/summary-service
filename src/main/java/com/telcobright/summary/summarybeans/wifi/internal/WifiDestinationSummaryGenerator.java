package com.telcobright.summary.summarybeans.wifi.internal;

import com.telcobright.summary.bean.spi.SummaryGenerator;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.wifi.model.WifiDestinationSummary;
import com.telcobright.summary.summarybeans.wifi.model.WifiFlowRow;

import java.time.Instant;
import java.time.ZoneId;

/**
 * The WiFi DESTINATION generator — {@code I = }{@link Timed} (one flow row with its window's start already read),
 * {@code T = WifiDestinationSummary}. It carries the root tenant's zone, on which every window's start is put before
 * it is cut to the bucket. Per-input logic in {@link WifiDestinationSummaryBuilder}.
 */
public final class WifiDestinationSummaryGenerator extends SummaryGenerator<WifiDestinationSummaryGenerator.Timed, WifiDestinationSummary> {

    /** A flow row and its window's start as an instant (read once by the bean, so a row with none never gets here). */
    public record Timed(WifiFlowRow row, Instant windowStart) {
    }

    private final ZoneId tenantZone;

    public WifiDestinationSummaryGenerator(ZoneId tenantZone) {
        this.tenantZone = tenantZone;
    }

    @Override
    protected WifiDestinationSummary generateOne(Timed input, WindowSize window) {
        return WifiDestinationSummaryBuilder.build(input.row(), input.windowStart(), window, tenantZone);
    }
}
