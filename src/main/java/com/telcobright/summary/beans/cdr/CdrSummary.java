package com.telcobright.summary.beans.cdr;

import com.telcobright.summary.bean.spi.SqlLiterals;
import com.telcobright.summary.bean.spi.SummaryEntity;
import com.telcobright.summary.bean.spi.SummaryKey;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The CDR summary entity — a faithful 1:1 port of billing-core's {@code AbstractCdrSummary} (all 47 data
 * columns). ONE entity for day AND hour (they differ only by the {@code tup_starttime} bucket + the target
 * table) and for call AND sms. Fields keep the legacy column names verbatim so the SQL maps 1:1.
 *
 * <p>{@link #merge} adds every counter (incl. {@code connectedcallsCC}); {@link #multiply} scales every
 * counter EXCEPT {@code connectedcallsCC} — a deliberate legacy inconsistency replicated verbatim (see the
 * comment in {@link #multiply}); do NOT "tidy" it without an explicit decision.
 */
public final class CdrSummary implements SummaryEntity<CdrSummary> {

    /** INSERT columns in legacy ExtInsertColumns order, WITHOUT id (AUTO_INCREMENT assigns it). */
    public static final String INSERT_COLUMNS =
            "tup_switchid,tup_inpartnerid,tup_outpartnerid,tup_incomingroute,tup_outgoingroute,"
            + "tup_customerrate,tup_supplierrate,tup_incomingip,tup_outgoingip,tup_countryorareacode,"
            + "tup_matchedprefixcustomer,tup_matchedprefixsupplier,tup_sourceId,tup_destinationId,"
            + "tup_customercurrency,tup_suppliercurrency,tup_tax1currency,tup_tax2currency,tup_vatcurrency,"
            + "tup_starttime,totalcalls,connectedcalls,connectedcallsCC,successfulcalls,actualduration,"
            + "roundedduration,duration1,duration2,duration3,PDD,customercost,suppliercost,tax1,tax2,vat,"
            + "intAmount1,intAmount2,longAmount1,longAmount2,longDecimalAmount1,longDecimalAmount2,"
            + "intAmount3,longAmount3,longDecimalAmount3,decimalAmount1,decimalAmount2,decimalAmount3";

    public static final String BUCKET_COLUMN = "tup_starttime";

    private Long id;

    // --- dimension / key columns ---
    public int tup_switchid;
    public int tup_inpartnerid;
    public int tup_outpartnerid;
    public String tup_incomingroute = "";
    public String tup_outgoingroute = "";
    public BigDecimal tup_customerrate = BigDecimal.ZERO;
    public BigDecimal tup_supplierrate = BigDecimal.ZERO;
    public String tup_incomingip = "";
    public String tup_outgoingip = "";
    public String tup_countryorareacode = "";
    public String tup_matchedprefixcustomer = "";
    public String tup_matchedprefixsupplier = "";
    public String tup_sourceId = "";
    public String tup_destinationId = "";
    public String tup_customercurrency = "";
    public String tup_suppliercurrency = "";
    public String tup_tax1currency = "";
    public String tup_tax2currency = "";
    public String tup_vatcurrency = "";
    public LocalDateTime tup_starttime;

    // --- counter columns ---
    public long totalcalls;
    public long connectedcalls;
    public long connectedcallsCC;
    public long successfulcalls;
    public BigDecimal actualduration = BigDecimal.ZERO;
    public BigDecimal roundedduration = BigDecimal.ZERO;
    public BigDecimal duration1 = BigDecimal.ZERO;
    public BigDecimal duration2 = BigDecimal.ZERO;
    public BigDecimal duration3 = BigDecimal.ZERO;
    public BigDecimal PDD = BigDecimal.ZERO;
    public BigDecimal customercost = BigDecimal.ZERO;
    public BigDecimal suppliercost = BigDecimal.ZERO;
    public BigDecimal tax1 = BigDecimal.ZERO;
    public BigDecimal tax2 = BigDecimal.ZERO;
    public BigDecimal vat = BigDecimal.ZERO;
    public int intAmount1;
    public int intAmount2;
    public int intAmount3;
    public long longAmount1;
    public long longAmount2;
    public long longAmount3;
    public BigDecimal longDecimalAmount1 = BigDecimal.ZERO;
    public BigDecimal longDecimalAmount2 = BigDecimal.ZERO;
    public BigDecimal longDecimalAmount3 = BigDecimal.ZERO;
    public BigDecimal decimalAmount1 = BigDecimal.ZERO;
    public BigDecimal decimalAmount2 = BigDecimal.ZERO;
    public BigDecimal decimalAmount3 = BigDecimal.ZERO;

    @Override
    public Long id() {
        return id;
    }

    @Override
    public void setId(Long id) {
        this.id = id;
    }

    /** The legacy GetTupleKey() tuple, in its exact order, as canonical tokens (tup_starttime to the second). */
    @Override
    public SummaryKey tupleKey() {
        return SummaryKey.of(
                Long.toString(tup_switchid),
                Long.toString(tup_inpartnerid),
                Long.toString(tup_outpartnerid),
                tup_incomingroute,
                tup_outgoingroute,
                SqlLiterals.decimalKey(tup_customerrate),
                SqlLiterals.decimalKey(tup_supplierrate),
                tup_incomingip,
                tup_outgoingip,
                tup_countryorareacode,
                tup_matchedprefixcustomer,
                tup_matchedprefixsupplier,
                tup_sourceId,
                tup_destinationId,
                tup_tax1currency,
                tup_tax2currency,
                tup_vatcurrency,
                SqlLiterals.datetimeKey(tup_starttime),
                tup_customercurrency,
                tup_suppliercurrency);
    }

    /** Legacy Merge: add every counter, including connectedcallsCC. */
    @Override
    public void merge(CdrSummary o) {
        totalcalls += o.totalcalls;
        connectedcalls += o.connectedcalls;
        connectedcallsCC += o.connectedcallsCC;
        successfulcalls += o.successfulcalls;
        actualduration = actualduration.add(o.actualduration);
        roundedduration = roundedduration.add(o.roundedduration);
        duration1 = duration1.add(o.duration1);
        duration2 = duration2.add(o.duration2);
        duration3 = duration3.add(o.duration3);
        PDD = PDD.add(o.PDD);
        customercost = customercost.add(o.customercost);
        suppliercost = suppliercost.add(o.suppliercost);
        tax1 = tax1.add(o.tax1);
        tax2 = tax2.add(o.tax2);
        vat = vat.add(o.vat);
        intAmount1 += o.intAmount1;
        intAmount2 += o.intAmount2;
        intAmount3 += o.intAmount3;
        longAmount1 += o.longAmount1;
        longAmount2 += o.longAmount2;
        longAmount3 += o.longAmount3;
        longDecimalAmount1 = longDecimalAmount1.add(o.longDecimalAmount1);
        longDecimalAmount2 = longDecimalAmount2.add(o.longDecimalAmount2);
        longDecimalAmount3 = longDecimalAmount3.add(o.longDecimalAmount3);
        decimalAmount1 = decimalAmount1.add(o.decimalAmount1);
        decimalAmount2 = decimalAmount2.add(o.decimalAmount2);
        decimalAmount3 = decimalAmount3.add(o.decimalAmount3);
    }

    /**
     * Legacy Multiply: scale every counter EXCEPT connectedcallsCC. The omission of connectedcallsCC is a
     * deliberate legacy inconsistency, replicated VERBATIM so the SUBTRACT/correction path behaves bug-for-bug
     * like billing-core. Do not add connectedcallsCC here without an explicit decision.
     */
    @Override
    public void multiply(int v) {
        BigDecimal f = BigDecimal.valueOf(v);
        totalcalls = v * totalcalls;
        connectedcalls = v * connectedcalls;
        // connectedcallsCC intentionally NOT scaled (mirrors legacy AbstractCdrSummary.Multiply)
        successfulcalls = v * successfulcalls;
        actualduration = actualduration.multiply(f);
        roundedduration = roundedduration.multiply(f);
        duration1 = duration1.multiply(f);
        duration2 = duration2.multiply(f);
        duration3 = duration3.multiply(f);
        PDD = PDD.multiply(f);
        customercost = customercost.multiply(f);
        suppliercost = suppliercost.multiply(f);
        tax1 = tax1.multiply(f);
        tax2 = tax2.multiply(f);
        vat = vat.multiply(f);
        intAmount1 = v * intAmount1;
        intAmount2 = v * intAmount2;
        intAmount3 = v * intAmount3;
        longAmount1 = v * longAmount1;
        longAmount2 = v * longAmount2;
        longAmount3 = v * longAmount3;
        longDecimalAmount1 = longDecimalAmount1.multiply(f);
        longDecimalAmount2 = longDecimalAmount2.multiply(f);
        longDecimalAmount3 = longDecimalAmount3.multiply(f);
        decimalAmount1 = decimalAmount1.multiply(f);
        decimalAmount2 = decimalAmount2.multiply(f);
        decimalAmount3 = decimalAmount3.multiply(f);
    }

    @Override
    public CdrSummary cloneWithFakeId() {
        CdrSummary c = new CdrSummary();
        c.id = null;
        c.tup_switchid = tup_switchid;
        c.tup_inpartnerid = tup_inpartnerid;
        c.tup_outpartnerid = tup_outpartnerid;
        c.tup_incomingroute = tup_incomingroute;
        c.tup_outgoingroute = tup_outgoingroute;
        c.tup_customerrate = tup_customerrate;
        c.tup_supplierrate = tup_supplierrate;
        c.tup_incomingip = tup_incomingip;
        c.tup_outgoingip = tup_outgoingip;
        c.tup_countryorareacode = tup_countryorareacode;
        c.tup_matchedprefixcustomer = tup_matchedprefixcustomer;
        c.tup_matchedprefixsupplier = tup_matchedprefixsupplier;
        c.tup_sourceId = tup_sourceId;
        c.tup_destinationId = tup_destinationId;
        c.tup_customercurrency = tup_customercurrency;
        c.tup_suppliercurrency = tup_suppliercurrency;
        c.tup_tax1currency = tup_tax1currency;
        c.tup_tax2currency = tup_tax2currency;
        c.tup_vatcurrency = tup_vatcurrency;
        c.tup_starttime = tup_starttime;
        c.totalcalls = totalcalls;
        c.connectedcalls = connectedcalls;
        c.connectedcallsCC = connectedcallsCC;
        c.successfulcalls = successfulcalls;
        c.actualduration = actualduration;
        c.roundedduration = roundedduration;
        c.duration1 = duration1;
        c.duration2 = duration2;
        c.duration3 = duration3;
        c.PDD = PDD;
        c.customercost = customercost;
        c.suppliercost = suppliercost;
        c.tax1 = tax1;
        c.tax2 = tax2;
        c.vat = vat;
        c.intAmount1 = intAmount1;
        c.intAmount2 = intAmount2;
        c.intAmount3 = intAmount3;
        c.longAmount1 = longAmount1;
        c.longAmount2 = longAmount2;
        c.longAmount3 = longAmount3;
        c.longDecimalAmount1 = longDecimalAmount1;
        c.longDecimalAmount2 = longDecimalAmount2;
        c.longDecimalAmount3 = longDecimalAmount3;
        c.decimalAmount1 = decimalAmount1;
        c.decimalAmount2 = decimalAmount2;
        c.decimalAmount3 = decimalAmount3;
        return c;
    }

    /** The (v1,…,v47) tuple in INSERT_COLUMNS order (legacy GetExtInsertValues, minus id). */
    @Override
    public String insertValues() {
        return "(" + SqlLiterals.num(tup_switchid)
                + "," + SqlLiterals.num(tup_inpartnerid)
                + "," + SqlLiterals.num(tup_outpartnerid)
                + "," + SqlLiterals.str(tup_incomingroute)
                + "," + SqlLiterals.str(tup_outgoingroute)
                + "," + SqlLiterals.num(tup_customerrate)
                + "," + SqlLiterals.num(tup_supplierrate)
                + "," + SqlLiterals.str(tup_incomingip)
                + "," + SqlLiterals.str(tup_outgoingip)
                + "," + SqlLiterals.str(tup_countryorareacode)
                + "," + SqlLiterals.str(tup_matchedprefixcustomer)
                + "," + SqlLiterals.str(tup_matchedprefixsupplier)
                + "," + SqlLiterals.str(tup_sourceId)
                + "," + SqlLiterals.str(tup_destinationId)
                + "," + SqlLiterals.str(tup_customercurrency)
                + "," + SqlLiterals.str(tup_suppliercurrency)
                + "," + SqlLiterals.str(tup_tax1currency)
                + "," + SqlLiterals.str(tup_tax2currency)
                + "," + SqlLiterals.str(tup_vatcurrency)
                + "," + SqlLiterals.datetime(tup_starttime)
                + "," + SqlLiterals.num(totalcalls)
                + "," + SqlLiterals.num(connectedcalls)
                + "," + SqlLiterals.num(connectedcallsCC)
                + "," + SqlLiterals.num(successfulcalls)
                + "," + SqlLiterals.num(actualduration)
                + "," + SqlLiterals.num(roundedduration)
                + "," + SqlLiterals.num(duration1)
                + "," + SqlLiterals.num(duration2)
                + "," + SqlLiterals.num(duration3)
                + "," + SqlLiterals.num(PDD)
                + "," + SqlLiterals.num(customercost)
                + "," + SqlLiterals.num(suppliercost)
                + "," + SqlLiterals.num(tax1)
                + "," + SqlLiterals.num(tax2)
                + "," + SqlLiterals.num(vat)
                + "," + SqlLiterals.num(intAmount1)
                + "," + SqlLiterals.num(intAmount2)
                + "," + SqlLiterals.num(longAmount1)
                + "," + SqlLiterals.num(longAmount2)
                + "," + SqlLiterals.num(longDecimalAmount1)
                + "," + SqlLiterals.num(longDecimalAmount2)
                + "," + SqlLiterals.num(intAmount3)
                + "," + SqlLiterals.num(longAmount3)
                + "," + SqlLiterals.num(longDecimalAmount3)
                + "," + SqlLiterals.num(decimalAmount1)
                + "," + SqlLiterals.num(decimalAmount2)
                + "," + SqlLiterals.num(decimalAmount3)
                + ")";
    }

    /** The counter assignments for UPDATE (legacy GetUpdateCommand order); dimensions never change. */
    @Override
    public String updateAssignments() {
        return "totalcalls=" + SqlLiterals.num(totalcalls)
                + ",connectedcalls=" + SqlLiterals.num(connectedcalls)
                + ",connectedcallsCC=" + SqlLiterals.num(connectedcallsCC)
                + ",successfulcalls=" + SqlLiterals.num(successfulcalls)
                + ",actualduration=" + SqlLiterals.num(actualduration)
                + ",roundedduration=" + SqlLiterals.num(roundedduration)
                + ",duration1=" + SqlLiterals.num(duration1)
                + ",duration2=" + SqlLiterals.num(duration2)
                + ",duration3=" + SqlLiterals.num(duration3)
                + ",PDD=" + SqlLiterals.num(PDD)
                + ",customercost=" + SqlLiterals.num(customercost)
                + ",suppliercost=" + SqlLiterals.num(suppliercost)
                + ",tax1=" + SqlLiterals.num(tax1)
                + ",tax2=" + SqlLiterals.num(tax2)
                + ",vat=" + SqlLiterals.num(vat)
                + ",intAmount1=" + SqlLiterals.num(intAmount1)
                + ",intAmount2=" + SqlLiterals.num(intAmount2)
                + ",longAmount1=" + SqlLiterals.num(longAmount1)
                + ",longAmount2=" + SqlLiterals.num(longAmount2)
                + ",longDecimalAmount1=" + SqlLiterals.num(longDecimalAmount1)
                + ",longDecimalAmount2=" + SqlLiterals.num(longDecimalAmount2)
                + ",intAmount3=" + SqlLiterals.num(intAmount3)
                + ",longAmount3=" + SqlLiterals.num(longAmount3)
                + ",longDecimalAmount3=" + SqlLiterals.num(longDecimalAmount3)
                + ",decimalAmount1=" + SqlLiterals.num(decimalAmount1)
                + ",decimalAmount2=" + SqlLiterals.num(decimalAmount2)
                + ",decimalAmount3=" + SqlLiterals.num(decimalAmount3);
    }
}
