package com.telcobright.summary.testkit;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.engine.internal.SummaryCache;
import com.telcobright.summary.engine.spi.MergeMode;
import com.telcobright.summary.summarybeans.call.CallSummaries;
import com.telcobright.summary.summarybeans.call.model.CallSummary;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The WiFi tests' bed (ARCH-0077-S): the two call beans of service group 101 with no CDI, and blob entries written
 * the way billing-core writes a domestic WiFi SESSION into a tier's outbox (BC-0007; the contract page's §2a) — the
 * voice {@code cdr} row with {@code ServiceGroup} 101 and ONE customer chargeable from the switch's settlement
 * ({@code SfPreRated}): {@code BilledAmount} = the package minutes with {@code idBilledUom} {@code TF_min} when the
 * tier paid from a bucket, else the money in {@code BDT}; {@code Quantity} = the seconds in {@code TF_s};
 * {@code unitPriceOrCharge} = the reference rate; no prefix, {@code RateId} 0. Billing's mapper writes public fields
 * by their declared names, a date as an array, nulls left out — as {@link
 * com.telcobright.summary.summarybeans.ad.internal.AdTestSupport} has it for a view.
 *
 * <p>The standing session is the contract page's sample: subscriber 8801711000002 (partner 7 of {@code btcl}) on
 * segment {@code W1} through {@code site-mirpur-10}, 30 minutes paid with 30 {@code TF_min} from bucket 5101 at a
 * 0.10 reference rate. The wire sends no out-partner, no supplier fields, no outgoing route and no prefix.
 */
public final class WifiSessionTestSupport {

    public static final int SERVICE_GROUP = 101;
    public static final String TABLE_SUFFIX = "101";
    public static final String DAY_TABLE = "sum_voice_day_101";
    public static final String HOUR_TABLE = "sum_voice_hr_101";
    public static final String DAILY_BEAN = "dailyCallSummarySg101";
    public static final String HOURLY_BEAN = "hourlyCallSummarySg101";

    private static long nextId = 1;

    private WifiSessionTestSupport() {
    }

    /** The config-instantiated daily bean of group 101, as the bed profile names it. */
    public static SummaryBean<CallSummary> daily101() {
        return CallSummaries.forWindow(DAILY_BEAN, "daily", TABLE_SUFFIX, SERVICE_GROUP, null);
    }

    public static SummaryBean<CallSummary> hourly101() {
        return CallSummaries.forWindow(HOURLY_BEAN, "hourly", TABLE_SUFFIX, SERVICE_GROUP, null);
    }

    public static LocalDateTime at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute, 0);
    }

    /** The standing session: 30 minutes, 30 {@code TF_min} from bucket 5101, partner 7, segment W1, site-mirpur-10. */
    public static Session session(LocalDateTime start) {
        return new Session(start);
    }

    /** A session the tier paid in money (no bucket): 3.00 BDT, no package units. */
    public static Session sessionPaidInMoney(LocalDateTime start) {
        return new Session(start).paidInMoney("3.00");
    }

    /** A session that carried no traffic: 0 seconds, its answer time still on the record, nothing taken from the bucket. */
    public static Session sessionOfZeroSeconds(LocalDateTime start) {
        return new Session(start).ofZeroSeconds();
    }

    /** One tier's record of one session, as billing-core writes the entry. Every setter returns {@code this}. */
    public static final class Session {
        private final LocalDateTime start;
        private final long id = nextId++;
        private int serviceGroup = SERVICE_GROUP;
        private Integer partner = 7;
        private String segment = "W1";
        private String msisdn = "8801711000002";
        private String site = "site-mirpur-10";
        private String route;                    // the record's OutgoingRoute; null = the wire sent none (a session's usual case)
        private String seconds = "1800";
        private boolean answered = true;
        private int chargingStatus = 1;          // the VOICE rule: duration > 0
        private String uom = "TF_min";
        private String packageAmount = "30";     // the minutes taken from the bucket
        private String money = "0";              // InPartnerCost: the money, 0 when the tier paid from a bucket
        private String rate = "0.10";            // the reference rate — never applied
        private Long packageAccount = 5101L;
        private String cause = "NORMAL_CLEARING";
        private String prefix;                   // MatchedPrefixCustomer: a session has none
        private String legacyPackageAmount;      // AdditionalSystemCodes — SG10's package stamp, when a row carries it
        private String legacyPackageId;          // AdditionalPartyNumber — SG10's idpackage stamp, when a row carries it

        private Session(LocalDateTime start) {
            this.start = start;
        }

        public Session serviceGroup(int group) { this.serviceGroup = group; return this; }
        public Session partner(Integer id) { this.partner = id; return this; }
        public Session segment(String name) { this.segment = name; return this; }
        public Session msisdn(String number) { this.msisdn = number; return this; }
        public Session site(String name) { this.site = name; return this; }
        /** The record's {@code OutgoingRoute}, when a producer sends one. */
        public Session route(String outgoingRoute) { this.route = outgoingRoute; return this; }
        public Session seconds(String seconds) { this.seconds = seconds; return this; }
        /** The unit the tier paid in: {@code TF_min} is a bucket, {@code BDT} is money; null = the wire named none. */
        public Session uom(String unit) { this.uom = unit; return this; }
        public Session minutes(String fromTheBucket) { this.packageAmount = fromTheBucket; return this; }
        public Session rate(String reference) { this.rate = reference; return this; }
        public Session cause(String word) { this.cause = word; return this; }
        public Session prefix(String matched) { this.prefix = matched; return this; }
        /** The legacy package stamps SG10 reads: {@code AdditionalSystemCodes} (the amount) and {@code AdditionalPartyNumber} (the id). */
        public Session legacyPackageStamps(String amount, String idPackage) { this.legacyPackageAmount = amount; this.legacyPackageId = idPackage; return this; }

        /** The tier paid in money: the amount in BDT, nothing from a bucket. */
        public Session paidInMoney(String amount) {
            this.uom = "BDT";
            this.money = amount;
            this.packageAmount = "0";
            return this;
        }

        /** No traffic: 0 seconds, ChargingStatus 0 (the voice rule), 0 taken from the bucket; the answer time stays. */
        public Session ofZeroSeconds() {
            this.seconds = "0";
            this.chargingStatus = 0;
            this.packageAmount = "0";
            return this;
        }

        public String json() {
            String sessionId = "5c0a7a2e-1b3d-4f60-9e11-0000000" + String.format("%05d", id);
            StringBuilder cdr = new StringBuilder("{\"SwitchId\":0,\"IdCall\":").append(id).append(",\"SequenceNumber\":").append(1791200000000040L + id)
                    .append(",\"FileName\":\"kafka:cdr\",\"UniqueBillId\":").append(quoted(sessionId));
            cdr.append(",\"ServiceGroup\":").append(serviceGroup);
            cdr.append(",\"IncomingRoute\":").append(quoted(site));
            if (route != null) cdr.append(",\"OutgoingRoute\":").append(quoted(route));
            cdr.append(",\"OriginatingCalledNumber\":").append(quoted(segment)).append(",\"TerminatingCalledNumber\":").append(quoted(segment));
            cdr.append(",\"OriginatingCallingNumber\":").append(quoted(msisdn)).append(",\"TerminatingCallingNumber\":").append(quoted(msisdn));
            cdr.append(",\"PrePaid\":1,\"DurationSec\":").append(seconds).append(",\"RoundedDuration\":").append(seconds).append(",\"Duration1\":").append(seconds);
            cdr.append(",\"StartTime\":").append(date(start)).append(",\"EndTime\":").append(date(start.plusSeconds(Long.parseLong(seconds))));
            if (answered) cdr.append(",\"AnswerTime\":").append(date(start)).append(",\"ConnectTime\":").append(date(start));
            cdr.append(",\"ChargingStatus\":").append(chargingStatus);
            if (partner != null) cdr.append(",\"InPartnerId\":").append(partner);
            cdr.append(",\"CustomerRate\":").append(rate).append(",\"InPartnerCost\":").append(money);
            if (prefix != null) cdr.append(",\"MatchedPrefixCustomer\":").append(quoted(prefix));
            if (cause != null) cdr.append(",\"HangupCause\":").append(quoted(cause));
            if (uom != null) cdr.append(",\"InPartnerUom\":").append(quoted(uom));
            if (packageAccount != null) cdr.append(",\"IdPackageAccount\":").append(packageAccount);
            cdr.append(",\"PackageAmount\":").append(packageAmount);
            if (legacyPackageAmount != null) cdr.append(",\"AdditionalSystemCodes\":").append(quoted(legacyPackageAmount));
            if (legacyPackageId != null) cdr.append(",\"AdditionalPartyNumber\":").append(quoted(legacyPackageId));
            cdr.append(",\"ResellerHierarchy\":\"btcl\",\"ChannelCallUuid\":").append(quoted(sessionId)).append("}");

            // SfPreRated: BilledAmount = the minutes when the tier paid in units (> 0), else the money; the unit as sent
            boolean paidInUnits = new java.math.BigDecimal(packageAmount).signum() > 0;
            String billed = paidInUnits ? packageAmount : money;
            String chargeable = "{\"id\":0,\"idEvent\":" + id + ",\"uniqueBillId\":" + quoted(sessionId) + ",\"transactionTime\":" + date(start)
                    + ",\"assignedDirection\":1,\"glAccountId\":0,\"servicegroup\":" + serviceGroup + ",\"servicefamily\":30,\"ProductId\":0,"
                    + (uom == null ? "" : "\"idBilledUom\":" + quoted(uom) + ",")
                    + "\"BilledAmount\":" + billed + ",\"Quantity\":" + seconds + ",\"idQuantityUom\":\"TF_s\",\"unitPriceOrCharge\":" + rate
                    + (prefix == null ? "" : ",\"Prefix\":" + quoted(prefix)) + ",\"RateId\":0,\"idBillingrule\":0,\"description\":\"nc\"}";
            return "{\"Cdr\":" + cdr + ",\"Chargeables\":[" + chargeable + "]}";
        }

        private static String date(LocalDateTime t) {
            String array = "[" + t.getYear() + "," + t.getMonthValue() + "," + t.getDayOfMonth() + "," + t.getHour() + "," + t.getMinute();
            return array + (t.getSecond() == 0 ? "" : "," + t.getSecond()) + "]";
        }
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static byte[] batchOf(Session... sessions) {
        List<String> entries = new ArrayList<>(sessions.length);
        for (Session session : sessions) entries.add(session.json());
        return ("[" + String.join(",", entries) + "]").getBytes(StandardCharsets.UTF_8);
    }

    /** Build {@code sessions} through {@code bean}, fold them into a cache, and return the window rows. */
    public static Collection<CallSummary> rollup(SummaryBean<CallSummary> bean, List<Session> sessions) {
        SummaryCache<CallSummary> cache = new SummaryCache<>(bean.table(), CallSummary.INSERT_COLUMNS, CallSummary.BUCKET_COLUMN);
        for (CallSummary built : bean.buildBatch(batchOf(sessions.toArray(Session[]::new)))) {
            cache.merge(built, MergeMode.ADD);
        }
        return cache.rows();
    }

    /** The one row of {@code rows} whose customer currency is {@code unit} ({@code ""} for a row the voice rule left uncosted). */
    public static CallSummary rowInUnit(Collection<CallSummary> rows, String unit) {
        List<CallSummary> found = rows.stream().filter(r -> r.tup_customercurrency.equals(unit)).toList();
        if (found.size() != 1) throw new AssertionError("unit '" + unit + "' has " + found.size() + " row(s), not one: " + rows.size() + " row(s) in all");
        return found.get(0);
    }
}
