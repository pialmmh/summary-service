package com.telcobright.summary.summarybeans.ad.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The ad category's view of the {@code Cdr} half of a {@code cdr} outbox blob entry (blob v2, written by
 * billing-core): only the fields the ad summary reads, by billing-core's own names, decoded case-insensitively,
 * every other field ignored. An ad view is a {@code cdr} row of service group 30 (ad-is-a-call §4.1):
 *
 * <ul>
 *   <li>{@code InPartnerId} — this tier's payer;</li>
 *   <li>{@code OriginatingCalledNumber} — the rule's code;</li>
 *   <li>{@code Codec} — the media kind;</li>
 *   <li>{@code HangupCause} — the ad cause: {@code NORMAL_CLEARING} is a done view, every other word a failed one;</li>
 *   <li>{@code StartTime} / {@code AnswerTime} — admitted / shown (no answer time = never shown), the tenant's wall clock;</li>
 *   <li>{@code DurationSec} — the seconds watched;</li>
 *   <li>{@code AdditionalMetaData} — one JSON object, the view's own facts ({@link AdViewFacts}).</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdCallCdr(
        Integer serviceGroup,
        Integer inPartnerId,
        String originatingCalledNumber,
        String codec,
        String hangupCause,
        LocalDateTime startTime,
        LocalDateTime answerTime,
        BigDecimal durationSec,
        String additionalMetaData
) {
    /** The ad view's service group (ad-is-a-call §4.1). */
    public static final int SERVICE_GROUP = 30;
    /** The cause of a view that ended as it should. */
    public static final String CAUSE_DONE = "NORMAL_CLEARING";

    public boolean isAdView() {
        return serviceGroup != null && serviceGroup == SERVICE_GROUP;
    }

    /** Done = the view cleared normally; every other cause (and none) is a failed view. */
    public boolean done() {
        return hangupCause != null && CAUSE_DONE.equalsIgnoreCase(hangupCause.trim());
    }

    /** Shown = the record has an answer time. */
    public boolean wasShown() {
        return answerTime != null;
    }
}
