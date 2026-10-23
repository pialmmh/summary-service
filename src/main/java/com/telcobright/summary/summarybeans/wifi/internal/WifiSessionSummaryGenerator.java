package com.telcobright.summary.summarybeans.wifi.internal;

import com.telcobright.summary.bean.spi.SummaryGenerator;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.call.model.RatedCall;
import com.telcobright.summary.summarybeans.wifi.model.WifiSessionSummary;

/**
 * The WiFi SESSION generator — {@code I = RatedCall} (the session's cdr + its picked customer leg, the call
 * category's own input), {@code T = WifiSessionSummary}. Per-input logic in {@link WifiSessionSummaryBuilder}; the
 * batch loop and the append / subtract modes come from the {@link SummaryGenerator} base and the engine.
 */
public final class WifiSessionSummaryGenerator extends SummaryGenerator<RatedCall, WifiSessionSummary> {

    @Override
    protected WifiSessionSummary generateOne(RatedCall input, WindowSize window) {
        return WifiSessionSummaryBuilder.build(input.cdr(), input.customerLeg(), window);
    }
}
