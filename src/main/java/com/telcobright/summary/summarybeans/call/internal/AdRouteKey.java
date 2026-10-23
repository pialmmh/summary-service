package com.telcobright.summary.summarybeans.call.internal;

import java.util.Optional;

/**
 * The SUMMARY's key of an ad record's route (S18, routesphere {@code docs/architecture/ad-is-a-call.md} §5): the ad
 * service writes {@code outgoingRoute} as {@code <ruleId>/<app>/<zone>/<site>/<district>/<gw>/<msisdn>/<mac>} — the
 * matched rule's id and ALL seven rule-matching request params, each percent-encoded ({@code /} → {@code %2F},
 * {@code %} → {@code %25}), always eight positions (ad-sphere ARCH-0055). Keyed whole, the group-30 summary would
 * hold one row per DEVICE per window. The key is the route's first two parts — {@code <ruleId>/<app>} — exactly as
 * the row carries them (still encoded, so an app named {@code a/b} stays one part), and nothing else.
 *
 * <p>This is the ad service's own reader, COPIED with its citation and not re-derived (SS-0003 §2a: two rules for one
 * key drift the moment the route's shape changes again): ad-sphere main, ARCH-0057,
 * {@code src/main/java/com/telcobright/adsphere/flow/api/AdCallPreprocessor.java:147–212} — {@code routeKeyOf},
 * {@code routePositionsOf}, {@code ruleIdOf}. The service does not depend on ad-sphere's jar (an application, not a
 * library), so the four lines live here; a change of the route's shape is a change of BOTH, by that citation.
 */
final class AdRouteKey {

    /** The rule's id and the seven request params: the positions of a route of the fixed shape. */
    private static final int POSITIONS = 8;

    private AdRouteKey() {
    }

    /**
     * {@code <ruleId>/<app>} as the row carries it; EMPTY when the text is not a route of the fixed shape — not eight
     * positions, or a first part that is not a number — so a row written before ARCH-0055 (the zone alone) is never
     * read as a route: its whole text stays the key, as before, and nothing is invented.
     */
    static Optional<String> routeKeyOf(String route) {
        if (route == null) {
            return Optional.empty();
        }
        String[] parts = route.split("/", -1);
        if (parts.length != POSITIONS || !isRuleId(parts[0])) {
            return Optional.empty();
        }
        return Optional.of(parts[0] + "/" + parts[1]);
    }

    private static boolean isRuleId(String part) {
        try {
            Long.parseLong(part);
            return true;
        } catch (NumberFormatException notARuleId) {
            return false;
        }
    }
}
