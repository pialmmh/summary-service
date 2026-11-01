package com.telcobright.summary.summarybeans.ad.model;

import com.telcobright.summary.bean.spi.SqlLiterals;
import com.telcobright.summary.bean.spi.SummaryEntity;
import com.telcobright.summary.bean.spi.SummaryKey;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One {@code sum_ad_*} row — the AD summary entity (design AD-AS-CALL §2.9, 2026-09-29; net-new, no legacy
 * counterpart). One row per TIER of an ad call (the chargeable pattern): the key is the tier's tenant (its
 * database: a reseller's summary is the rows where {@code tup_tenant} = its database) and partner, the campaign,
 * the CONTENT shown (a campaign holds several; {@code ''} when the record carries none),
 * the rule code, the zone, the site, the app, the media kind and the outcome, plus the window bucket
 * ({@code tup_starttime} = the call's start truncated to the window). Measures all {@code +=}; {@code multiply}
 * scales ALL of them (net-new stays clean).
 *
 * <p>What a tier was charged is TWO measures, never added to each other (the architect's ruling on SS-0001, decide
 * 3): {@code chargedamount} is MONEY — the tier's charge in BDT; {@code chargedunits} is what a tier paid in the
 * units of a package (seconds, views). Both DECIMAL(18,6). A row can carry both: views of one key paid in money by
 * one account and in units by another.
 */
public final class AdSummary implements SummaryEntity<AdSummary> {

    /** INSERT column list (CSV, in {@link #insertValues()} order, WITHOUT id). */
    public static final String INSERT_COLUMNS =
            "tup_tenant,tup_partnerid,tup_campaignid,tup_rulecode,tup_zone,tup_site,tup_app,tup_mediakind,tup_outcome,"
                    + "tup_starttime,views,shown,completed,credited,failed,watchedsec,chargedamount,chargedunits,tup_contentid,skipped";

    public static final String BUCKET_COLUMN = "tup_starttime";

    private Long id;

    // -- key dimensions --
    public String tup_tenant = "";
    public int tup_partnerid;
    public int tup_campaignid;
    public String tup_rulecode = "";
    public String tup_zone = "";
    public String tup_site = "";
    public String tup_app = "";
    public String tup_mediakind = "";
    public String tup_outcome = "";
    public LocalDateTime tup_starttime;
    /**
     * The content shown — {@code AdditionalMetaData.contentId}; {@code ''} when the record carries none. A key
     * dimension like the others. Its COLUMN is the table's last: a table that got it later (by ALTER, which can
     * only append on PostgreSQL) is then the same table as one made with it.
     */
    public String tup_contentid = "";

    // -- measures (all +=; multiply scales ALL) --
    public long views;
    public long shown;
    public long completed;
    public long credited;
    public long failed;
    public long watchedsec;
    /** Money: the charges whose unit is BDT. */
    public BigDecimal chargedamount = BigDecimal.ZERO;
    /** Units: the charges paid from a package, in the package's own unit. Never added to the money. */
    public BigDecimal chargedunits = BigDecimal.ZERO;
    /**
     * 2026-11-02 (the owner: "Reports: a column for skipped views"): the views the viewer skipped at the content's skip
     * mark — counted INSIDE views, completed and credited, never instead of them. Its COLUMN is the table's last
     * (after {@code tup_contentid}): a table that gets it by ALTER is the same table as one made with it. ad-sphere's
     * reader sums it by this name ({@code JdbcCdrReader.SKIPPED}).
     */
    public long skipped;

    @Override
    public Long id() {
        return id;
    }

    @Override
    public void setId(Long id) {
        this.id = id;
    }

    /** The 11-token dimension + bucket tuple, as canonical tokens. */
    @Override
    public SummaryKey tupleKey() {
        return SummaryKey.of(
                tup_tenant,
                Integer.toString(tup_partnerid),
                Integer.toString(tup_campaignid),
                tup_contentid,
                tup_rulecode,
                tup_zone,
                tup_site,
                tup_app,
                tup_mediakind,
                tup_outcome,
                SqlLiterals.datetimeKey(tup_starttime));
    }

    @Override
    public void merge(AdSummary o) {
        views += o.views;
        shown += o.shown;
        completed += o.completed;
        credited += o.credited;
        failed += o.failed;
        watchedsec += o.watchedsec;
        chargedamount = chargedamount.add(o.chargedamount);
        chargedunits = chargedunits.add(o.chargedunits);
        skipped += o.skipped;
    }

    /** Scales EVERY measure, {@code views} included (the SUBTRACT path negates a copy). */
    @Override
    public void multiply(int factor) {
        views *= factor;
        shown *= factor;
        completed *= factor;
        credited *= factor;
        failed *= factor;
        watchedsec *= factor;
        chargedamount = chargedamount.multiply(BigDecimal.valueOf(factor));
        chargedunits = chargedunits.multiply(BigDecimal.valueOf(factor));
        skipped *= factor;
    }

    @Override
    public AdSummary cloneWithFakeId() {
        AdSummary c = new AdSummary();
        c.tup_tenant = tup_tenant;
        c.tup_partnerid = tup_partnerid;
        c.tup_campaignid = tup_campaignid;
        c.tup_rulecode = tup_rulecode;
        c.tup_zone = tup_zone;
        c.tup_site = tup_site;
        c.tup_app = tup_app;
        c.tup_mediakind = tup_mediakind;
        c.tup_outcome = tup_outcome;
        c.tup_starttime = tup_starttime;
        c.tup_contentid = tup_contentid;
        c.views = views;
        c.shown = shown;
        c.completed = completed;
        c.credited = credited;
        c.failed = failed;
        c.watchedsec = watchedsec;
        c.chargedamount = chargedamount;
        c.chargedunits = chargedunits;
        c.skipped = skipped;
        return c;
    }

    @Override
    public String insertValues() {
        return "(" + SqlLiterals.str(tup_tenant)
                + "," + SqlLiterals.num(tup_partnerid)
                + "," + SqlLiterals.num(tup_campaignid)
                + "," + SqlLiterals.str(tup_rulecode)
                + "," + SqlLiterals.str(tup_zone)
                + "," + SqlLiterals.str(tup_site)
                + "," + SqlLiterals.str(tup_app)
                + "," + SqlLiterals.str(tup_mediakind)
                + "," + SqlLiterals.str(tup_outcome)
                + "," + SqlLiterals.datetime(tup_starttime)
                + "," + SqlLiterals.num(views)
                + "," + SqlLiterals.num(shown)
                + "," + SqlLiterals.num(completed)
                + "," + SqlLiterals.num(credited)
                + "," + SqlLiterals.num(failed)
                + "," + SqlLiterals.num(watchedsec)
                + "," + SqlLiterals.num(chargedamount)
                + "," + SqlLiterals.num(chargedunits)
                + "," + SqlLiterals.str(tup_contentid)
                + "," + SqlLiterals.num(skipped)
                + ")";
    }

    @Override
    public String updateAssignments() {
        return "views=" + SqlLiterals.num(views)
                + ",shown=" + SqlLiterals.num(shown)
                + ",completed=" + SqlLiterals.num(completed)
                + ",credited=" + SqlLiterals.num(credited)
                + ",failed=" + SqlLiterals.num(failed)
                + ",watchedsec=" + SqlLiterals.num(watchedsec)
                + ",chargedamount=" + SqlLiterals.num(chargedamount)
                + ",chargedunits=" + SqlLiterals.num(chargedunits)
                + ",skipped=" + SqlLiterals.num(skipped);
    }

    /** The date-partition key for this row: {@code tup_starttime}. */
    @Override
    public String bucketLiteral() {
        return SqlLiterals.datetime(tup_starttime);
    }
}
