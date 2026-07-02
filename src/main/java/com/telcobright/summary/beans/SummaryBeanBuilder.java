package com.telcobright.summary.beans;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryEntity;

/**
 * The enforced builder CONTRACT for every summary bean — present and future. A concrete entry point (e.g.
 * {@link DailySummaryBuilder}) extends this with its own self-type {@code B}, inherits the fluent
 * {@link #tableSuffix}/{@link #serviceGroup}/{@link #context} chain, and implements {@link #construct()} to
 * assemble its bean. {@link #build()} is shared and {@code final}: it validates the common invariants (a service
 * group and a well-formed table suffix are required) and then constructs — so no bean can skip that check.
 *
 * <p>This is the brevity convention: a library user configures any bean the same way —
 * {@code XxxBuilder.create(mapper).serviceGroup(..).tableSuffix(..).context(..).build()} — and a NEW bean joins the
 * convention simply by shipping a {@code XxxBuilder extends SummaryBeanBuilder} in this package. The recursive
 * self-type keeps the chain returning the concrete builder, so a bean may add its own setters and still chain.
 *
 * @param <T> the summary entity the built bean maintains
 * @param <B> the concrete builder's own type (curiously-recurring, so setters return {@code B})
 */
public abstract class SummaryBeanBuilder<T extends SummaryEntity<T>, B extends SummaryBeanBuilder<T, B>> {

    /** The blob ObjectMapper the bean decodes outbox rows with (the category tunes a copy of it). */
    protected final ObjectMapper blobMapper;

    /** The pre-provisioned table set this bean writes to (e.g. {@code "3"} → {@code sum_voice_<window>_3}) — REQUIRED. */
    protected String tableSuffix;

    /** The service group whose records this bean keeps (the batch filter) — REQUIRED. */
    protected Integer serviceGroup;

    /** The shared read-only context name this bean needs (e.g. {@code "mediationContext"}), or null. */
    protected String context;

    protected SummaryBeanBuilder(ObjectMapper blobMapper) {
        this.blobMapper = blobMapper;
    }

    /** The table-suffix selecting the pre-provisioned set (e.g. {@code "3"} → {@code sum_voice_<window>_3}). */
    public B tableSuffix(String tableSuffix) {
        this.tableSuffix = tableSuffix;
        return self();
    }

    /** The service group whose records this bean keeps (the batch filter; does NOT name the table). */
    public B serviceGroup(int serviceGroup) {
        this.serviceGroup = serviceGroup;
        return self();
    }

    /** The shared read-only context this bean needs, or null if none. */
    public B context(String context) {
        this.context = context;
        return self();
    }

    /** Build + validate the configured bean. Shared and final — every bean gets the same required-field checks. */
    public final SummaryBean<T> build() {
        if (serviceGroup == null) {
            throw new IllegalStateException("a summary bean requires a service group — call .serviceGroup(..) before .build()");
        }
        if (tableSuffix == null || tableSuffix.isBlank()) {
            throw new IllegalStateException("a summary bean requires a table suffix — call .tableSuffix(..) before .build()");
        }
        if (!tableSuffix.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException("table suffix '" + tableSuffix
                    + "' is invalid — only letters, digits and _ are allowed (it lands in a derived table name)");
        }
        return construct();
    }

    /** Return {@code this} as the concrete builder type, so the fluent setters chain on {@code B}. */
    protected abstract B self();

    /** Assemble the concrete, configured bean from the collected settings. */
    protected abstract SummaryBean<T> construct();
}
