package com.telcobright.summary.summarybeans.chargeable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.chargeable.internal.ChargeableSummaryBean;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The <b>daily</b> chargeable summary — one {@link ChargeableSummaryBean} fixed to the {@code daily} window,
 * writing {@code sum_chargeable_day}. Activate it by listing {@code dailyChargeableSummary} in
 * {@code summary.enabledSummary}; only {@code context} comes from {@code summary.beans.dailyChargeableSummary}.
 */
@Singleton
public final class DailyChargeableSummary extends ChargeableSummaryBean {

    public static final String NAME = "dailyChargeableSummary";

    private static final WindowSize WINDOW = WindowSize.parse("daily");

    @Inject
    public DailyChargeableSummary(ObjectMapper mapper) {
        super(mapper, NAME);
    }

    /** Explicit wiring (tests / non-CDI). */
    public DailyChargeableSummary(ObjectMapper blobMapper, String context) {
        super(blobMapper, NAME, context);
    }

    @Override
    public WindowSize window() {
        return WINDOW;
    }
}
