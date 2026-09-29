package com.telcobright.summary.summarybeans.ad.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;

/**
 * The {@code Cdr} half of an {@code ad_cdr} outbox blob entry (seed-callflow's {@code AdCdrBlob}, 2026-09-29):
 * billing's PascalCase names the voice decoder reads ({@code InPartnerId, OutPartnerId, StartTime, ConnectTime,
 * DurationSec, ChargingStatus, NERSuccess}) plus the ad facts the ad category keys on. Decoded case-insensitively,
 * unknown fields ignored — a blob from a newer ad-sphere never fails the drain.
 *
 * @param outcome  {@code done} (a normal clearing) | {@code failed} (every other cause)
 * @param answered the ad reached the screen
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdCdr(
        String sessionId,
        String tenant,
        Integer inPartnerId,
        Integer outPartnerId,
        String incomingRoute,
        String outgoingRoute,
        LocalDateTime startTime,
        LocalDateTime connectTime,
        LocalDateTime endTime,
        Integer durationSec,
        Integer chargingStatus,
        Integer nerSuccess,
        Integer campaignId,
        String campaignName,
        String contentId,
        Integer contentPartnerId,
        String ruleCode,
        String zone,
        String site,
        String district,
        String app,
        String mediaKind,
        Integer requiredSeconds,
        Boolean answered,
        String outcome,
        String hangupCause,
        Boolean fallback
) {
    public static final String OUTCOME_DONE = "done";
    public static final String OUTCOME_FAILED = "failed";

    public boolean done() { return OUTCOME_DONE.equalsIgnoreCase(outcome == null ? "" : outcome.trim()); }
    public boolean wasShown() { return Boolean.TRUE.equals(answered) || connectTime != null; }
}
