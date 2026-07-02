package com.telcobright.summary.summarybeans.call.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The cdr half of an outbox blob entry — the rated CDR fields the {@link CallSummaryBuilder} reads. PINNED:
 * field names are billing-core's C# {@code cdr} PascalCase properties, decoded CASE-INSENSITIVELY (so the
 * camelCase fields here match), nulls omitted. {@code StartTime} is the cdr's wall-clock local start (the
 * window bucket source); {@code ConnectTime} present ⇒ the call connected.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Cdr(
        int switchId,
        Integer inPartnerId,
        Integer outPartnerId,
        String incomingRoute,
        String outgoingRoute,
        String originatingIp,        // C# OriginatingIP
        String terminatingIp,        // C# TerminatingIP
        String countryCode,
        LocalDateTime connectTime,
        Integer nerSuccess,          // C# NERSuccess
        Integer chargingStatus,
        BigDecimal durationSec,
        BigDecimal roundedDuration,
        BigDecimal duration1,
        BigDecimal duration2,
        BigDecimal duration3,
        BigDecimal pdd,              // C# PDD
        LocalDateTime startTime,
        Integer ansIdTerm,
        Integer ansIdOrig,
        String matchedPrefixSupplier,
        String matchedPrefixY,
        BigDecimal outPartnerCost,
        BigDecimal supplierRate,
        BigDecimal tax2,
        // -- the 5 SG10 faithfulness fields (work order §2; absent until billing's package/Z flows exist) --
        String matchedPrefixCustomer,
        BigDecimal zAmount,            // C# ZAmount — ans tax -> vat
        BigDecimal costAnsIn,          // -> longDecimalAmount1 (anscost)
        String additionalSystemCodes,  // JSON STRING carrying the package amount -> parse -> longDecimalAmount2
        String additionalPartyNumber   // JSON STRING carrying the package id     -> parse -> intAmount1
) {
}
