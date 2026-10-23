package com.telcobright.summary.summarybeans.call;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryKind;
import com.telcobright.summary.summarybeans.call.chargeable.DailyChargeableSummary;
import com.telcobright.summary.summarybeans.call.chargeable.HourlyChargeableSummary;
import com.telcobright.summary.summarybeans.call.chargeable.internal.ChargeableSummaryBean;

import java.util.Set;

/**
 * The <b>call</b> kind — the voice call, the model every other kind follows (SS-0008; the owner's R13). Its word is
 * {@code call}; it is the kind a tenant has when its profile names none ({@code summary.kinds} absent = {@code [call]}).
 *
 * <p>Its service groups: 10 ({@code SgDomOffnetOut}) and 11 ({@code SgDomOffnetIn}) — and every group no kind names,
 * as the voice deployment summed every group before kinds existed. Its entity: {@code cdr}.
 *
 * <p>Its bean set, all in this package:
 * <ul>
 *   <li>the call summary {@code sum_voice_<window>_<suffix>}: the window classes {@link DailySummary} /
 *       {@link HourlySummary}, extra instances by {@link CallSummaries#forWindow} (§12g); generator
 *       {@code internal/CallSummaryGenerator}, builder {@code internal/CallSummaryBuilder} (and {@code beans/DailySummaryBuilder},
 *       {@code beans/HourlySummaryBuilder}), table {@code internal/SumVoiceDdl}. A call-shaped bean is the kind of the group it
 *       reads: of 10 or 11 the call's, of 30 the ad's, of 101 the WiFi's (the same code, each kind's view);</li>
 *   <li>the chargeable roll-up {@code sum_chargeable_<window>}: {@link DailyChargeableSummary} / {@link HourlyChargeableSummary}
 *       ({@code chargeable/}); generator {@code ChargeableSummaryGenerator}, builder {@code ChargeableSummaryBuilder} (and
 *       {@code beans/Daily|HourlyChargeableSummaryBuilder}), table {@code SumChargeableDdl}. It reads every leg of every
 *       record — the one bean whose input crosses the kinds: it sums the legs of the kinds the tenant enabled.</li>
 * </ul>
 */
public final class CallKind implements SummaryKind {

    public static final String WORD = "call";
    /** {@code SgDomOffnetOut}. */
    public static final int SERVICE_GROUP_DOMESTIC_OUT = 10;
    /** {@code SgDomOffnetIn}. */
    public static final int SERVICE_GROUP_DOMESTIC_IN = 11;

    @Override
    public String word() {
        return WORD;
    }

    @Override
    public Set<Integer> serviceGroups() {
        return Set.of(SERVICE_GROUP_DOMESTIC_OUT, SERVICE_GROUP_DOMESTIC_IN);
    }

    @Override
    public boolean takesEveryUnnamedServiceGroup() {
        return true;
    }

    @Override
    public Set<String> entityTypes() {
        return Set.of("cdr");
    }

    @Override
    public Set<String> catalogBeans() {
        return Set.of(DailySummary.NAME, HourlySummary.NAME, DailyChargeableSummary.NAME, HourlyChargeableSummary.NAME);
    }

    /** The chargeable roll-up; a call-shaped bean is the kind of its group, not decided here. */
    @Override
    public boolean owns(SummaryBean<?> bean) {
        return bean instanceof ChargeableSummaryBean;
    }
}
