package com.telcobright.summary.beans.cdr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * The customer-leg {@code acc_chargeable} half of an outbox blob entry — the fields the
 * {@link CdrSummaryBuilder} reads (PINNED, C# names decoded case-insensitively, nulls omitted).
 * {@code servicegroup} drives the SG-specific build (and the SG→table mapping the bean is configured for).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Customer(
        int servicegroup,
        String prefix,
        BigDecimal unitPriceOrCharge,
        String idBilledUom,
        BigDecimal billedAmount,
        BigDecimal taxAmount1,
        BigDecimal otherDecAmount1,
        BigDecimal otherAmount1
) {
}
