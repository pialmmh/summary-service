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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The ad tests' bed: the two window beans with no CDI, and blob entries written the way billing-core writes an ad
 * view into ONE tier's outbox (entity {@code cdr}, blob v2): its own field names ({@code cdr.java},
 * {@code acc_chargeable.java} — Jackson writes public fields by their declared names), a date as the array its
 * mapper emits ({@code [2026,9,29,10,0,5]}, the seconds left out when 0), nulls omitted, the view's facts as ONE
 * JSON object inside the STRING {@code AdditionalMetaData}, and ONE customer chargeable per record.
 *
 * <p>The standing scenario is the design's: Unilever's view of {@code lux-soap} in zone {@code dhaka-01}. The
 * reseller's tier {@code res_44} records it with the advertiser (partner 61) paying 0.50; the operator's tier
 * {@code btcl} records it with the reseller (partner 44) paying 0.40. Each tier's record sits in that tier's own
 * outbox, so a test names the tier it drains.
 */
public final class AdTestSupport {

    public static final String DAY_TABLE = "sum_ad_day_30";
    public static final String HOUR_TABLE = "sum_ad_hr_30";
    /** The reseller's tier schema: the advertiser's record. */
    public static final String LEAF = "res_44";
    /** The operator's tier schema: the reseller's record. */
    public static final String ROOT = "btcl";
    private static final ObjectMapper MAPPER = CdrBlobMapper.create();

    private AdTestSupport() {
    }

    public static AdSummaryBean dailyBean() { return new DailyAdSummary(MAPPER, null); }

    public static AdSummaryBean hourlyBean() { return new HourlyAdSummary(MAPPER, null); }

    public static LocalDateTime at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute, 0);
    }

    /** The advertiser's record of the standing view, as {@code res_44}'s outbox carries it: shown, cleared normally, 0.50. */
    public static View leafView(LocalDateTime start) {
        return new View(start).partner(61).charge("0.50");
    }

    /** The reseller's record of the SAME view, as {@code btcl}'s outbox carries it: partner 44, 0.40. */
    public static View rootView(LocalDateTime start) {
        return new View(start).partner(44).charge("0.40");
    }

    /**
     * A view refused before anyone was admitted (no rule matched): ONE record, on the entry tenant, no answer
     * time, 0 seconds, its cause, a chargeable of zero IN BDT (ruled: a refused view is money 0), the tenant's own
     * operator partner as the payer (B4: a record always names one) — and the ZONE as the called number, as the
     * switch writes it when no rule has a code.
     */
    public static View refusedView(LocalDateTime start) {
        return new View(start).partner(1).cause("NO_RULE").neverShown().watched("0").charge("0").media(null)
                .rule("sylhet-09").meta("{\"app\":\"wifi\",\"zone\":\"sylhet-09\",\"completed\":false,\"credited\":false,\"fallback\":false}");
    }

    /** One tier's record of one ad view, as billing-core writes the entry. Every setter returns {@code this}. */
    public static final class View {
        private final LocalDateTime start;
        private Integer serviceGroup = 30;
        private Integer partner = 61;
        private String cause = "NORMAL_CLEARING";
        private boolean shown = true;
        private String watched = "15";
        private String charge = "0.50";
        private String uom = "BDT";
        private String rule = "1001";
        private String media = "video";
        private Object campaign = 5;
        private String zone = "dhaka-01";
        private String site = "dhaka-site-1";
        private String app = "wifi";
        private boolean completed = true;
        private boolean credited = true;
        private String rawMeta;
        private boolean metaAbsent;
        private boolean noChargeable;
        private boolean isoDates;

        private View(LocalDateTime start) {
            this.start = start;
        }

        public View serviceGroup(Integer group) { this.serviceGroup = group; return this; }
        public View partner(Integer id) { this.partner = id; return this; }
        public View cause(String word) { this.cause = word; return this; }
        public View neverShown() { this.shown = false; return this; }
        public View watched(String seconds) { this.watched = seconds; return this; }
        public View charge(String amount) { this.charge = amount; return this; }
        /** The unit the tier paid in: {@code BDT} is money; a package's unit ({@code TF_s}, {@code OTH_ea}) is units; null = none named. */
        public View uom(String unit) { this.uom = unit; return this; }
        public View rule(String code) { this.rule = code; return this; }
        public View media(String kind) { this.media = kind; return this; }
        public View campaign(Object id) { this.campaign = id; return this; }
        public View zone(String name) { this.zone = name; return this; }
        public View site(String name) { this.site = name; return this; }
        public View app(String name) { this.app = name; return this; }
        public View completed(boolean yes) { this.completed = yes; return this; }
        public View credited(boolean yes) { this.credited = yes; return this; }
        /** The meta data text as it is, whatever it is — a short object, a broken one, not an object at all. */
        public View meta(String text) { this.rawMeta = text; return this; }
        public View noMeta() { this.metaAbsent = true; return this; }
        public View noChargeable() { this.noChargeable = true; return this; }
        /** Dates as ISO text instead of billing-core's arrays (both must decode). */
        public View isoDates() { this.isoDates = true; return this; }

        /** Admitted, charged, never shown (decision D13 / the owner's "no return policy"): the charge stays. */
        public View admittedNeverShown() {
            return cause("NOT_SHOWN").neverShown().watched("0").completed(false).credited(false);
        }

        public String json() {
            StringBuilder cdr = new StringBuilder("{\"SwitchId\":0,\"IdCall\":0,\"SequenceNumber\":1790961243000017,\"FileName\":\"kafka:cdr\"");
            if (serviceGroup != null) cdr.append(",\"ServiceGroup\":").append(serviceGroup);
            cdr.append(",\"IncomingRoute\":\"lux-soap\",\"OriginatingIP\":\"10.20.0.1\"");
            if (rule != null) cdr.append(",\"OriginatingCalledNumber\":").append(quoted(rule)).append(",\"TerminatingCalledNumber\":").append(quoted(rule));
            cdr.append(",\"OriginatingCallingNumber\":\"8801711000001\",\"TerminatingCallingNumber\":\"8801711000001\",\"PrePaid\":1");
            cdr.append(",\"DurationSec\":").append(watched);
            cdr.append(",\"EndTime\":").append(date(start.plusSeconds(20)));
            if (shown) cdr.append(",\"ConnectTime\":").append(date(start.plusSeconds(1))).append(",\"AnswerTime\":").append(date(start.plusSeconds(1)));
            cdr.append(",\"ChargingStatus\":").append(shown ? 1 : 0);          // group 30: 1 = shown (billing-core BC-0002)
            cdr.append(",\"OutgoingRoute\":").append(quoted(zone == null ? "" : zone)).append(",\"TerminatingIP\":\"10.10.188.40\"");
            cdr.append(",\"StartTime\":").append(date(start));
            if (partner != null) cdr.append(",\"InPartnerId\":").append(partner);
            cdr.append(",\"CustomerRate\":").append(charge).append(",\"OutPartnerId\":9,\"MatchedPrefixCustomer\":\"10\"");
            cdr.append(",\"InPartnerCost\":").append(charge);
            if (media != null) cdr.append(",\"Codec\":").append(quoted(media));
            String meta = metaText();
            if (meta != null) cdr.append(",\"AdditionalMetaData\":").append(quoted(meta));
            cdr.append(",\"SignalingStartTime\":").append(date(start));
            cdr.append(",\"ResellerHierarchy\":\"btcl > res_44\",\"ChannelCallUuid\":\"2f6c1c1e\"");
            if (cause != null) cdr.append(",\"HangupCause\":").append(quoted(cause));
            if (uom != null) cdr.append(",\"InPartnerUom\":").append(quoted(uom));
            cdr.append(",\"IdPackageAccount\":3061,\"PackageAmount\":0}");

            String chargeable = "{\"id\":0,\"idEvent\":0,\"transactionTime\":" + date(start) + ",\"assignedDirection\":1,\"glAccountId\":0,"
                    + "\"servicegroup\":30,\"servicefamily\":30,\"ProductId\":0," + (uom == null ? "" : "\"idBilledUom\":" + quoted(uom) + ",")
                    + "\"BilledAmount\":" + charge + ",\"Quantity\":" + watched + ",\"unitPriceOrCharge\":" + charge
                    + ",\"Prefix\":\"10\",\"RateId\":0,\"idBillingrule\":0}";
            return "{\"Cdr\":" + cdr + ",\"Chargeables\":[" + (noChargeable ? "" : chargeable) + "]}";
        }

        /** The view's facts as the switch's {@code fillCdr} writes them into every tier's record. */
        private String metaText() {
            if (metaAbsent) return null;
            if (rawMeta != null) return rawMeta;
            StringBuilder m = new StringBuilder("{\"levelIndex\":0,\"partnerName\":\"Unilever\"");
            if (campaign != null) m.append(",\"campaignId\":").append(campaign instanceof String text ? quoted(text) : campaign);
            m.append(",\"campaignName\":\"lux-soap\",\"contentId\":\"lux-30\"");
            if (app != null) m.append(",\"app\":").append(quoted(app));
            if (zone != null) m.append(",\"zone\":").append(quoted(zone));
            if (site != null) m.append(",\"site\":").append(quoted(site));
            m.append(",\"requiredSeconds\":15,\"completed\":").append(completed).append(",\"credited\":").append(credited).append(",\"fallback\":false}");
            return m.toString();
        }

        private String date(LocalDateTime t) {
            if (isoDates) return quoted(t.toString());
            String array = "[" + t.getYear() + "," + t.getMonthValue() + "," + t.getDayOfMonth() + "," + t.getHour() + "," + t.getMinute();
            return array + (t.getSecond() == 0 ? "" : "," + t.getSecond()) + "]";
        }
    }

    /** A JSON string literal of {@code text}: quotes and backslashes escaped (the meta data is JSON inside a JSON string). */
    static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static byte[] batchJson(List<String> entries) {
        return ("[" + String.join(",", entries) + "]").getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] batchOf(View... views) {
        List<String> entries = new ArrayList<>(views.length);
        for (View view : views) entries.add(view.json());
        return batchJson(entries);
    }

    /** Build {@code views} through {@code bean} for {@code tier}, fold them into a cache, and return the window rows. */
    public static Collection<AdSummary> rollup(AdSummaryBean bean, String tier, List<View> views) {
        SummaryCache<AdSummary> cache = new SummaryCache<>(bean.table(), AdSummary.INSERT_COLUMNS, AdSummary.BUCKET_COLUMN);
        for (AdSummary built : bean.buildBatch(batchOf(views.toArray(View[]::new)), tier)) {
            cache.merge(built, MergeMode.ADD);
        }
        return cache.rows();
    }

    /** {@code count} views of the leaf tier, one every {@code stepMinutes}. */
    public static List<View> series(LocalDateTime from, int stepMinutes, int count) {
        List<View> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(leafView(from.plusMinutes((long) i * stepMinutes)));
        return out;
    }

    public static long totalViews(Collection<AdSummary> rows) {
        return rows.stream().mapToLong(r -> r.views).sum();
    }

    public static BigDecimal totalCharged(Collection<AdSummary> rows) {
        return rows.stream().map(r -> r.chargedamount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static BigDecimal totalUnits(Collection<AdSummary> rows) {
        return rows.stream().map(r -> r.chargedunits).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
