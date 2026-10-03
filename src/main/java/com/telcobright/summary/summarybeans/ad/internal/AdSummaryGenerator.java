package com.telcobright.summary.summarybeans.ad.internal;

import com.telcobright.summary.bean.spi.SummaryGenerator;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.ad.model.AdSummary;
import com.telcobright.summary.summarybeans.ad.model.AdView;

/**
 * The AD category's generator — {@code I = AdView} (one view as one tier recorded it), {@code T = AdSummary}.
 * Per-input logic in {@link AdSummaryBuilder}; the batch loop and the append / replace modes come from the
 * {@link SummaryGenerator} base and the engine.
 */
public final class AdSummaryGenerator extends SummaryGenerator<AdView, AdSummary> {

    @Override
    protected AdSummary generateOne(AdView input, WindowSize window) {
        return AdSummaryBuilder.build(input, window);
    }
}
