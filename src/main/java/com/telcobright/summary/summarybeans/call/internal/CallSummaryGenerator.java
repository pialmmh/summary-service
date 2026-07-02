package com.telcobright.summary.summarybeans.call.internal;

import com.telcobright.summary.bean.spi.SummaryGenerator;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.call.model.CallSummary;
import com.telcobright.summary.summarybeans.call.model.RatedCall;

/**
 * The CALL category's generator — {@code I = RatedCall} (cdr + picked customer leg), {@code T = CallSummary}.
 * The per-input logic is the faithful legacy stamp in {@link CallSummaryBuilder}; the batch loop, and the
 * append (add/subtract) + future replace modes, come from the {@link SummaryGenerator} base + the engine.
 */
public final class CallSummaryGenerator extends SummaryGenerator<RatedCall, CallSummary> {

    @Override
    protected CallSummary generateOne(RatedCall input, WindowSize window) {
        return CallSummaryBuilder.build(input.cdr(), input.customerLeg(), window);
    }
}
