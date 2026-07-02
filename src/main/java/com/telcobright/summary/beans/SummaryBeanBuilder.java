package com.telcobright.summary.beans;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryEntity;

/**
 * The enforced builder CONTRACT for every summary bean — present and future. A concrete entry point (e.g.
 * {@link DailySummaryBuilder}, {@link DailyChargeableSummaryBuilder}) extends this (voice builders via the
 * {@link CallBeanBuilder} layer, which adds the service-group/table-suffix chain the call category requires),
 * and implements {@link #construct()} to assemble its bean. {@link #build()} is shared and {@code final}: it
 * runs {@link #validate()} and then constructs — so no bean can skip its category's required-field checks.
 *
 * <p>This is the brevity convention: a library user configures any bean the same fluent way —
 * {@code XxxBuilder.create(mapper).….build()} — and a NEW bean joins the convention simply by shipping a
 * {@code XxxBuilder} in this package. The recursive self-type keeps the chain returning the concrete builder.
 *
 * @param <T> the summary entity the built bean maintains
 * @param <B> the concrete builder's own type (curiously-recurring, so setters return {@code B})
 */
public abstract class SummaryBeanBuilder<T extends SummaryEntity<T>, B extends SummaryBeanBuilder<T, B>> {

    /** The blob ObjectMapper the bean decodes outbox rows with (the category tunes a copy of it). */
    protected final ObjectMapper blobMapper;

    /** The shared read-only context name this bean needs (e.g. {@code "mediationContext"}), or null. */
    protected String context;

    protected SummaryBeanBuilder(ObjectMapper blobMapper) {
        this.blobMapper = blobMapper;
    }

    /** The shared read-only context this bean needs, or null if none. */
    public B context(String context) {
        this.context = context;
        return self();
    }

    /** Build the configured bean. Shared and final — {@link #validate()} always runs first. */
    public final SummaryBean<T> build() {
        validate();
        return construct();
    }

    /** The category's required-field checks (e.g. the call category requires service-group + table-suffix). */
    protected void validate() {
        // no common required fields; category layers override
    }

    /** Return {@code this} as the concrete builder type, so the fluent setters chain on {@code B}. */
    protected abstract B self();

    /** Assemble the concrete, configured bean from the collected settings. */
    protected abstract SummaryBean<T> construct();
}
