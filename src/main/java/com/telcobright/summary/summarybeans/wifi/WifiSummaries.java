package com.telcobright.summary.summarybeans.wifi;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.call.internal.CdrBlobMapper;
import com.telcobright.summary.summarybeans.wifi.internal.WifiSessionSummaryBean;
import com.telcobright.summary.summarybeans.wifi.model.WifiSessionSummary;

/**
 * Factory for the CONFIG-INSTANTIATED WiFi beans (SS-0006) — the call category's {@code CallSummaries.forWindow}
 * pattern: a yml entry under {@code summary.beans.<name>} with a {@code window:} and a {@code kind:} is materialised
 * through here under its OWN name — its own offset bookmark, worker and table. There is no catalog class per window.
 */
public final class WifiSummaries {

    private WifiSummaries() {
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
}
