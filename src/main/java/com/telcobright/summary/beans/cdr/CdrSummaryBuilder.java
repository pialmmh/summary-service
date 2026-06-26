package com.telcobright.summary.beans.cdr;

import com.telcobright.summary.bean.spi.WindowSize;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Builds a per-call {@link CdrSummary} from a {@link RatedCdrEvent} — the port of legacy
 * {@code CdrSummaryBuilder}: the SG-independent identity/count/duration fields (PopulateCommon), the window
 * bucket ({@code tup_starttime} truncated to the bean's {@link WindowSize}), then the service-group-specific
 * rate/cost/tax/prefix/currency fields (SG10 customer leg vs SG11 customer leg), then null-string defaults.
 *
 * <p>CUSTOMER leg only — supplier/extended-leg fields populate from the cdr where present (SG10) and otherwise
 * stay 0, exactly as legacy.
 */
final class CdrSummaryBuilder {

    private CdrSummaryBuilder() {
    }

    static CdrSummary build(RatedCdrEvent e, WindowSize window, ZoneId zone) {
        CdrSummary s = new CdrSummary();
        populateCommon(s, e);
        s.tup_starttime = window.bucketStart(Instant.ofEpochMilli(e.startEpochMillis()), zone);
        populateServiceGroup(s, e);
        replaceNullsWithDefault(s);
        return s;
    }

    private static void populateCommon(CdrSummary s, RatedCdrEvent e) {
        s.tup_switchid = e.switchId();
        s.tup_inpartnerid = e.inPartnerId() == null ? 0 : e.inPartnerId();
        s.tup_outpartnerid = e.outPartnerId() == null ? 0 : e.outPartnerId();
        s.tup_incomingroute = e.incomingRoute() == null ? "" : e.incomingRoute();
        s.tup_outgoingroute = e.outgoingRoute() == null ? "" : e.outgoingRoute();
        s.tup_incomingip = e.originatingIp() == null ? "" : e.originatingIp();
        s.tup_outgoingip = e.terminatingIp() == null ? "" : e.terminatingIp();

        s.totalcalls = 1;
        s.connectedcalls = e.connectTimeEpochMillis() != null ? 1 : 0;
        s.connectedcallsCC = e.nerSuccess() != null && e.nerSuccess() == 1 ? 1 : 0;
        s.successfulcalls = e.chargingStatus() == null ? 0 : e.chargingStatus();
        s.actualduration = nz(e.durationSec());
        s.roundedduration = nz(e.roundedDuration());
        s.duration1 = nz(e.duration1());
        s.duration2 = nz(e.duration2());
        s.duration3 = nz(e.duration3());
        s.PDD = nz(e.pdd());
    }

    private static void populateServiceGroup(CdrSummary s, RatedCdrEvent e) {
        s.tup_countryorareacode = e.countryCode();

        if (e.serviceGroup() == 10) {            // SG10 customer leg (+ supplier fields from the cdr)
            s.tup_destinationId = idString(e.ansIdTerm());
            s.tup_matchedprefixsupplier = e.matchedPrefixSupplier();
            s.tup_matchedprefixcustomer = e.prefix();
            s.tup_customerrate = nz(e.unitPriceOrCharge());
            s.tup_customercurrency = e.idBilledUom();
            s.customercost = nz(e.billedAmount());
            s.tup_tax1currency = "BDT";
            s.tax1 = nz(e.taxAmount1());
            s.suppliercost = nz(e.outPartnerCost());
            s.tup_supplierrate = nz(e.supplierRate());
            s.tup_suppliercurrency = "BDT";
            s.tup_tax2currency = "BDT";
            s.tax2 = nz(e.tax2());
        } else if (e.serviceGroup() == 11) {     // SG11 customer leg
            s.tup_matchedprefixcustomer = e.matchedPrefixY();
            s.tup_sourceId = idString(e.ansIdOrig());
            s.customercost = nz(e.billedAmount());
            s.tup_customerrate = nz(e.otherDecAmount1());
            s.longDecimalAmount1 = nz(e.otherAmount1());
            s.tax1 = nz(e.taxAmount1());
        } else {
            throw new IllegalArgumentException("no summary mapping for service group " + e.serviceGroup());
        }
    }

    private static void replaceNullsWithDefault(CdrSummary s) {
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
