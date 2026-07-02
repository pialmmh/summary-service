package com.telcobright.summary.beans;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.summarybeans.chargeable.DailyChargeableSummary;
import com.telcobright.summary.summarybeans.chargeable.model.ChargeableSummary;

/**
 * High-level entry point for the <b>daily</b> chargeable summary bean:
 * <pre>{@code
 * SummaryBean<ChargeableSummary> daily = DailyChargeableSummaryBuilder.create(mapper)
 *         .context("mediationContext")   // optional
 *         .build();                      // -> table sum_chargeable_day (fixed; every SG, every leg)
 * }</pre>
 * No service-group and no table-suffix — the chargeable rollup keeps EVERY leg of every service group and its
 * table is fixed per window.
 */
public final class DailyChargeableSummaryBuilder
        extends SummaryBeanBuilder<ChargeableSummary, DailyChargeableSummaryBuilder> {

    private DailyChargeableSummaryBuilder(ObjectMapper blobMapper) {
        super(blobMapper);
    }

    /** Start a daily-chargeable-summary builder over the given blob ObjectMapper. */
    public static DailyChargeableSummaryBuilder create(ObjectMapper blobMapper) {
        return new DailyChargeableSummaryBuilder(blobMapper);
    }

    @Override
    protected DailyChargeableSummaryBuilder self() {
        return this;
    }

    @Override
    protected SummaryBean<ChargeableSummary> construct() {
        return new DailyChargeableSummary(blobMapper, context);
    }
}
