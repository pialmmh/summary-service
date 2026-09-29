package com.telcobright.summary.summarybeans.ad.internal;

import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.ad.model.AdCdr;
import com.telcobright.summary.summarybeans.ad.model.AdLeg;
import com.telcobright.summary.summarybeans.ad.model.AdSummary;

import java.math.BigDecimal;

/**
 * Builds one {@link AdSummary} row from ONE tier of ONE ad call (design §2.9): the tier's tenant and partner
 * are the row's, the call's campaign / rule / zone / site / app / media / outcome the rest of the key; the
 * bucket is the call's {@code StartTime} truncated to the bean's window. A call nothing admitted (no leg) is one
 * row on the ENTRY tenant with the advertiser when one was known, else partner 0. Key strings are clipped to
 * their column widths so a fresh build keys identically to its reloaded row.
 *
 * <p>The measures: {@code views} 1 always; {@code shown} when the ad reached the screen; {@code completed}
 * when the call cleared normally after being shown; {@code failed} when it did not clear normally;
 * {@code watchedsec} = the billsec; {@code chargedamount} = the tier's debit. {@code credited} (the free session
 * that followed) is not on the blob this round — it stays 0 until the payload carries it (open question).
 */
final class AdSummaryBuilder {

    static final String OUTCOME_UNKNOWN = "";

    private AdSummaryBuilder() {
    }

    static AdSummary build(AdCdr cdr, AdLeg leg, WindowSize window) {
        AdSummary s = new AdSummary();
        boolean tier = leg != null;
        s.tup_tenant = clip(orEmpty(tier && leg.tenant() != null ? leg.tenant() : cdr.tenant()), 100);
        s.tup_partnerid = tier && leg.partnerId() != null ? leg.partnerId() : nz(cdr.inPartnerId());
        s.tup_campaignid = nz(cdr.campaignId());
        s.tup_rulecode = clip(orEmpty(cdr.ruleCode()), 20);
        s.tup_zone = clip(orEmpty(cdr.zone()), 64);
        s.tup_site = clip(orEmpty(cdr.site()), 64);
        s.tup_app = clip(orEmpty(cdr.app()), 32);
        s.tup_mediakind = clip(orEmpty(cdr.mediaKind()), 16);
        s.tup_outcome = clip(orEmpty(cdr.outcome()), 32);
        s.tup_starttime = window.bucketStart(cdr.startTime());

        boolean shown = cdr.wasShown();
        boolean done = cdr.done();
        s.views = 1;
        s.shown = shown ? 1 : 0;
        s.completed = done && shown ? 1 : 0;
        s.credited = 0;
        s.failed = done ? 0 : 1;
        s.watchedsec = nz(cdr.durationSec());
        s.chargedamount = tier ? nzd(leg.billedAmount()) : BigDecimal.ZERO;
        return s;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static BigDecimal nzd(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String orEmpty(String v) {
        return v == null ? "" : v.trim();
    }

    private static String clip(String v, int maxLength) {
        return v.length() <= maxLength ? v : v.substring(0, maxLength);
    }
}
