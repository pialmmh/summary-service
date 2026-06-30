package com.telcobright.summary.summarybeans.call;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.call.internal.CallSummaryBean;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The <b>hourly</b> call summary — one {@link CallSummaryBean} fixed to the {@code hourly} window. Activate it by
 * listing {@code hourlyCallSummary} in {@code summary.enabledSummary}; its {@code table} / {@code service-group}
 * / {@code context} come from {@code summary.beans.hourlyCallSummary} in the active profile yml.
 */
@Singleton
public final class HourlySummary extends CallSummaryBean {

    public static final String NAME = "hourlyCallSummary";

    private static final WindowSize WINDOW = WindowSize.parse("hourly");

    @Inject
    public HourlySummary(ObjectMapper mapper) {
        super(mapper, NAME);
    }

    /** Explicit wiring (tests / non-CDI). */
    public HourlySummary(ObjectMapper blobMapper, String tableSuffix, int serviceGroup, String context) {
        super(blobMapper, NAME, tableSuffix, serviceGroup, context);
    }

    @Override
    public WindowSize window() {
        return WINDOW;
    }
}
