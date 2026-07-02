package com.telcobright.summary.summarybeans.call.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One element of the outbox blob's JSON array. Blob v2 (PINNED): {@code {"Cdr": {…}, "Chargeables": [leg…]}} —
 * the rated cdr with ALL its chargeable legs. This reader is PERMANENTLY tolerant of the v1 shape
 * ({@code {"Cdr": {…}, "Customer": {…}}}) per the dotnet ruling (A3): a v1 row decodes as a single-element
 * leg list, so stray pre-upgrade rows in an outbox can never be silently lost.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CdrBlobEntry(Cdr cdr, Chargeable customer, List<Chargeable> chargeables) {

    /** v2 convenience: an entry carrying just the legs list. */
    public CdrBlobEntry(Cdr cdr, List<Chargeable> chargeables) {
        this(cdr, null, chargeables);
    }

    /** Every chargeable leg — the v2 list, or the lone v1 customer leg, or empty. */
    public List<Chargeable> legs() {
        if (chargeables != null && !chargeables.isEmpty()) {
            return chargeables;
        }
        return customer != null ? List.of(customer) : List.of();
    }

    /**
     * The customer-direction leg the VOICE build reads: {@code assignedDirection == 1}, else the first leg
     * (billing's own {@code Entry.Customer()} rule — the v1 blob carried no direction), else null.
     */
    public Chargeable customerLeg() {
        List<Chargeable> legs = legs();
        return legs.stream().filter(Chargeable::isCustomerLeg).findFirst()
                .orElse(legs.isEmpty() ? null : legs.get(0));
    }
}
