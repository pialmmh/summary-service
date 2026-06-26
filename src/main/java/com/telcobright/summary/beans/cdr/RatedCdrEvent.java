package com.telcobright.summary.beans.cdr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * The rated-CDR event this service consumes — ONE per rated call, emitted by billing-core to Kafka. It carries
 * the cdr + its customer chargeable fields the {@link CdrSummaryBuilder} reads (the union; each service group
 * uses its own subset).
 *
 * <p><b>PROVISIONAL CONTRACT.</b> Field names/types are the architect's proposal mapped field-by-field to the
 * legacy {@code CdrSummaryBuilder}; pending billing-core (dotnet) pinning the real event schema + topic. When
 * it lands, reconcile this record + the builder — nothing else changes. {@code startEpochMillis} /
 * {@code connectTimeEpochMillis} are UTC epoch millis; counter source fields may be null (treated as zero).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RatedCdrEvent(
        int serviceGroup,                 // chargeable.servicegroup (10 -> *_03, 11 -> *_02)
        long startEpochMillis,            // cdr.StartTime (the window bucket source)

        // --- common identity / counts / durations (PopulateCommon) ---
        int switchId,
        Integer inPartnerId,
        Integer outPartnerId,
        String incomingRoute,
        String outgoingRoute,
        String originatingIp,
        String terminatingIp,
        Long connectTimeEpochMillis,      // present -> connectedcalls=1
        Integer nerSuccess,               // ==1 -> connectedcallsCC=1
        Integer chargingStatus,           // -> successfulcalls
        BigDecimal durationSec,
        BigDecimal roundedDuration,
        BigDecimal duration1,
        BigDecimal duration2,
        BigDecimal duration3,
        BigDecimal pdd,
        String countryCode,

        // --- service-group-specific (PopulateServiceGroup) ---
        Integer ansIdTerm,                // SG10 -> tup_destinationId
        Integer ansIdOrig,                // SG11 -> tup_sourceId
        String matchedPrefixSupplier,     // SG10
        String matchedPrefixY,            // SG11 -> tup_matchedprefixcustomer
        String prefix,                    // chargeable.Prefix (SG10 customer prefix)
        BigDecimal unitPriceOrCharge,     // SG10 customer rate
        String idBilledUom,               // SG10 customer currency
        BigDecimal billedAmount,          // customer cost (both SGs)
        BigDecimal taxAmount1,            // tax1 (both SGs)
        BigDecimal otherDecAmount1,       // SG11 customer rate
        BigDecimal otherAmount1,          // SG11 -> longDecimalAmount1
        BigDecimal outPartnerCost,        // SG10 supplier cost
        BigDecimal supplierRate,          // SG10 supplier rate
        BigDecimal tax2                   // SG10 supplier tax
) {
}
