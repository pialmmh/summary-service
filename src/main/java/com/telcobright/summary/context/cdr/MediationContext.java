package com.telcobright.summary.context.cdr;

import com.telcobright.summary.context.spi.SummaryContext;

/**
 * The CDR mediation context — the same tenant data billing loads from config-manager (partners, route-wise
 * partners, SG routing, …), shared read-only across the cdr beans.
 *
 * <p><b>PROVISIONAL shape.</b> The pinned outbox contract feeds {@code (Cdr, Customer)} straight to the
 * builder, so for v1 the CDR build does NOT read this context — it is loaded (per the user directive) and held
 * for partner/route lookups + future beans. {@code raw} keeps the config-manager payload until the exact
 * fields are pinned.
 */
public final class MediationContext implements SummaryContext {

    private final String name;
    private final String tenant;
    private final boolean loaded;
    private final String raw;

    public MediationContext(String name, String tenant, boolean loaded, String raw) {
        this.name = name;
        this.tenant = tenant;
        this.loaded = loaded;
        this.raw = raw;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean loaded() {
        return loaded;
    }

    public String tenant() {
        return tenant;
    }

    /** The raw config-manager tenant-root payload (provisional; parsed when fields are pinned). */
    public String raw() {
        return raw;
    }
}
