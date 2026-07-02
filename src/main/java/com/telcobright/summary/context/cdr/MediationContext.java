package com.telcobright.summary.context.cdr;

import com.telcobright.summary.context.spi.SummaryContext;

/**
 * The CDR mediation context — the same tenant data billing loads from config-manager (partners, route-wise
 * partners, SG routing, …), shared read-only across the cdr beans.
 *
 * <p><b>Held RAW by design (decisions §13e).</b> The pinned outbox contract feeds {@code (Cdr, Chargeable)}
 * straight to the builders — mediation/rating already consumed the context on billing's side, so NO summary
 * bean reads a field from it (verified against the legacy stamps). It is loaded (per the user directive) via
 * the SAME api billing calls and held; the moment a bean needs a lookup, the typed shape is ADOPTED from
 * billing-core java's {@code mediation.context.MediationContext} (the shared source of truth) — never invented
 * here. {@code raw} keeps the payload until then.
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
