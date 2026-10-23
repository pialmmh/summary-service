package com.telcobright.summary.summarybeans.chargeable.internal;

import com.telcobright.summary.bean.spi.SummaryGenerator;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.call.model.Chargeable;
import com.telcobright.summary.summarybeans.chargeable.model.ChargeableSummary;

/**
 * The CHARGEABLE category's generator — {@code I = Chargeable} (one leg), {@code T = ChargeableSummary}.
 * Per-input logic in {@link ChargeableSummaryBuilder}; batch loop + append/replace modes from the
 * {@link SummaryGenerator} base + the engine.
 */
public final class ChargeableSummaryGenerator extends SummaryGenerator<Chargeable, ChargeableSummary> {

    @Override
    protected ChargeableSummary generateOne(Chargeable input, WindowSize window) {
        return ChargeableSummaryBuilder.build(input, window);
    }
}
