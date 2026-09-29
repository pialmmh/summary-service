package com.telcobright.summary.summarybeans.ad;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.ad.internal.AdSummaryBean;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The <b>daily</b> ad summary — one {@link AdSummaryBean} fixed to the {@code daily} window, writing
 * {@code sum_ad_day_30}. Activate it by listing {@code dailyAdSummary} in {@code summary.enabledSummary}; only
 * {@code context} / {@code mode} come from {@code summary.beans.dailyAdSummary}.
 */
@Singleton
public final class DailyAdSummary extends AdSummaryBean {

    public static final String NAME = "dailyAdSummary";

    private static final WindowSize WINDOW = WindowSize.parse("daily");

    @Inject
    public DailyAdSummary(ObjectMapper mapper) {
        super(mapper, NAME);
    }

    /** Explicit wiring (tests / non-CDI). */
    public DailyAdSummary(ObjectMapper blobMapper, String context) {
        super(blobMapper, NAME, context);
    }

    @Override
    public WindowSize window() {
        return WINDOW;
    }
}
