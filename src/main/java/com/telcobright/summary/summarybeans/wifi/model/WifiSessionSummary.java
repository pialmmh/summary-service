package com.telcobright.summary.summarybeans.wifi.model;

import com.telcobright.summary.bean.spi.SqlLiterals;
import com.telcobright.summary.bean.spi.SummaryEntity;
import com.telcobright.summary.bean.spi.SummaryKey;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * One {@code sum_wifi_hr_101} / {@code sum_wifi_day_101} row — the WiFi SESSION summary entity (SS-0006 item 2; the
 * WiFi architect's spec §3). A session is a Call of service group 101; this row sums the sessions of one key in one
 * window. The key is read from the record's COLUMNS, never its meta (the WiFi architect's rule a): the switch, the
 * payer, the site ({@code InTrunkAdditionalInfo}), the zone ({@code OutTrunkAdditionalInfo}), the grant tier's word
 * ({@code MatchedPrefixY}) and its number ({@code Category}), the package code ({@code MatchedPrefixCustomer}), the
 * unit the tier paid in ({@code TF_min} | {@code BDT}, kept apart as the 101 call bean keeps them), the cause family
 * (a word from {@code HangupCause}), and the bucket.
 *
 * <p>The sums all {@code +=}; {@code multiply} scales every one (net-new, as the ad entity). ONE column is DERIVED:
 * {@link #avg_session_sec} = {@code total_duration_sec / sessions_connected} (0 when none), recomputed after every
 * {@link #merge} and {@link #multiply} — NEVER added (the architect's ruling 4): the engine writes the merged row's
 * absolute values, so a derived column holds. A report over a range recomputes it from the two totals.
 *
 * <p>A record whose {@code ValidFlag} is 0 (its bytes are no measurement, A-0099) is STILL summed here (the WiFi
 * architect's rule b): a report that wants measured bytes only filters on the record's flag, not on this table.
 */
public final class WifiSessionSummary implements SummaryEntity<WifiSessionSummary> {

    /** INSERT column list (CSV, in {@link #insertValues()} order, WITHOUT id). */
    public static final String INSERT_COLUMNS =
            "tup_switchid,tup_inpartnerid,tup_site,tup_zone,tup_granttier,tup_category,tup_packagecode,tup_customercurrency,"
                    + "tup_causefamily,tup_starttime,sessions,sessions_connected,total_duration_sec,avg_session_sec,bytes_up,bytes_dn,customercost";

    public static final String BUCKET_COLUMN = "tup_starttime";

    /** The scale of the derived average: the column's six places. */
    private static final int AVERAGE_SCALE = 6;

    private Long id;

    // -- key dimensions (every one a COLUMN of the record) --
    public int tup_switchid;
    public int tup_inpartnerid;
    public String tup_site = "";
    public String tup_zone = "";
    public String tup_granttier = "";
    public int tup_category;
    public String tup_packagecode = "";
    public String tup_customercurrency = "";
    public String tup_causefamily = "";
    public LocalDateTime tup_starttime;

    // -- sums (all +=; multiply scales ALL) --
    public long sessions;
    /** The sessions whose {@code DurationSec} is above 0. */
    public long sessions_connected;
    public long total_duration_sec;
    public long bytes_up;
    public long bytes_dn;
    public BigDecimal customercost = BigDecimal.ZERO;

    // -- derived, never added --
    /** {@code total_duration_sec / sessions_connected}, 0 when none; recomputed by {@link #recomputeAverage()}. */
    public BigDecimal avg_session_sec = BigDecimal.ZERO;

    @Override
    public Long id() {
        return id;
    }

    @Override
    public void setId(Long id) {
        this.id = id;
    }

    /** The 10-token dimension + bucket tuple, as canonical tokens. */
    @Override
    public SummaryKey tupleKey() {
        return SummaryKey.of(
                Integer.toString(tup_switchid),
                Integer.toString(tup_inpartnerid),
                tup_site,
                tup_zone,
                tup_granttier,
                Integer.toString(tup_category),
                tup_packagecode,
                tup_customercurrency,
                tup_causefamily,
                SqlLiterals.datetimeKey(tup_starttime));
    }

    @Override
    public void merge(WifiSessionSummary o) {
        sessions += o.sessions;
        sessions_connected += o.sessions_connected;
        total_duration_sec += o.total_duration_sec;
        bytes_up += o.bytes_up;
        bytes_dn += o.bytes_dn;
        customercost = customercost.add(o.customercost);
        recomputeAverage();
    }

    /** Scales EVERY sum, {@code sessions} included (the SUBTRACT path negates a copy); the average is derived again. */
    @Override
    public void multiply(int factor) {
        sessions *= factor;
        sessions_connected *= factor;
        total_duration_sec *= factor;
        bytes_up *= factor;
        bytes_dn *= factor;
        customercost = customercost.multiply(BigDecimal.valueOf(factor));
        recomputeAverage();
    }

    /** The one derived column: the total seconds over the connected sessions, 0 when none — never a sum of averages. */
    public void recomputeAverage() {
        avg_session_sec = sessions_connected == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(total_duration_sec).divide(BigDecimal.valueOf(sessions_connected), AVERAGE_SCALE, RoundingMode.HALF_UP);
    }

    @Override
    public WifiSessionSummary cloneWithFakeId() {
        WifiSessionSummary c = new WifiSessionSummary();
        c.tup_switchid = tup_switchid;
        c.tup_inpartnerid = tup_inpartnerid;
        c.tup_site = tup_site;
        c.tup_zone = tup_zone;
        c.tup_granttier = tup_granttier;
        c.tup_category = tup_category;
        c.tup_packagecode = tup_packagecode;
        c.tup_customercurrency = tup_customercurrency;
        c.tup_causefamily = tup_causefamily;
        c.tup_starttime = tup_starttime;
        c.sessions = sessions;
        c.sessions_connected = sessions_connected;
        c.total_duration_sec = total_duration_sec;
        c.avg_session_sec = avg_session_sec;
        c.bytes_up = bytes_up;
        c.bytes_dn = bytes_dn;
        c.customercost = customercost;
        return c;
    }

    @Override
    public String insertValues() {
        return "(" + SqlLiterals.num(tup_switchid)
                + "," + SqlLiterals.num(tup_inpartnerid)
                + "," + SqlLiterals.str(tup_site)
                + "," + SqlLiterals.str(tup_zone)
                + "," + SqlLiterals.str(tup_granttier)
                + "," + SqlLiterals.num(tup_category)
                + "," + SqlLiterals.str(tup_packagecode)
                + "," + SqlLiterals.str(tup_customercurrency)
                + "," + SqlLiterals.str(tup_causefamily)
                + "," + SqlLiterals.datetime(tup_starttime)
                + "," + SqlLiterals.num(sessions)
                + "," + SqlLiterals.num(sessions_connected)
                + "," + SqlLiterals.num(total_duration_sec)
                + "," + SqlLiterals.num(avg_session_sec)
                + "," + SqlLiterals.num(bytes_up)
                + "," + SqlLiterals.num(bytes_dn)
                + "," + SqlLiterals.num(customercost)
                + ")";
    }

    @Override
    public String updateAssignments() {
        return "sessions=" + SqlLiterals.num(sessions)
                + ",sessions_connected=" + SqlLiterals.num(sessions_connected)
                + ",total_duration_sec=" + SqlLiterals.num(total_duration_sec)
                + ",avg_session_sec=" + SqlLiterals.num(avg_session_sec)
                + ",bytes_up=" + SqlLiterals.num(bytes_up)
                + ",bytes_dn=" + SqlLiterals.num(bytes_dn)
                + ",customercost=" + SqlLiterals.num(customercost);
    }

    /** The date-partition key for this row: {@code tup_starttime}. */
    @Override
    public String bucketLiteral() {
        return SqlLiterals.datetime(tup_starttime);
    }
}
