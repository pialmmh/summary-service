package com.telcobright.summary.summarybeans.ad;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryKind;
import com.telcobright.summary.summarybeans.ad.internal.AdSummaryBean;
import com.telcobright.summary.summarybeans.ad.model.AdCallCdr;

import java.util.Set;

/**
 * The <b>ad</b> kind — an ad view is a Call (ad-is-a-call §4.1, §5; SS-0008). Its word is {@code ad}; its service group
 * 30; its entity {@code cdr} (the call's own stream: billing-core writes each tier's view as a {@code cdr} row of group 30).
 *
 * <p>Its bean set, all in this package:
 * <ul>
 *   <li>the ad summary {@code sum_ad_<window>_30}: the window classes {@link DailyAdSummary} / {@link HourlyAdSummary};
 *       generator {@code internal/AdSummaryGenerator}, builder {@code internal/AdSummaryBuilder}, table {@code internal/SumAdDdl};</li>
 *   <li>the view as a call {@code sum_voice_<window>_30}: a call-shaped bean of group 30 (the profile's
 *       {@code service-group: 30}), the call kind's code — it is this kind's because it reads this kind's group.</li>
 * </ul>
 */
public final class AdKind implements SummaryKind {

    public static final String WORD = "ad";

    @Override
    public String word() {
        return WORD;
    }

    @Override
    public Set<Integer> serviceGroups() {
        return Set.of(AdCallCdr.SERVICE_GROUP);
    }

    @Override
    public Set<String> entityTypes() {
        return Set.of(AdSummaryBean.ENTITY_TYPE);
    }

    @Override
    public Set<String> catalogBeans() {
        return Set.of(DailyAdSummary.NAME, HourlyAdSummary.NAME);
    }

    @Override
    public boolean owns(SummaryBean<?> bean) {
        return bean instanceof AdSummaryBean;
    }
}
