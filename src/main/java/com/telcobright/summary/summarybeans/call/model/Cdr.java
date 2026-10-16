package com.telcobright.summary.summarybeans.call.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The cdr half of an outbox blob entry — the rated CDR fields the {@link CallSummaryBuilder} reads. PINNED:
 * field names are billing-core's C# {@code cdr} PascalCase properties, decoded CASE-INSENSITIVELY (so the
 * camelCase fields here match), nulls omitted. {@code StartTime} is the cdr's wall-clock local start (the
 * window bucket source); {@code ConnectTime} present ⇒ the call connected.
 *
 * <p>Since SS-0006 the model also reads the columns billing-core's WiFi dictionary fills on a 101 row (BC-0008,
 * {@code mediation/servicegroups/WifiColumns.java:82-104}; the contract page's §2a): the bytes in {@code XAmount} /
 * {@code YAmount}, the volume budget in {@code ZAmount}, the site and the zone in {@code InTrunkAdditionalInfo} /
 * {@code OutTrunkAdditionalInfo}, the grant tier's word in {@code MatchedPrefixY} and its number in {@code Category},
 * the MAC in {@code TerminatingCallingNumber}, the device IP in {@code OriginatingIP}, the granted seconds in
 * {@code Duration1}, the RADIUS cause in {@code ReleaseCauseSystem}, who ended it in {@code ReleaseDirection}, measured
 * in {@code ValidFlag} — and the package stamps of the ratified wire, {@code PackageAmount} / {@code IdPackageAccount}
 * (SS-0005's F2), and {@code HangupCause} (the session's end word, the cause family's source). A blob written before
 * these columns existed decodes them as null — nothing else of the mapper changes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Cdr(
        int switchId,
        Integer inPartnerId,
        Integer outPartnerId,
        String incomingRoute,
        String outgoingRoute,
        String originatingIp,        // C# OriginatingIP — a WiFi session's device IP (BC-0008)
        String terminatingIp,        // C# TerminatingIP
        String countryCode,
        LocalDateTime connectTime,
        Integer nerSuccess,          // C# NERSuccess
        Integer chargingStatus,
        BigDecimal durationSec,
        BigDecimal roundedDuration,
        BigDecimal duration1,        // a WiFi session's granted seconds (BC-0008: grantedMinutes × 60)
        BigDecimal duration2,
        BigDecimal duration3,
        BigDecimal pdd,              // C# PDD
        LocalDateTime startTime,
        Integer ansIdTerm,
        Integer ansIdOrig,
        String matchedPrefixSupplier,
        String matchedPrefixY,       // a WiFi session's grant tier WORD: free | paid | admin | test (BC-0008)
        BigDecimal outPartnerCost,
        BigDecimal supplierRate,
        BigDecimal tax2,
        // -- the 5 SG10 faithfulness fields (work order §2; absent until billing's package/Z flows exist) --
        String matchedPrefixCustomer,  // SG10: the prefix; a WiFi session: the package code (BC-0008)
        BigDecimal zAmount,            // C# ZAmount — SG10: ans tax -> vat; a WiFi session: the volume budget in bytes
        BigDecimal costAnsIn,          // -> longDecimalAmount1 (anscost)
        String additionalSystemCodes,  // JSON STRING carrying the package amount -> parse -> longDecimalAmount2
        String additionalPartyNumber,  // JSON STRING carrying the package id     -> parse -> intAmount1
        // -- the WiFi session's columns (SS-0006 item 1; BC-0008's WifiColumns dictionary), null on an older blob --
        BigDecimal xAmount,            // C# XAmount — bytes up, an exact integer
        BigDecimal yAmount,            // C# YAmount — bytes down, an exact integer
        String inTrunkAdditionalInfo,  // the site
        String outTrunkAdditionalInfo, // the zone
        Integer category,              // the grant tier's number: free 1, paid 2, admin 3, test 4
        Integer validFlag,             // measured: 1 the bytes are the plane's, 0 A-0099's unmeasured fault
        String terminatingCallingNumber,   // the MAC (the gateway stamps it on every session)
        Integer releaseCauseSystem,    // the RADIUS Acct-Terminate-Cause
        Integer releaseDirection,      // who ended it: 1 the network/plane, 2 the subscriber, 3 the system
        BigDecimal packageAmount,      // the units taken from the bucket (the ratified wire's column; SS-0005 F2)
        Long idPackageAccount,         // the bucket they were taken from
        String hangupCause             // the session's end word (the cause family's source)
) {
}
