package com.telcobright.summary.summarybeans.wifi.internal;

import java.util.Locale;
import java.util.Map;

/**
 * The cause FAMILY of a WiFi session — the key {@code tup_causefamily}, one of seven words (the architect's ruling 6,
 * SS-0006): {@code idle | time | volume | balance | deauth | shutdown | other}, from the record's {@code HangupCause}.
 *
 * <p>The words the record carries were read at their source ({@code wifi} origin/master, 2026-10-16). The gateway
 * writes {@code hangupCause} as {@code WifiCause.of(<the base's cause>)} ({@code flow/api/WifiSessionFlow.java:656});
 * the base's cause is the machine's end word or {@code NORMAL_CLEARING} ({@code seed-sessionflow
 * SessionFlowSteps.java:331-334}); {@code WifiCause.of} ({@code flow/api/WifiCause.java:42-59}) turns the live
 * machine's lower-case words into the record's upper-case ones and passes a word it does not know through AS IT IS.
 * The families, by the record's words:
 * <ul>
 *   <li>{@code idle} — {@code MEDIA_TIMEOUT} ({@code WifiCause.java:32}): the meter saw no packet for the media
 *       timeout, or the plane said the device is idle ({@code session/v2/UsageMeter.java:16,101,170}; the RADIUS
 *       Idle-Timeout, {@code internal/radius/RadiusAttr.java:85});</li>
 *   <li>{@code time} — {@code PACKAGE_EXPIRED} ({@code WifiCause.java:25}: the purchase's expiry passed) and the base's
 *       {@code MAX_DURATION_REACHED} ({@code SessionCause.java:37});</li>
 *   <li>{@code volume} — {@code VOLUME_EXHAUSTED} ({@code WifiCause.java:33}): the meter's or the plane's volume cut
 *       ({@code UsageMeter.java:110}, {@code PlaneCut.java:11});</li>
 *   <li>{@code balance} — {@code BALANCE_EXHAUSTED} ({@code SessionCause.java:36}) and {@code INSUFFICIENT_BALANCE}
 *       ({@code SessionCause.java:26}). NOTE: the meter's "minutes" cut (the granted minutes used up,
 *       {@code UsageMeter.java:141}) and the plane's "time-plane" cut ({@code PlaneCut.java:11}) reach the record as
 *       {@code BALANCE_EXHAUSTED} ({@code WifiCause.java:49}), so a minutes package used up counts here, under
 *       {@code balance}, not under {@code time}; the record's {@code ReleaseCauseSystem} says Session-Timeout (5) for
 *       both ({@code RadiusAttr.java:82,90}) — a report that wants that split reads the column;</li>
 *   <li>{@code deauth} — {@code DEAUTH} ({@code WifiCause.java:34}; every word that starts with {@code deauth}:
 *       {@code deauth:user}, {@code deauth:portal}, {@code deauth:flow:<cause>} — {@code WifiCause.java:45},
 *       {@code flow/internal/EndFacts.java:37}), {@code COA} ({@code WifiCause.java:35}) and {@code SUPERSEDED}
 *       ({@code WifiCause.java:37}; the free session a paid one replaced, Admin-Reset on the wire,
 *       {@code RadiusAttr.java:80,93});</li>
 *   <li>{@code shutdown} — {@code SYSTEM_SHUTDOWN} ({@code SessionCause.java:48}; the Stop says {@code restart},
 *       {@code EndFacts.java:36}) and {@code LOST_AT_RESTART} ({@code SessionCause.java:47});</li>
 *   <li>{@code other} — everything else, {@code NORMAL_CLEARING} first (the subscriber's own sign-out,
 *       {@code SessionFlowSteps.java:333}), then {@code GATE_BUSY} / {@code GATE_FAILED} / {@code NO_AUTH} /
 *       {@code SILENT} / {@code ROGUE_ROW} ({@code WifiCause.java:28-39}), the refusals before admission
 *       ({@code NO_MAC} … {@code NO_PURCHASE_NAMED}, {@code WifiCause.java:15-23}), {@code INTERNAL_ERROR}, no cause
 *       at all, and any word this class does not know.</li>
 * </ul>
 * Words are matched without regard to case; a null or blank cause is {@code other}.
 */
public final class WifiCauseFamily {

    public static final String IDLE = "idle";
    public static final String TIME = "time";
    public static final String VOLUME = "volume";
    public static final String BALANCE = "balance";
    public static final String DEAUTH = "deauth";
    public static final String SHUTDOWN = "shutdown";
    public static final String OTHER = "other";

    /** The record's words and their families, as read at the source (the class comment cites each). */
    private static final Map<String, String> BY_WORD = Map.ofEntries(
            Map.entry("MEDIA_TIMEOUT", IDLE),
            Map.entry("PACKAGE_EXPIRED", TIME),
            Map.entry("MAX_DURATION_REACHED", TIME),
            Map.entry("VOLUME_EXHAUSTED", VOLUME),
            Map.entry("BALANCE_EXHAUSTED", BALANCE),
            Map.entry("INSUFFICIENT_BALANCE", BALANCE),
            Map.entry("DEAUTH", DEAUTH),
            Map.entry("COA", DEAUTH),
            Map.entry("SUPERSEDED", DEAUTH),
            Map.entry("SYSTEM_SHUTDOWN", SHUTDOWN),
            Map.entry("LOST_AT_RESTART", SHUTDOWN));

    private static final String DEAUTH_PREFIX = "DEAUTH";

    private WifiCauseFamily() {
    }

    /** The family of a record's {@code HangupCause}; {@code other} for a word this class does not know, or none. */
    public static String of(String hangupCause) {
        if (hangupCause == null || hangupCause.isBlank()) {
            return OTHER;
        }
        String word = hangupCause.trim().toUpperCase(Locale.ROOT);
        if (word.startsWith(DEAUTH_PREFIX)) {
            return DEAUTH;
        }
        return BY_WORD.getOrDefault(word, OTHER);
    }
}
