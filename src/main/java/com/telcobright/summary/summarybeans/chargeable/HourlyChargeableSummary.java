package com.telcobright.summary.summarybeans.chargeable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.chargeable.internal.ChargeableSummaryBean;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The <b>hourly</b> chargeable summary — one {@link ChargeableSummaryBean} fixed to the {@code hourly} window,
 * writing {@code sum_chargeable_hr}. Activate it by listing {@code hourlyChargeableSummary} in
 * {@code summary.enabledSummary}; only {@code context} comes from {@code summary.beans.hourlyChargeableSummary}.
 */
@Singleton
public final class HourlyChargeableSummary extends ChargeableSummaryBean {

    public static final String NAME = "hourlyChargeableSummary";

    private static final WindowSize WINDOW = WindowSize.parse("hourly");

    @Inject
    public HourlyChargeableSummary(ObjectMapper mapper) {
        super(mapper, NAME);
    }

    /** Explicit wiring (tests / non-CDI). */
    public HourlyChargeableSummary(ObjectMapper blobMapper, String context) {
        super(blobMapper, NAME, context);
    }

    @Override
    public WindowSize window() {
        return WINDOW;
    }
}
