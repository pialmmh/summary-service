package com.telcobright.summary.summarybeans.ad.internal;

import com.telcobright.summary.bean.spi.SummaryGenerator;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.ad.model.AdSummary;
import com.telcobright.summary.summarybeans.ad.model.AdTier;

/**
 * The AD category's generator — {@code I = AdTier} (one call, one tier), {@code T = AdSummary}. Per-input logic in
 * {@link AdSummaryBuilder}; the batch loop and the append / replace modes come from the {@link SummaryGenerator}
 * base and the engine.
 */
public final class AdSummaryGenerator extends SummaryGenerator<AdTier, AdSummary> {

    @Override
    protected AdSummary generateOne(AdTier input, WindowSize window) {
        return AdSummaryBuilder.build(input.cdr(), input.leg(), window);
    }
}
