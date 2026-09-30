package com.telcobright.summary.summarybeans.ad.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One element of an {@code ad_cdr} outbox blob's JSON array — the same v2 envelope as voice
 * ({@code {"Cdr": {…}, "Chargeables": [tier…]}}), so the codec and the drain are the ones the call category uses;
 * only the shapes inside differ. A refused or timed-out call carries NO legs: it still counts, on the entry tenant.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdCdrEntry(AdCdr cdr, List<AdLeg> chargeables) {

    /** Every tier, leaf first; empty for a call nothing admitted. */
    public List<AdLeg> tiers() {
        return chargeables == null ? List.of() : chargeables;
    }
}
