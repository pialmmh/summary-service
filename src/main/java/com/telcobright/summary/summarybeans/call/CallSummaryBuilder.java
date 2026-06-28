package com.telcobright.summary.summarybeans.call;

import com.telcobright.summary.bean.spi.WindowSize;

import java.math.BigDecimal;

/**
 * Builds a per-call {@link CallSummary} from an outbox blob entry's {@code (Cdr, Customer)} pair — the port of
 * billing-core's {@code CdrSummaryBuilder} (the legacy (cdr, acc_chargeable) signature): the SG-independent
 * identity/count/duration fields (PopulateCommon), the window bucket ({@code tup_starttime} = the cdr's
 * wall-clock {@code StartTime} truncated to the bean's {@link WindowSize}), then the service-group-specific
 * rate/cost/tax/prefix/currency fields (SG10 customer leg vs SG11 customer leg), then null-string defaults.
 */
final class CallSummaryBuilder {

    private CallSummaryBuilder() {
    }

    static CallSummary build(Cdr cdr, Customer customer, WindowSize window) {
        CallSummary s = new CallSummary();
        populateCommon(s, cdr);
        s.tup_starttime = window.bucketStart(cdr.startTime());
        populateServiceGroup(s, cdr, customer);
        replaceNullsWithDefault(s);
        return s;
    }

    private static void populateCommon(CallSummary s, Cdr cdr) {
        s.tup_switchid = cdr.switchId();
        s.tup_inpartnerid = cdr.inPartnerId() == null ? 0 : cdr.inPartnerId();
        s.tup_outpartnerid = cdr.outPartnerId() == null ? 0 : cdr.outPartnerId();
        s.tup_incomingroute = orEmpty(cdr.incomingRoute());
        s.tup_outgoingroute = orEmpty(cdr.outgoingRoute());
        s.tup_incomingip = orEmpty(cdr.originatingIp());
        s.tup_outgoingip = orEmpty(cdr.terminatingIp());

        s.totalcalls = 1;
        s.connectedcalls = cdr.connectTime() != null ? 1 : 0;
        s.connectedcallsCC = cdr.nerSuccess() != null && cdr.nerSuccess() == 1 ? 1 : 0;
        s.successfulcalls = cdr.chargingStatus() == null ? 0 : cdr.chargingStatus();
        s.actualduration = nz(cdr.durationSec());
        s.roundedduration = nz(cdr.roundedDuration());
        s.duration1 = nz(cdr.duration1());
        s.duration2 = nz(cdr.duration2());
        s.duration3 = nz(cdr.duration3());
        s.PDD = nz(cdr.pdd());
    }

    private static void populateServiceGroup(CallSummary s, Cdr cdr, Customer customer) {
        s.tup_countryorareacode = cdr.countryCode();

        if (customer.servicegroup() == 10) {            // SG10 customer leg (+ supplier fields from the cdr)
            s.tup_destinationId = idString(cdr.ansIdTerm());
            s.tup_matchedprefixsupplier = cdr.matchedPrefixSupplier();
            s.tup_matchedprefixcustomer = customer.prefix();
            s.tup_customerrate = nz(customer.unitPriceOrCharge());
            s.tup_customercurrency = customer.idBilledUom();
            s.customercost = nz(customer.billedAmount());
            s.tup_tax1currency = "BDT";
            s.tax1 = nz(customer.taxAmount1());
            s.suppliercost = nz(cdr.outPartnerCost());
            s.tup_supplierrate = nz(cdr.supplierRate());
            s.tup_suppliercurrency = "BDT";
            s.tup_tax2currency = "BDT";
            s.tax2 = nz(cdr.tax2());
        } else if (customer.servicegroup() == 11) {     // SG11 customer leg
            s.tup_matchedprefixcustomer = cdr.matchedPrefixY();
            s.tup_sourceId = idString(cdr.ansIdOrig());
            s.customercost = nz(customer.billedAmount());
            s.tup_customerrate = nz(customer.otherDecAmount1());
            s.longDecimalAmount1 = nz(customer.otherAmount1());
            s.tax1 = nz(customer.taxAmount1());
        } else {
            throw new IllegalArgumentException("no summary mapping for service group " + customer.servicegroup());
        }
    }

    private static void replaceNullsWithDefault(CallSummary s) {
        s.tup_countryorareacode = orEmpty(s.tup_countryorareacode);
        s.tup_matchedprefixcustomer = orEmpty(s.tup_matchedprefixcustomer);
        s.tup_matchedprefixsupplier = orEmpty(s.tup_matchedprefixsupplier);
        s.tup_sourceId = orEmpty(s.tup_sourceId);
        s.tup_destinationId = orEmpty(s.tup_destinationId);
        s.tup_customercurrency = orEmpty(s.tup_customercurrency);
        s.tup_suppliercurrency = orEmpty(s.tup_suppliercurrency);
        s.tup_tax1currency = orEmpty(s.tup_tax1currency);
        s.tup_tax2currency = orEmpty(s.tup_tax2currency);
        s.tup_vatcurrency = orEmpty(s.tup_vatcurrency);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String idString(Integer id) {
        return id == null ? null : Integer.toString(id);
    }

    private static String orEmpty(String v) {
        return v == null ? "" : v;
    }
}
