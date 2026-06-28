package com.telcobright.summary.summarybeans.call;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.WindowSize;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The <b>daily</b> call summary — one {@link CallSummaryBean} fixed to the {@code daily} window. Activate it by
 * listing {@code dailyCallSummary} in {@code summary.enabledSummary}; its {@code table} / {@code service-group}
 * / {@code context} come from {@code summary.beans.dailyCallSummary} in the active profile yml.
 */
@Singleton
public final class DailySummary extends CallSummaryBean {

    public static final String NAME = "dailyCallSummary";

    private static final WindowSize WINDOW = WindowSize.parse("daily");

    @Inject
    public DailySummary(ObjectMapper mapper) {
        super(mapper, NAME);
    }

    /** Explicit wiring (tests / non-CDI). */
    public DailySummary(ObjectMapper blobMapper, String table, int serviceGroup, String context) {
        super(blobMapper, NAME, table, serviceGroup, context);
    }

    @Override
    public WindowSize window() {
        return WINDOW;
    }
}
