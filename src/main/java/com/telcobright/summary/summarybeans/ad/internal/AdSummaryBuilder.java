package com.telcobright.summary.summarybeans.ad.internal;

import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.ad.model.AdCallCdr;
import com.telcobright.summary.summarybeans.ad.model.AdSummary;
import com.telcobright.summary.summarybeans.ad.model.AdView;
import com.telcobright.summary.summarybeans.ad.model.AdViewFacts;
import com.telcobright.summary.summarybeans.call.model.Chargeable;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Builds one {@link AdSummary} row from ONE ad view as ONE tier recorded it (ad-is-a-call §4.1, §5; brief S1).
 *
 * <p>The key: the tier (the schema's own name — one pair of tables per tier schema, so {@code tup_tenant} is
 * always it), the payer ({@code InPartnerId}), the campaign, the content shown ({@code contentId} of the meta data;
 * empty when the record carries none), the rule's code ({@code OriginatingCalledNumber}),
 * the zone, the site, the app, the media kind ({@code Codec}), the outcome ({@code done} when {@code HangupCause}
 * is {@code NORMAL_CLEARING}, else {@code failed}), and the bucket: the record's {@code StartTime} — the tenant's
 * wall clock — cut to the bean's window. Key strings are cut to their column widths so a fresh build keys
 * identically to its reloaded row.
 *
 * <p>The measures: {@code views} 1 always; {@code shown} when the record has an answer time; {@code completed}
 * and {@code credited} as the view's facts say; {@code failed} when the cause is not a normal clearing;
 * {@code watchedsec} = {@code DurationSec}, to the whole second (half up — the column is a whole number).
 *
 * <p>The charge is the customer chargeable's billed amount, and it is MONEY or UNITS by the leg's own unit
 * ({@code idBilledUom}): a leg in {@code BDT} counts into {@code chargedamount}; a leg in any other unit — the
 * unit of a package the tier paid from — counts into {@code chargedunits}. The two are never added. A leg that
 * names no unit is not taken for money. An entry without a chargeable counts the view with no charge.
 */
final class AdSummaryBuilder {

    static final String OUTCOME_DONE = "done";
    static final String OUTCOME_FAILED = "failed";
    /**
     * The width of {@code tup_app}: 64, the width of the app's name at its source (ad-sphere's {@code ad_caller.app},
     * its start road refuses a longer one). It was 32: two apps sharing their first 32 characters merged into one row.
     */
    static final int APP_WIDTH = 64;
    /** The width of {@code tup_contentid}: 64, the width of a content's id at its source (ad-sphere's {@code ad_content.id}). */
    static final int CONTENT_WIDTH = 64;
    /** The unit of money on a chargeable; every other unit is a package's. */
    static final String MONEY_UOM = "BDT";

    private AdSummaryBuilder() {
    }

    static AdSummary build(AdView view, WindowSize window) {
        AdCallCdr cdr = view.cdr();
        AdViewFacts facts = view.facts();
        Chargeable charge = view.customerLeg();
        boolean done = cdr.done();

        AdSummary s = new AdSummary();
        s.tup_tenant = clip(orEmpty(view.tier()), 100);
        s.tup_partnerid = cdr.inPartnerId() == null ? 0 : cdr.inPartnerId();
        s.tup_campaignid = facts.campaignId();
        s.tup_contentid = clip(orEmpty(facts.contentId()), CONTENT_WIDTH);
        s.tup_rulecode = clip(orEmpty(cdr.originatingCalledNumber()), 20);
        s.tup_zone = clip(orEmpty(facts.zone()), 64);
        s.tup_site = clip(orEmpty(facts.site()), 64);
        s.tup_app = clip(orEmpty(facts.app()), APP_WIDTH);
        s.tup_mediakind = clip(orEmpty(cdr.codec()), 16);
        s.tup_outcome = done ? OUTCOME_DONE : OUTCOME_FAILED;
        s.tup_starttime = window.bucketStart(cdr.startTime());

        s.views = 1;
        s.shown = cdr.wasShown() ? 1 : 0;
        s.completed = facts.completed() ? 1 : 0;
        s.credited = facts.credited() ? 1 : 0;
        s.failed = done ? 0 : 1;
        s.watchedsec = wholeSeconds(cdr.durationSec());
        BigDecimal billed = charge == null || charge.billedAmount() == null ? BigDecimal.ZERO : charge.billedAmount();
        boolean inMoney = charge != null && isMoney(charge.idBilledUom());
        s.chargedamount = inMoney ? billed : BigDecimal.ZERO;
        s.chargedunits = inMoney ? BigDecimal.ZERO : billed;
        return s;
    }

    private static boolean isMoney(String uom) {
        return uom != null && MONEY_UOM.equalsIgnoreCase(uom.trim());
    }

    private static long wholeSeconds(BigDecimal durationSec) {
        return durationSec == null ? 0 : durationSec.setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private static String orEmpty(String v) {
        return v == null ? "" : v.trim();
    }

    private static String clip(String v, int maxLength) {
        return v.length() <= maxLength ? v : v.substring(0, maxLength);
    }
}
