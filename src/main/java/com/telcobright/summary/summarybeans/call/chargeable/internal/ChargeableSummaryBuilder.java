package com.telcobright.summary.summarybeans.chargeable.internal;

import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.call.model.Chargeable;
import com.telcobright.summary.summarybeans.chargeable.model.ChargeableSummary;

import java.math.BigDecimal;

/**
 * Builds one {@link ChargeableSummary} row from ONE chargeable leg (every leg of every entry becomes a row —
 * no service-group filter, no direction filter; direction is a KEY column, so customer revenue and supplier
 * cost stay separate). Bucket = the leg's own {@code transactionTime} truncated to the bean's window (the
 * authoritative source per dotnet A4 — today it always equals {@code cdr.StartTime}). Null measures → 0;
 * key strings clipped to their column widths so a fresh build keys identically to its reloaded row.
 */
final class ChargeableSummaryBuilder {

    private ChargeableSummaryBuilder() {
    }

    static ChargeableSummary build(Chargeable leg, WindowSize window) {
        ChargeableSummary s = new ChargeableSummary();
        s.tup_servicegroup = leg.servicegroup();
        s.tup_servicefamily = leg.servicefamily();
        s.tup_assigneddirection = leg.assignedDirection() == null ? 0 : leg.assignedDirection();
        s.tup_productid = leg.productId();
        s.tup_billeduom = clip(orEmpty(leg.idBilledUom()), 32);
        s.tup_prefix = clip(orEmpty(leg.prefix()), 32);
        s.tup_transactiontime = window.bucketStart(leg.transactionTime());

        s.totalcount = 1;
        s.BilledAmount = nz(leg.billedAmount());
        s.Quantity = nz(leg.quantity());
        s.TaxAmount1 = nz(leg.taxAmount1());
        s.TaxAmount2 = nz(leg.taxAmount2());
        s.TaxAmount3 = nz(leg.taxAmount3());
        s.VatAmount1 = nz(leg.vatAmount1());
        s.VatAmount2 = nz(leg.vatAmount2());
        s.VatAmount3 = nz(leg.vatAmount3());
        s.OtherAmount1 = nz(leg.otherAmount1());
        s.OtherAmount2 = nz(leg.otherAmount2());
        s.OtherAmount3 = nz(leg.otherAmount3());
        s.OtherDecAmount1 = nz(leg.otherDecAmount1());
        s.OtherDecAmount2 = nz(leg.otherDecAmount2());
        s.OtherDecAmount3 = nz(leg.otherDecAmount3());
        return s;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String orEmpty(String v) {
        return v == null ? "" : v;
    }

    private static String clip(String v, int maxLength) {
        return v.length() <= maxLength ? v : v.substring(0, maxLength);
    }
}
