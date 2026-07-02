package com.telcobright.summary.summarybeans.call.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One {@code acc_chargeable} leg of an outbox blob entry (blob v2 carries ALL legs per cdr — customer AND
 * supplier). PINNED: property names are billing-core's {@code acc_chargeable} fields as declared (mixed case),
 * decoded case-insensitively, nulls omitted. {@code assignedDirection}: 1 = customer, 2 = supplier (0 = none,
 * unused by the batch path). {@code transactionTime} is always present and equals the cdr's {@code StartTime}
 * today — it is the CHARGEABLE summary's bucket source. The voice build reads the customer leg only.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Chargeable(
        int servicegroup,
        int servicefamily,
        Integer assignedDirection,   // billing serializes a Byte: 1=customer, 2=supplier
        long productId,              // C# ProductId
        String idBilledUom,          // currency
        String prefix,               // C# Prefix
        LocalDateTime transactionTime,
        BigDecimal unitPriceOrCharge,
        BigDecimal billedAmount,     // C# BilledAmount
        BigDecimal quantity,         // C# Quantity
        BigDecimal taxAmount1,
        BigDecimal taxAmount2,
        BigDecimal taxAmount3,
        BigDecimal vatAmount1,
        BigDecimal vatAmount2,
        BigDecimal vatAmount3,
        BigDecimal otherAmount1,
        BigDecimal otherAmount2,
        BigDecimal otherAmount3,
        BigDecimal otherDecAmount1,
        BigDecimal otherDecAmount2,
        BigDecimal otherDecAmount3
) {

    /** True for the customer-direction leg (the one the voice build reads). */
    public boolean isCustomerLeg() {
        return assignedDirection != null && assignedDirection == 1;
    }
}
