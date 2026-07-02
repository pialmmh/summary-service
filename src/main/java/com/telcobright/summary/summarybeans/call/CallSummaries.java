package com.telcobright.summary.summarybeans.call;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.call.internal.CallSummaryBean;
import com.telcobright.summary.summarybeans.call.internal.CdrBlobMapper;
import com.telcobright.summary.summarybeans.call.model.CallSummary;

/**
 * Factory for CONFIG-INSTANTIATED call beans — extra instances beyond the singleton per-window catalog
 * ({@link DailySummary}, {@link HourlySummary}). The catalog classes fix one name each, so one class can cover
 * only ONE service group; legacy billing summarised SG10 AND SG11 in parallel. A yml entry that carries a
 * {@code window:} key (e.g. {@code dailyCallSummarySg11: {window: daily, service-group: 11, table-suffix: "2"}})
 * is materialised through here under its OWN name — its own offset bookmark, worker, and table.
 */
public final class CallSummaries {

    private CallSummaries() {
    }

    /** A call bean for any window under a custom name (its own offset/worker); window is {@code daily}/{@code hourly}/{@code 5min}/…. */
    public static SummaryBean<CallSummary> forWindow(String name, String window, String tableSuffix,
                                                     int serviceGroup, String context) {
        WindowSize windowSize = WindowSize.parse(window);
        return new CallSummaryBean(CdrBlobMapper.create(), name, tableSuffix, serviceGroup, context) {
            @Override
            public WindowSize window() {
                return windowSize;
            }
        };
    }
}
