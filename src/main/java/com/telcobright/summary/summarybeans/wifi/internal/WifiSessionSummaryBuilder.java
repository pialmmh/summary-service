package com.telcobright.summary.summarybeans.wifi.internal;

import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.call.model.Cdr;
import com.telcobright.summary.summarybeans.call.model.Chargeable;
import com.telcobright.summary.summarybeans.wifi.model.WifiSessionSummary;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Builds one {@link WifiSessionSummary} delta from ONE WiFi session's record — the {@code cdr} row of service group
 * 101 and the ONE customer chargeable billing-core built from the switch's settlement (the pair the call bean's
 * 101 branch reads, {@code CallSummaryBuilder.stampWifiSession}).
 *
 * <p><b>The key, every part a COLUMN of the record</b> (the WiFi architect's rule a; billing-core's dictionary
 * {@code WifiColumns.java:82-104}): {@code tup_gw} = {@code InMgwId} — the BRAS id, the meta {@code gw} (SS-0007, the
 * owner's R4; {@code ''} when the record names none) — {@code tup_site} = {@code InTrunkAdditionalInfo}, {@code tup_zone} =
 * {@code OutTrunkAdditionalInfo}, {@code tup_granttier} = {@code MatchedPrefixY}, {@code tup_category} =
 * {@code Category}, {@code tup_packagecode} = {@code MatchedPrefixCustomer}; {@code tup_switchid} and
 * {@code tup_inpartnerid} as every call summary; {@code tup_customercurrency} = the chargeable's unit, stamped as
 * the 101 call bean stamps it — behind the voice's {@code ChargingStatus} early return ({@code CallSummaryBuilder
 * .stampWifiSession:152-162}): a session of 0 seconds has no unit and no cost; {@code tup_causefamily} =
 * {@link WifiCauseFamily#of} of {@code HangupCause}; the bucket = {@code StartTime} (the tenant's wall clock) cut
 * to the window. Key texts are cut to their column widths so a fresh build keys as its reloaded row.
 *
 * <p><b>The sums:</b> {@code sessions} 1; {@code sessions_connected} 1 when {@code DurationSec} is above 0;
 * {@code total_duration_sec} = the seconds, whole; {@code bytes_up} / {@code bytes_dn} = {@code XAmount} /
 * {@code YAmount} (exact integers; absent = 0); {@code customercost} = the chargeable's billed amount when charged.
 * A session of 0 seconds counts in {@code sessions}, not in {@code sessions_connected}, and adds 0 to every sum.
 * The average is derived by the entity, never stamped from a record.
 *
 * <p>A record whose {@code ValidFlag} is 0 is summed like any other (rule b): nothing here reads the flag.
 */
final class WifiSessionSummaryBuilder {

    // ---- the widths of the key's texts: ONE number each, used by the cut below AND by the table (SumWifiSessionDdl) ----
    // the convention of the ad bean (AdSummaryBuilder.*_WIDTH): a value is never wider than its column, and each width is
    // its source's full width — a zone and a site are 64 there too
    static final int SITE_WIDTH = 64;
    static final int ZONE_WIDTH = 64;
    /** {@code tup_gw}: the BRAS id (the gateway id rule allows 32) — ONE number with the destination sums' {@code tup_gw}. */
    static final int GW_WIDTH = WifiDestinationSummaryBuilder.GW_WIDTH;
    /** {@code tup_granttier}: one of the gateway's four words (free, paid, admin, test). */
    static final int GRANTTIER_WIDTH = 16;
    /** {@code tup_packagecode}: the package's code at its source (the rate prefix the base wrote). */
    static final int PACKAGECODE_WIDTH = 64;
    /** {@code tup_customercurrency}: the unit, as the voice tables hold it (16). */
    static final int CURRENCY_WIDTH = 16;
    /** {@code tup_causefamily}: one of this service's own seven words. */
    static final int CAUSEFAMILY_WIDTH = 16;

    private WifiSessionSummaryBuilder() {
    }

    static WifiSessionSummary build(Cdr cdr, Chargeable charge, WindowSize window) {
        WifiSessionSummary s = new WifiSessionSummary();
        stampKeyFromTheColumns(s, cdr);
        s.tup_starttime = window.bucketStart(cdr.startTime());
        stampCharge(s, cdr, charge);
        stampSums(s, cdr);
        s.recomputeAverage();
        return s;
    }

    private static void stampKeyFromTheColumns(WifiSessionSummary s, Cdr cdr) {
        s.tup_switchid = cdr.switchId();
        s.tup_inpartnerid = cdr.inPartnerId() == null ? 0 : cdr.inPartnerId();
        s.tup_gw = clip(orEmpty(cdr.inMgwId()), GW_WIDTH);
        s.tup_site = clip(orEmpty(cdr.inTrunkAdditionalInfo()), SITE_WIDTH);
        s.tup_zone = clip(orEmpty(cdr.outTrunkAdditionalInfo()), ZONE_WIDTH);
        s.tup_granttier = clip(orEmpty(cdr.matchedPrefixY()), GRANTTIER_WIDTH);
        s.tup_category = cdr.category() == null ? 0 : cdr.category();
        s.tup_packagecode = clip(orEmpty(cdr.matchedPrefixCustomer()), PACKAGECODE_WIDTH);
        s.tup_causefamily = clip(WifiCauseFamily.of(cdr.hangupCause()), CAUSEFAMILY_WIDTH);
    }

    /** The 101 call bean's rule: the unit and the cost only for a charged session (ChargingStatus 1, the voice rule). */
    private static void stampCharge(WifiSessionSummary s, Cdr cdr, Chargeable charge) {
        boolean charged = cdr.chargingStatus() != null && cdr.chargingStatus() == 1;
        if (!charged) {
            return;                                      // the voice's early return: no unit, no cost
        }
        s.tup_customercurrency = clip(orEmpty(charge.idBilledUom()), CURRENCY_WIDTH);
        s.customercost = nz(charge.billedAmount());
    }

    private static void stampSums(WifiSessionSummary s, Cdr cdr) {
        long seconds = whole(cdr.durationSec());
        s.sessions = 1;
        s.sessions_connected = seconds > 0 ? 1 : 0;
        s.total_duration_sec = seconds;
        s.bytes_up = whole(cdr.xAmount());
        s.bytes_dn = whole(cdr.yAmount());
    }

    /** A numeric column as a whole number (the bytes are exact integers; the seconds are whole for a session). */
    private static long whole(BigDecimal value) {
        return value == null ? 0 : value.setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String orEmpty(String v) {
        return v == null ? "" : v.trim();
    }

    private static String clip(String v, int maxLength) {
        return v.length() <= maxLength ? v : v.substring(0, maxLength);
    }
}
