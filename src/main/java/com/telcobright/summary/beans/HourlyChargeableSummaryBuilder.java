package com.telcobright.summary.beans;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.summarybeans.chargeable.HourlyChargeableSummary;
import com.telcobright.summary.summarybeans.chargeable.model.ChargeableSummary;

/**
 * High-level entry point for the <b>hourly</b> chargeable summary bean — same chain as
 * {@link DailyChargeableSummaryBuilder}, table {@code sum_chargeable_hr} (fixed; every SG, every leg).
 */
public final class HourlyChargeableSummaryBuilder
        extends SummaryBeanBuilder<ChargeableSummary, HourlyChargeableSummaryBuilder> {

    private HourlyChargeableSummaryBuilder(ObjectMapper blobMapper) {
        super(blobMapper);
    }

    /** Start an hourly-chargeable-summary builder over the given blob ObjectMapper. */
    public static HourlyChargeableSummaryBuilder create(ObjectMapper blobMapper) {
        return new HourlyChargeableSummaryBuilder(blobMapper);
    }

    @Override
    protected HourlyChargeableSummaryBuilder self() {
        return this;
    }

    @Override
    protected SummaryBean<ChargeableSummary> construct() {
        return new HourlyChargeableSummary(blobMapper, context);
    }
}
