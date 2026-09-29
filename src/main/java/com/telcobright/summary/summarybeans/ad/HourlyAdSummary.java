package com.telcobright.summary.summarybeans.ad;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.ad.internal.AdSummaryBean;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The <b>hourly</b> ad summary — one {@link AdSummaryBean} fixed to the {@code hourly} window, writing
 * {@code sum_ad_hr_30}. Activate it by listing {@code hourlyAdSummary} in {@code summary.enabledSummary}.
 */
@Singleton
public final class HourlyAdSummary extends AdSummaryBean {

    public static final String NAME = "hourlyAdSummary";

    private static final WindowSize WINDOW = WindowSize.parse("hourly");

    @Inject
    public HourlyAdSummary(ObjectMapper mapper) {
        super(mapper, NAME);
    }

    /** Explicit wiring (tests / non-CDI). */
    public HourlyAdSummary(ObjectMapper blobMapper, String context) {
        super(blobMapper, NAME, context);
    }

    @Override
    public WindowSize window() {
        return WINDOW;
    }
}
