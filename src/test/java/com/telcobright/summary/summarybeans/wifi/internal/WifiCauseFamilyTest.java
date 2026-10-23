package com.telcobright.summary.summarybeans.wifi.internal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The cause family of a WiFi session, pinned to the REAL words the record carries ({@code wifi} origin/master,
 * 2026-10-16: {@code WifiCause.java}, {@code SessionCause.java}; the class comment of {@link WifiCauseFamily} cites the
 * line of each) — the architect's ruling 6.
 */
class WifiCauseFamilyTest {

    @Test
    void every_word_the_record_carries_has_its_family() {
        Map<String, String> words = Map.ofEntries(
                Map.entry("MEDIA_TIMEOUT", "idle"),                 // WifiCause.java:32 — the meter saw no packet / the plane said idle
                Map.entry("PACKAGE_EXPIRED", "time"),               // WifiCause.java:25 — the purchase's expiry passed
                Map.entry("MAX_DURATION_REACHED", "time"),          // SessionCause.java:37
                Map.entry("VOLUME_EXHAUSTED", "volume"),            // WifiCause.java:33 — the meter's / the plane's volume cut
                Map.entry("BALANCE_EXHAUSTED", "balance"),          // SessionCause.java:36 — the minutes used up arrive as this word too (WifiCause.java:49)
                Map.entry("INSUFFICIENT_BALANCE", "balance"),       // SessionCause.java:26
                Map.entry("DEAUTH", "deauth"),                      // WifiCause.java:34 — /deauthorize, an officer's or the operator's cut
                Map.entry("COA", "deauth"),                         // WifiCause.java:35
                Map.entry("SUPERSEDED", "deauth"),                  // WifiCause.java:37 — the free session a paid one replaced (Admin-Reset)
                Map.entry("SYSTEM_SHUTDOWN", "shutdown"),           // SessionCause.java:48 — the Stop says "restart" (EndFacts.java:36)
                Map.entry("LOST_AT_RESTART", "shutdown"),           // SessionCause.java:47
                Map.entry("NORMAL_CLEARING", "other"));             // SessionFlowSteps.java:333 — the subscriber's own sign-out

        words.forEach((word, family) -> assertEquals(family, WifiCauseFamily.of(word), word));
    }

    @Test
    void a_word_that_starts_with_deauth_is_deauth_whatever_follows() {
        // the Stop's wire word deauth:flow:<cause> (EndFacts.java:37); the live words deauth:user / deauth:portal (RadiusAttr.java:92)
        for (String word : List.of("deauth:flow:balance_exhausted", "deauth:user", "deauth:portal", "DEAUTH:ADMIN", "deauth")) {
            assertEquals("deauth", WifiCauseFamily.of(word), word);
        }
    }

    @Test
    void the_words_that_are_nobodys_family_are_other_and_so_is_an_unknown_word_or_none() {
        // the wire's refusals and the door's ends (WifiCause.java:15-39), the base's faults, the pre-admission words
        for (String word : List.of("GATE_BUSY", "GATE_FAILED", "NO_AUTH", "SILENT", "ROGUE_ROW", "NO_MAC", "NO_TENANT", "INFRA_MAC", "NO_GRANT",
                "ACCOUNT_NOT_FOUND", "NO_PURCHASE_NAMED", "INTERNAL_ERROR", "NO_ROUTE", "HUNG_MACHINE", "vip", "")) {
            assertEquals("other", WifiCauseFamily.of(word), word);
        }
        assertEquals("other", WifiCauseFamily.of(null));
    }

    @Test
    void the_match_does_not_regard_case_or_the_blanks_around_the_word() {
        assertEquals("idle", WifiCauseFamily.of("media_timeout"));
        assertEquals("volume", WifiCauseFamily.of(" Volume_Exhausted "));
        assertEquals("shutdown", WifiCauseFamily.of("system_shutdown"));
    }
}
