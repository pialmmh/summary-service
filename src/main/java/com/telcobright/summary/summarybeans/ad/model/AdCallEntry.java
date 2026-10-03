package com.telcobright.summary.summarybeans.ad.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.telcobright.summary.summarybeans.call.model.CdrBlobEntry;
import com.telcobright.summary.summarybeans.call.model.Chargeable;

import java.util.List;

/**
 * One element of a {@code cdr} outbox blob's JSON array as the ad category reads it: the same envelope the call
 * and chargeable categories decode ({@code {"Cdr": {…}, "Chargeables": [leg…]}}, blob v2; the v1
 * {@code {"Cdr", "Customer"}} shape is tolerated as everywhere), with the ad's own view of the {@code Cdr}.
 * billing-core writes ONE customer chargeable per ad record — what the settle step charged this tier.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdCallEntry(AdCallCdr cdr, Chargeable customer, List<Chargeable> chargeables) {

    /** The customer-direction leg, by the rule every category uses; null when the entry carries no leg. */
    public Chargeable customerLeg() {
        return CdrBlobEntry.customerLegOf(chargeables, customer);
    }
}
