package com.telcobright.summary.summarybeans.call.internal;

import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.call.model.CallSummary;
import com.telcobright.summary.summarybeans.call.model.Cdr;
import com.telcobright.summary.summarybeans.call.model.Chargeable;

import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Builds a per-call {@link CallSummary} from an outbox blob entry's {@code (Cdr, customer-leg Chargeable)}
 * pair — the port of billing's per-SG summary stamps (legacy {@code SgDomOffnetOut/In
 * .SetServiceGroupWiseSummaryParams} + {@code SgIntlTransitVoice.SetChargingSummaryInCustomerDirection}):
 * the SG-independent identity/count/duration fields, the window bucket ({@code tup_starttime} = the cdr's
 * wall-clock {@code StartTime} truncated to the bean's {@link WindowSize}), the per-SG pre-charge fields,
 * then — ONLY for a charged call ({@code ChargingStatus == 1}, the legacy early-return) — the
 * rate/cost/tax/currency block, then null-string defaults and key canonicalization.
 *
 * <p>Legacy quirk kept: SG10 sets {@code tup_matchedprefixcustomer} from the CHARGEABLE's prefix inside the
 * customer-direction stamp and then immediately overwrites it with {@code cdr.MatchedPrefixCustomer} — so the
 * CDR field is the surviving source (work order §2). {@code AdditionalSystemCodes}/{@code AdditionalPartyNumber}
 * arrive as JSON STRINGS (legacy repurposed free-text columns); unparsable → 0 with a warn, per the dotnet ruling.
 */
final class CallSummaryBuilder {

    private static final Logger LOG = Logger.getLogger(CallSummaryBuilder.class);

    private CallSummaryBuilder() {
    }

    static CallSummary build(Cdr cdr, Chargeable chargeable, WindowSize window) {
        CallSummary s = new CallSummary();
        populateCommon(s, cdr);
        s.tup_starttime = window.bucketStart(cdr.startTime());
        populateServiceGroup(s, cdr, chargeable);
        replaceNullsWithDefault(s);
        canonicalizeKeyDimensionsToColumnContract(s);
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

    private static void populateServiceGroup(CallSummary s, Cdr cdr, Chargeable chargeable) {
        s.tup_countryorareacode = cdr.countryCode();
        boolean charged = cdr.chargingStatus() != null && cdr.chargingStatus() == 1;

        if (chargeable.servicegroup() == 10) {          // SG10 (SgDomOffnetOut)
            // pre-charge stamps — set for EVERY call, charged or not
            s.tup_destinationId = idString(cdr.ansIdTerm());
            s.tup_matchedprefixsupplier = cdr.matchedPrefixSupplier();
            if (!charged) {
                return;                                  // legacy early-return: ChargingStatus != 1
            }
            // customer-direction stamp (SgIntlTransitVoice) — rate/currency/cost from the customer leg
            s.tup_customerrate = nz(chargeable.unitPriceOrCharge());
            s.tup_customercurrency = chargeable.idBilledUom();
            s.customercost = nz(chargeable.billedAmount());
            // SG stamp — cdr-side fields; MatchedPrefixCustomer OVERWRITES the leg's prefix (legacy order)
            s.tup_matchedprefixcustomer = cdr.matchedPrefixCustomer();
            s.suppliercost = nz(cdr.outPartnerCost());
            s.tup_supplierrate = nz(cdr.supplierRate());
            s.tup_suppliercurrency = "BDT";
            s.tup_tax1currency = "BDT";                  // btrc
            s.tax1 = nz(chargeable.taxAmount1());        // partner tax amount for btrc
            s.tup_tax2currency = "BDT";
            s.tax2 = nz(cdr.tax2());                     // icx tax amount
            s.tup_vatcurrency = "BDT";
            s.vat = nz(cdr.zAmount());                   // ans tax amount
            s.longDecimalAmount1 = nz(cdr.costAnsIn());                                   // anscost
            s.longDecimalAmount2 = parseDecimalOrZero(cdr.additionalSystemCodes(), cdr);  // package amount
            s.intAmount1 = parseIntOrZero(cdr.additionalPartyNumber(), cdr);              // idpackage
        } else if (chargeable.servicegroup() == 11) {   // SG11 (SgDomOffnetIn)
            // pre-charge stamps
            s.tup_matchedprefixcustomer = cdr.matchedPrefixY();
            s.tup_sourceId = idString(cdr.ansIdOrig());
            if (!charged) {
                return;                                  // legacy early-return: ChargingStatus != 1
            }
            s.customercost = nz(chargeable.billedAmount());       // invoice amount
            s.tup_customerrate = nz(chargeable.otherDecAmount1()); // x rate
            s.longDecimalAmount1 = nz(chargeable.otherAmount1());  // x amount
            s.tax1 = nz(chargeable.taxAmount1());                  // btrc
        } else {
            throw new IllegalArgumentException("no summary mapping for service group " + chargeable.servicegroup());
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

    /**
     * Canonicalize the KEY dimensions to what MySQL will actually store — DECIMAL(18,6) rounding and the
     * VARCHAR column widths — BEFORE the tuple key is ever taken. Without this, a 7-decimal rate (or an
     * oversize route) keys differently from its own reloaded row: the engine INSERTs a duplicate window, which
     * either violates {@code uq_tuple} (drain wedged) or, with the unique key dropped, plants two rows that
     * later collide in {@link com.telcobright.summary.engine.internal.SummaryCache#populateExisting}.
     */
    private static void canonicalizeKeyDimensionsToColumnContract(CallSummary s) {
        s.tup_customerrate = scale6(s.tup_customerrate);    // DECIMAL(18,6) — MySQL rounds half away from zero
        s.tup_supplierrate = scale6(s.tup_supplierrate);
        s.tup_incomingroute = clip(s.tup_incomingroute, 64);
        s.tup_outgoingroute = clip(s.tup_outgoingroute, 64);
        s.tup_incomingip = clip(s.tup_incomingip, 64);
        s.tup_outgoingip = clip(s.tup_outgoingip, 64);
        s.tup_countryorareacode = clip(s.tup_countryorareacode, 32);
        s.tup_matchedprefixcustomer = clip(s.tup_matchedprefixcustomer, 32);
        s.tup_matchedprefixsupplier = clip(s.tup_matchedprefixsupplier, 32);
        s.tup_sourceId = clip(s.tup_sourceId, 32);
        s.tup_destinationId = clip(s.tup_destinationId, 32);
        s.tup_customercurrency = clip(s.tup_customercurrency, 16);
        s.tup_suppliercurrency = clip(s.tup_suppliercurrency, 16);
        s.tup_tax1currency = clip(s.tup_tax1currency, 16);
        s.tup_tax2currency = clip(s.tup_tax2currency, 16);
        s.tup_vatcurrency = clip(s.tup_vatcurrency, 16);
    }

    private static BigDecimal scale6(BigDecimal v) {
        return v.setScale(6, RoundingMode.HALF_UP);
    }

    private static String clip(String v, int maxLength) {
        return v.length() <= maxLength ? v : v.substring(0, maxLength);
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

    /** Legacy Convert.ToDecimal on a repurposed free-text field; null/blank/unparsable → 0 (dotnet ruling A4). */
    private static BigDecimal parseDecimalOrZero(String value, Cdr cdr) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            LOG.warnf("unparsable AdditionalSystemCodes '%s' (cdr start=%s) -> 0", value, cdr.startTime());
            return BigDecimal.ZERO;
        }
    }

    /** Legacy Convert.ToInt32 on a repurposed free-text field; null/blank/unparsable → 0 (dotnet ruling A4). */
    private static int parseIntOrZero(String value, Cdr cdr) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            LOG.warnf("unparsable AdditionalPartyNumber '%s' (cdr start=%s) -> 0", value, cdr.startTime());
            return 0;
        }
    }
}
