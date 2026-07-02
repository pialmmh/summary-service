package com.telcobright.summary.beans;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.summarybeans.call.HourlySummary;
import com.telcobright.summary.summarybeans.call.model.CallSummary;

/**
 * High-level entry point for the <b>hourly</b> call summary bean — the public, fluent way a library user
 * assembles it:
 * <pre>{@code
 * SummaryBean<CallSummary> hourly = HourlySummaryBuilder.create(mapper)
 *         .serviceGroup(10)             // filters SG10 records
 *         .tableSuffix("3")             // -> table sum_voice_hr_3 (pre-provisioned set)
 *         .context("mediationContext")
 *         .build();
 * }</pre>
 * The window ({@code hourly}) and bean name are fixed by {@link HourlySummary}; the table DERIVES as
 * {@code sum_voice_hr_<table-suffix>} (the suffix selects one of the pre-provisioned table sets). (The running Quarkus service still wires this bean via CDI + YAML — this builder is the
 * programmatic API for embedders and tests.)
 */
public final class HourlySummaryBuilder extends CallBeanBuilder<HourlySummaryBuilder> {

    private HourlySummaryBuilder(ObjectMapper blobMapper) {
        super(blobMapper);
    }

    /** Start an hourly-summary builder over the given blob ObjectMapper. */
    public static HourlySummaryBuilder create(ObjectMapper blobMapper) {
        return new HourlySummaryBuilder(blobMapper);
    }

    @Override
    protected HourlySummaryBuilder self() {
        return this;
    }

    @Override
    protected SummaryBean<CallSummary> construct() {
        return new HourlySummary(blobMapper, tableSuffix, serviceGroup, context);
    }
}
