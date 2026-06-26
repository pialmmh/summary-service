package com.telcobright.summary.beans.cdr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * The rated-CDR event this service consumes — ONE per rated call, emitted by billing-core to Kafka.
 *
 * <p><b>PROVISIONAL CONTRACT.</b> These field names/types are the architect's proposal pending billing-core
 * (dotnet) pinning the real schema (handoff posted on the summary-service channel). When the real schema
 * lands, reconcile this record + {@link CdrVoiceSummaryBean}'s extractors — nothing else changes.
 *
 * <p>{@code startEpochMillis} is the call start time in UTC epoch millis (used for windowing); the bean's zone
 * turns it into the day/hour bucket. Counter source fields may be null (treated as zero).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RatedCdrEvent(
        long startEpochMillis,

        // --- dimensions (group-by key) ---
        Integer switchId,
        Integer inPartnerId,
        Integer outPartnerId,
        String incomingRoute,
        String outgoingRoute,
        BigDecimal customerRate,
        BigDecimal supplierRate,
        String incomingIp,
        String outgoingIp,
        String countryOrAreaCode,
        String matchedPrefixCustomer,
        String matchedPrefixSupplier,
        String sourceId,
        String destinationId,
        String customerCurrency,
        String supplierCurrency,

        // --- counter source fields ---
        Integer chargingStatus,
        boolean connected,
        BigDecimal durationSec,
        BigDecimal roundedDuration,
        BigDecimal duration1,
        BigDecimal customerCost,
        BigDecimal supplierCost,
        BigDecimal tax1,
        BigDecimal tax2
) {
}
