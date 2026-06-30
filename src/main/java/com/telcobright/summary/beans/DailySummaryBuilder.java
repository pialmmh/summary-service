package com.telcobright.summary.beans;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.summarybeans.call.DailySummary;
import com.telcobright.summary.summarybeans.call.model.CallSummary;

/**
 * High-level entry point for the <b>daily</b> call summary bean — the public, fluent way a library user
 * assembles it:
 * <pre>{@code
 * SummaryBean<CallSummary> daily = DailySummaryBuilder.create(mapper)
 *         .serviceGroup(10)              // filters SG10 records
 *         .tableSuffix("3")              // -> table sum_voice_day_3 (pre-provisioned set)
 *         .context("mediationContext")
 *         .build();
 * }</pre>
 * The window ({@code daily}) and bean name are fixed by {@link DailySummary}; the table DERIVES as
 * {@code sum_voice_day_<table-suffix>} (the suffix selects one of the pre-provisioned table sets). (The running Quarkus service still wires this bean via CDI + YAML — this builder is the
 * programmatic API for embedders and tests.)
 */
public final class DailySummaryBuilder extends SummaryBeanBuilder<CallSummary, DailySummaryBuilder> {

    private DailySummaryBuilder(ObjectMapper blobMapper) {
        super(blobMapper);
    }

    /** Start a daily-summary builder over the given blob ObjectMapper. */
    public static DailySummaryBuilder create(ObjectMapper blobMapper) {
        return new DailySummaryBuilder(blobMapper);
    }

    @Override
    protected DailySummaryBuilder self() {
        return this;
    }

    @Override
    protected SummaryBean<CallSummary> construct() {
        return new DailySummary(blobMapper, tableSuffix, serviceGroup, context);
    }
}
