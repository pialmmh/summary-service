package com.telcobright.summary.summarybeans.chargeable.model;

import com.telcobright.summary.bean.spi.SqlLiterals;
import com.telcobright.summary.bean.spi.SummaryEntity;
import com.telcobright.summary.bean.spi.SummaryKey;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One {@code sum_chargeable_*} row — the CHARGEABLE summary entity (net-new, architect-specified in the
 * 2026-07-02 work order §4; there is no legacy chargeable rollup). Key = the legacy {@code acc_chargeable
 * .GetTuple()} anchor (servicegroup, servicefamily, assignedDirection) + product/currency/prefix + the window
 * bucket ({@code tup_transactiontime}); customer (revenue) and supplier (cost) legs stay SEPARATE rows because
 * direction is a key column. Measures all {@code +=}; {@code multiply} scales ALL of them — net-new stays
 * clean, no legacy quirk. Amounts are DECIMAL(20,8) (billing money math rounds HALF_EVEN at 8dp).
 */
public final class ChargeableSummary implements SummaryEntity<ChargeableSummary> {

    /** INSERT column list (CSV, in {@link #insertValues()} order, WITHOUT id). */
    public static final String INSERT_COLUMNS =
            "tup_servicegroup,tup_servicefamily,tup_assigneddirection,tup_productid,tup_billeduom,tup_prefix,"
                    + "tup_transactiontime,totalcount,BilledAmount,Quantity,"
                    + "TaxAmount1,TaxAmount2,TaxAmount3,VatAmount1,VatAmount2,VatAmount3,"
                    + "OtherAmount1,OtherAmount2,OtherAmount3,OtherDecAmount1,OtherDecAmount2,OtherDecAmount3";

    public static final String BUCKET_COLUMN = "tup_transactiontime";

    private Long id;

    // -- key dimensions --
    public int tup_servicegroup;
    public int tup_servicefamily;
    public int tup_assigneddirection;
    public long tup_productid;
    public String tup_billeduom = "";
    public String tup_prefix = "";
    public LocalDateTime tup_transactiontime;

    // -- measures (all +=; multiply scales ALL) --
    public long totalcount;
    public BigDecimal BilledAmount = BigDecimal.ZERO;
    public BigDecimal Quantity = BigDecimal.ZERO;
    public BigDecimal TaxAmount1 = BigDecimal.ZERO;
    public BigDecimal TaxAmount2 = BigDecimal.ZERO;
    public BigDecimal TaxAmount3 = BigDecimal.ZERO;
    public BigDecimal VatAmount1 = BigDecimal.ZERO;
    public BigDecimal VatAmount2 = BigDecimal.ZERO;
    public BigDecimal VatAmount3 = BigDecimal.ZERO;
    public BigDecimal OtherAmount1 = BigDecimal.ZERO;
    public BigDecimal OtherAmount2 = BigDecimal.ZERO;
    public BigDecimal OtherAmount3 = BigDecimal.ZERO;
    public BigDecimal OtherDecAmount1 = BigDecimal.ZERO;
    public BigDecimal OtherDecAmount2 = BigDecimal.ZERO;
    public BigDecimal OtherDecAmount3 = BigDecimal.ZERO;

    @Override
    public Long id() {
        return id;
    }

    @Override
    public void setId(Long id) {
        this.id = id;
    }

    /** The 7-token dimension+bucket tuple, as canonical tokens. */
    @Override
    public SummaryKey tupleKey() {
        return SummaryKey.of(
                Integer.toString(tup_servicegroup),
                Integer.toString(tup_servicefamily),
                Integer.toString(tup_assigneddirection),
                Long.toString(tup_productid),
                tup_billeduom,
                tup_prefix,
                SqlLiterals.datetimeKey(tup_transactiontime));
    }

    @Override
    public void merge(ChargeableSummary o) {
        totalcount += o.totalcount;
        BilledAmount = BilledAmount.add(o.BilledAmount);
        Quantity = Quantity.add(o.Quantity);
        TaxAmount1 = TaxAmount1.add(o.TaxAmount1);
        TaxAmount2 = TaxAmount2.add(o.TaxAmount2);
        TaxAmount3 = TaxAmount3.add(o.TaxAmount3);
        VatAmount1 = VatAmount1.add(o.VatAmount1);
        VatAmount2 = VatAmount2.add(o.VatAmount2);
        VatAmount3 = VatAmount3.add(o.VatAmount3);
        OtherAmount1 = OtherAmount1.add(o.OtherAmount1);
        OtherAmount2 = OtherAmount2.add(o.OtherAmount2);
        OtherAmount3 = OtherAmount3.add(o.OtherAmount3);
        OtherDecAmount1 = OtherDecAmount1.add(o.OtherDecAmount1);
        OtherDecAmount2 = OtherDecAmount2.add(o.OtherDecAmount2);
        OtherDecAmount3 = OtherDecAmount3.add(o.OtherDecAmount3);
    }

    /** Scales EVERY measure — including {@code totalcount} (no legacy quirk here; net-new stays clean). */
    @Override
    public void multiply(int factor) {
        BigDecimal f = BigDecimal.valueOf(factor);
        totalcount *= factor;
        BilledAmount = BilledAmount.multiply(f);
        Quantity = Quantity.multiply(f);
        TaxAmount1 = TaxAmount1.multiply(f);
        TaxAmount2 = TaxAmount2.multiply(f);
        TaxAmount3 = TaxAmount3.multiply(f);
        VatAmount1 = VatAmount1.multiply(f);
        VatAmount2 = VatAmount2.multiply(f);
        VatAmount3 = VatAmount3.multiply(f);
        OtherAmount1 = OtherAmount1.multiply(f);
        OtherAmount2 = OtherAmount2.multiply(f);
        OtherAmount3 = OtherAmount3.multiply(f);
        OtherDecAmount1 = OtherDecAmount1.multiply(f);
        OtherDecAmount2 = OtherDecAmount2.multiply(f);
        OtherDecAmount3 = OtherDecAmount3.multiply(f);
    }

    @Override
    public ChargeableSummary cloneWithFakeId() {
        ChargeableSummary c = new ChargeableSummary();
        c.tup_servicegroup = tup_servicegroup;
        c.tup_servicefamily = tup_servicefamily;
        c.tup_assigneddirection = tup_assigneddirection;
        c.tup_productid = tup_productid;
        c.tup_billeduom = tup_billeduom;
        c.tup_prefix = tup_prefix;
        c.tup_transactiontime = tup_transactiontime;
        c.totalcount = totalcount;
        c.BilledAmount = BilledAmount;
        c.Quantity = Quantity;
        c.TaxAmount1 = TaxAmount1;
        c.TaxAmount2 = TaxAmount2;
        c.TaxAmount3 = TaxAmount3;
        c.VatAmount1 = VatAmount1;
        c.VatAmount2 = VatAmount2;
        c.VatAmount3 = VatAmount3;
        c.OtherAmount1 = OtherAmount1;
        c.OtherAmount2 = OtherAmount2;
        c.OtherAmount3 = OtherAmount3;
        c.OtherDecAmount1 = OtherDecAmount1;
        c.OtherDecAmount2 = OtherDecAmount2;
        c.OtherDecAmount3 = OtherDecAmount3;
        return c;
    }

    @Override
    public String insertValues() {
        return "(" + SqlLiterals.num(tup_servicegroup)
                + "," + SqlLiterals.num(tup_servicefamily)
                + "," + SqlLiterals.num(tup_assigneddirection)
                + "," + SqlLiterals.num(tup_productid)
                + "," + SqlLiterals.str(tup_billeduom)
                + "," + SqlLiterals.str(tup_prefix)
                + "," + SqlLiterals.datetime(tup_transactiontime)
                + "," + SqlLiterals.num(totalcount)
                + "," + SqlLiterals.num(BilledAmount)
                + "," + SqlLiterals.num(Quantity)
                + "," + SqlLiterals.num(TaxAmount1)
                + "," + SqlLiterals.num(TaxAmount2)
                + "," + SqlLiterals.num(TaxAmount3)
                + "," + SqlLiterals.num(VatAmount1)
                + "," + SqlLiterals.num(VatAmount2)
                + "," + SqlLiterals.num(VatAmount3)
                + "," + SqlLiterals.num(OtherAmount1)
                + "," + SqlLiterals.num(OtherAmount2)
                + "," + SqlLiterals.num(OtherAmount3)
                + "," + SqlLiterals.num(OtherDecAmount1)
                + "," + SqlLiterals.num(OtherDecAmount2)
                + "," + SqlLiterals.num(OtherDecAmount3)
                + ")";
    }

    @Override
    public String updateAssignments() {
        return "totalcount=" + SqlLiterals.num(totalcount)
                + ",BilledAmount=" + SqlLiterals.num(BilledAmount)
                + ",Quantity=" + SqlLiterals.num(Quantity)
                + ",TaxAmount1=" + SqlLiterals.num(TaxAmount1)
                + ",TaxAmount2=" + SqlLiterals.num(TaxAmount2)
                + ",TaxAmount3=" + SqlLiterals.num(TaxAmount3)
                + ",VatAmount1=" + SqlLiterals.num(VatAmount1)
                + ",VatAmount2=" + SqlLiterals.num(VatAmount2)
                + ",VatAmount3=" + SqlLiterals.num(VatAmount3)
                + ",OtherAmount1=" + SqlLiterals.num(OtherAmount1)
                + ",OtherAmount2=" + SqlLiterals.num(OtherAmount2)
                + ",OtherAmount3=" + SqlLiterals.num(OtherAmount3)
                + ",OtherDecAmount1=" + SqlLiterals.num(OtherDecAmount1)
                + ",OtherDecAmount2=" + SqlLiterals.num(OtherDecAmount2)
                + ",OtherDecAmount3=" + SqlLiterals.num(OtherDecAmount3);
    }

    /** The date-partition key for this row: {@code tup_transactiontime}. */
    @Override
    public String bucketLiteral() {
        return SqlLiterals.datetime(tup_transactiontime);
    }
}
