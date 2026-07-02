package com.telcobright.summary.beans;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.summarybeans.call.model.CallSummary;

/**
 * The CALL-category layer of the builder contract: every voice bean requires a {@code serviceGroup} (the
 * record filter) and a {@code tableSuffix} (selects the pre-provisioned {@code sum_voice_<window>_<suffix>}
 * set) — enforced here for all of them, in one place. Chargeable-category builders skip this layer entirely
 * (their tables are fixed per window and they roll up every service group).
 *
 * @param <B> the concrete builder's own type
 */
public abstract class CallBeanBuilder<B extends CallBeanBuilder<B>> extends SummaryBeanBuilder<CallSummary, B> {

    /** The pre-provisioned table set this bean writes to (e.g. {@code "03"} → {@code sum_voice_<window>_03}) — REQUIRED. */
    protected String tableSuffix;

    /** The service group whose records this bean keeps (the batch filter) — REQUIRED. */
    protected Integer serviceGroup;

    protected CallBeanBuilder(ObjectMapper blobMapper) {
        super(blobMapper);
    }

    /** The table-suffix selecting the pre-provisioned set (e.g. {@code "03"} → {@code sum_voice_<window>_03}). */
    public B tableSuffix(String tableSuffix) {
        this.tableSuffix = tableSuffix;
        return self();
    }

    /** The service group whose records this bean keeps (the batch filter; does NOT name the table). */
    public B serviceGroup(int serviceGroup) {
        this.serviceGroup = serviceGroup;
        return self();
    }

    @Override
    protected void validate() {
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
    }
}
