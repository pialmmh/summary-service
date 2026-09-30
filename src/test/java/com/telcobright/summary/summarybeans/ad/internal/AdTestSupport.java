package com.telcobright.summary.summarybeans.ad.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.engine.internal.SummaryCache;
import com.telcobright.summary.engine.spi.MergeMode;
import com.telcobright.summary.summarybeans.ad.DailyAdSummary;
import com.telcobright.summary.summarybeans.ad.HourlyAdSummary;
import com.telcobright.summary.summarybeans.ad.model.AdSummary;
import com.telcobright.summary.summarybeans.call.internal.CdrBlobMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The ad tests' bed: the two window beans with no CDI, blob entries written EXACTLY as seed-callflow's
 * {@code AdCdrBlob.json} writes them (PascalCase keys, {@code Chargeables} one per tier), and a {@code rollup}
 * that runs the real build → bucket → merge path.
 */
final class AdTestSupport {

    static final String DAY_TABLE = "sum_ad_day_30";
    static final String HOUR_TABLE = "sum_ad_hr_30";
    private static final ObjectMapper MAPPER = CdrBlobMapper.create();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private AdTestSupport() {
    }

    static AdSummaryBean dailyBean() { return new DailyAdSummary(MAPPER, null); }

    static AdSummaryBean hourlyBean() { return new HourlyAdSummary(MAPPER, null); }

    static LocalDateTime at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute, 0);
    }

    /** Design §5 item 1: Unilever's view from dhaka-01, two tiers debited (res_44 0.50, btcl 0.40), shown, cleared normally. */
    static String twoTierEntry(LocalDateTime start, String sessionId) {
        return twoTierEntry(start, sessionId, "done", true, true, 15);
    }

    static String twoTierEntry(LocalDateTime start, String sessionId, String outcome, boolean answered, int billsec) {
        return twoTierEntry(start, sessionId, outcome, answered, answered, billsec);
    }

    /** {@code credited} = the credit child reached OPEN (ARCH-0001 ruling 5.5): the advertiser's view was claimed. */
    static String twoTierEntry(LocalDateTime start, String sessionId, String outcome, boolean answered, boolean credited, int billsec) {
        String s = TS.format(start);
        String cause = "done".equals(outcome) ? "NORMAL_CLEARING" : "NOT_SHOWN";
        return "{\"Cdr\":{\"SwitchId\":0,\"SessionId\":\"" + sessionId + "\",\"Tenant\":\"btcl\",\"InPartnerId\":61,\"OutPartnerId\":9,"
                + "\"IncomingRoute\":\"lux-soap\",\"OutgoingRoute\":\"dhaka-01\",\"StartTime\":\"" + s + "\","
                + (answered ? "\"ConnectTime\":\"" + s + "\"," : "")
                + "\"EndTime\":\"" + s + "\",\"DurationSec\":" + billsec + ",\"ChargingStatus\":1,\"NERSuccess\":" + (answered ? 1 : 0) + ","
                + "\"MatchedPrefixCustomer\":\"1001\",\"OriginatingCallingNumber\":\"aa:bb\",\"OriginatingCalledNumber\":\"1001\",\"ServiceGroup\":30,"
                + "\"CampaignId\":5,\"CampaignName\":\"lux-soap\",\"ContentId\":\"lux-30\",\"ContentPartnerId\":61,\"RuleCode\":\"1001\","
                + "\"Zone\":\"dhaka-01\",\"Site\":\"dhaka-site-1\",\"District\":\"dhaka\",\"Gw\":\"bras-1\",\"App\":\"wifi\",\"MediaKind\":\"video\","
                + "\"RequiredSeconds\":15,\"Answered\":" + answered + ",\"Credited\":" + credited + ",\"Outcome\":\"" + outcome + "\",\"HangupCause\":\"" + cause + "\",\"Fallback\":false,"
                + "\"SomethingOfTomorrow\":{\"x\":1}},"
                + "\"Chargeables\":["
                + "{\"servicegroup\":30,\"servicefamily\":30,\"assignedDirection\":1,\"ProductId\":5,\"idBilledUom\":\"BDT\",\"Prefix\":\"1001\","
                + "\"transactionTime\":\"" + s + "\",\"unitPriceOrCharge\":0.50,\"BilledAmount\":0.50,\"Quantity\":1,\"Tenant\":\"res_44\",\"PartnerId\":61,"
                + "\"LevelIndex\":0,\"ResellerHierarchy\":\"btcl > res_44\",\"Reference\":\"" + sessionId + "#L0\"},"
                + "{\"servicegroup\":30,\"servicefamily\":30,\"assignedDirection\":1,\"ProductId\":5,\"idBilledUom\":\"BDT\",\"Prefix\":\"1001\","
                + "\"transactionTime\":\"" + s + "\",\"unitPriceOrCharge\":0.40,\"BilledAmount\":0.40,\"Quantity\":1,\"Tenant\":\"btcl\",\"PartnerId\":44,"
                + "\"LevelIndex\":1,\"ResellerHierarchy\":\"btcl\",\"Reference\":\"" + sessionId + "#L1\"}"
                + "]}";
    }

    /** Design §5 item 4: no rule matched — FAILED NO_RULE, no tier, the entry tenant btcl, no advertiser known. */
    static String refusedEntry(LocalDateTime start, String sessionId) {
        String s = TS.format(start);
        return "{\"Cdr\":{\"SwitchId\":0,\"SessionId\":\"" + sessionId + "\",\"Tenant\":\"btcl\",\"InPartnerId\":null,\"OutPartnerId\":9,"
                + "\"IncomingRoute\":null,\"OutgoingRoute\":\"sylhet-09\",\"StartTime\":\"" + s + "\",\"EndTime\":\"" + s + "\",\"DurationSec\":0,"
                + "\"ChargingStatus\":0,\"NERSuccess\":0,\"MatchedPrefixCustomer\":null,\"OriginatingCallingNumber\":\"cc:dd\",\"OriginatingCalledNumber\":null,"
                + "\"ServiceGroup\":30,\"CampaignId\":null,\"CampaignName\":null,\"ContentId\":null,\"ContentPartnerId\":null,\"RuleCode\":null,"
                + "\"Zone\":\"sylhet-09\",\"Site\":null,\"District\":null,\"Gw\":\"bras-1\",\"App\":\"wifi\",\"MediaKind\":null,\"RequiredSeconds\":0,"
                + "\"Answered\":false,\"Credited\":false,\"Outcome\":\"failed\",\"HangupCause\":\"NO_RULE\",\"Fallback\":false},\"Chargeables\":[]}";
    }

    static byte[] batchJson(List<String> entries) {
        return ("[" + String.join(",", entries) + "]").getBytes(StandardCharsets.UTF_8);
    }

    /** Build {@code entries} through {@code bean}, fold them into a cache, and return the aggregated window rows. */
    static Collection<AdSummary> rollup(AdSummaryBean bean, List<String> entries) {
        SummaryCache<AdSummary> cache = new SummaryCache<>(bean.table(), AdSummary.INSERT_COLUMNS, AdSummary.BUCKET_COLUMN);
        for (AdSummary built : bean.buildBatch(batchJson(entries))) {
            cache.merge(built, MergeMode.ADD);
        }
        return cache.rows();
    }

    static List<String> series(LocalDateTime from, int stepMinutes, int count) {
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(twoTierEntry(from.plusMinutes((long) i * stepMinutes), "ad-" + i));
        return out;
    }

    static long totalViews(Collection<AdSummary> rows) {
        return rows.stream().mapToLong(r -> r.views).sum();
    }

    static BigDecimal totalCharged(Collection<AdSummary> rows) {
        return rows.stream().map(r -> r.chargedamount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
