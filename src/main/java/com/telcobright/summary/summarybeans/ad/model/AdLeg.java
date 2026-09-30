package com.telcobright.summary.summarybeans.ad.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One {@code Chargeables} leg of an {@code ad_cdr} blob entry = ONE TIER of the ad call (design §2.5): the tier's
 * tenant (its database) and partner, its level (0 = the advertiser's, 1 = the reseller's above …), the rate that
 * priced it, the amount debited, and the debit reference. {@code servicegroup 30}, {@code assignedDirection 1}
 * on every tier (the advertiser pays; there is no supplier leg this round).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdLeg(
        int servicegroup,
        int servicefamily,
        Integer assignedDirection,
        long productId,
        String idBilledUom,
        String prefix,
        LocalDateTime transactionTime,
        BigDecimal unitPriceOrCharge,
        BigDecimal billedAmount,
        BigDecimal quantity,
        String tenant,
        Integer partnerId,
        Integer levelIndex,
        String resellerHierarchy,
        String reference
) {
}
