package com.telcobright.summary.summarybeans.call.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.summarybeans.call.internal.CdrBlobMapper;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.WifiSessionTestSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The GOLDEN decode of the three fixtures the suite already had at 7d63ae6 — a voice call (group 10), an ad view
 * (group 30) and a WiFi session as BC-0007 wrote it (group 101) — field for field, as the {@link Cdr} model read them
 * BEFORE it gained the WiFi columns (SS-0006 item 1). Every value here was captured from the fixtures at 7d63ae6; a
 * change of the mapper, or of a field the three fixtures carry, is red here.
 */
class CdrBlobGoldenTest {

    private static final ObjectMapper MAPPER = CdrBlobMapper.create();
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 10, 9, 0);

    private static Cdr decode(byte[] batch) {
        try {
            List<CdrBlobEntry> entries = MAPPER.readValue(batch, MAPPER.getTypeFactory().constructCollectionType(List.class, CdrBlobEntry.class));
            return entries.get(0).cdr();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void the_voice_fixture_decodes_to_the_values_it_had_at_7d63ae6() {
        Cdr c = decode(CdrTestSupport.batchJson(List.of(CdrTestSupport.sg10Entry(START))));

        assertEquals(1, c.switchId());
        assertEquals(5, c.inPartnerId());
        assertEquals(0, c.outPartnerId());
        assertEquals("in", c.incomingRoute());
        assertEquals("out", c.outgoingRoute());
        assertEquals("1.1.1.1", c.originatingIp());
        assertEquals("2.2.2.2", c.terminatingIp());
        assertEquals("880", c.countryCode());
        assertEquals(START, c.connectTime());
        assertEquals(1, c.nerSuccess());
        assertEquals(1, c.chargingStatus());
        assertEquals(0, new BigDecimal("60").compareTo(c.durationSec()));
        assertEquals(0, new BigDecimal("60").compareTo(c.roundedDuration()));
        assertEquals(0, new BigDecimal("60").compareTo(c.duration1()));
        assertEquals(0, BigDecimal.ZERO.compareTo(c.duration2()));
        assertEquals(0, BigDecimal.ZERO.compareTo(c.duration3()));
        assertEquals(0, BigDecimal.ZERO.compareTo(c.pdd()));
        assertEquals(START, c.startTime());
        assertEquals(42, c.ansIdTerm());
        assertNull(c.ansIdOrig());
        assertEquals("1712", c.matchedPrefixSupplier());
        assertNull(c.matchedPrefixY());
        assertEquals(0, BigDecimal.ZERO.compareTo(c.outPartnerCost()));
        assertEquals(0, new BigDecimal("0.8").compareTo(c.supplierRate()));
        assertEquals(0, BigDecimal.ZERO.compareTo(c.tax2()));
        assertEquals("1712C", c.matchedPrefixCustomer());
        assertEquals(0, new BigDecimal("0.25").compareTo(c.zAmount()));
        assertEquals(0, new BigDecimal("0.3").compareTo(c.costAnsIn()));
        assertEquals("12.5", c.additionalSystemCodes());
        assertEquals("77", c.additionalPartyNumber());
    }

    @Test
    void the_ad_fixture_decodes_to_the_values_it_had_at_7d63ae6() {
        Cdr c = decode(AdTestSupport.batchOf(AdTestSupport.leafView(START)));

        assertEquals(0, c.switchId());
        assertEquals(61, c.inPartnerId());
        assertEquals(9, c.outPartnerId());
        assertEquals("lux-soap", c.incomingRoute());
        assertEquals("dhaka-01", c.outgoingRoute());
        assertEquals("10.20.0.1", c.originatingIp());
        assertEquals("10.10.188.40", c.terminatingIp());
        assertNull(c.countryCode());
        assertEquals(START.plusSeconds(1), c.connectTime());
        assertNull(c.nerSuccess());
        assertEquals(1, c.chargingStatus());
        assertEquals(0, new BigDecimal("15").compareTo(c.durationSec()));
        assertEquals(0, new BigDecimal("15").compareTo(c.roundedDuration()));
        assertEquals(0, new BigDecimal("15").compareTo(c.duration1()));
        assertNull(c.duration2());
        assertEquals(0, new BigDecimal("1.0").compareTo(c.pdd()));
        assertEquals(START, c.startTime());
        assertNull(c.ansIdTerm());
        assertNull(c.matchedPrefixSupplier());
        assertNull(c.matchedPrefixY());
        assertNull(c.outPartnerCost());
        assertEquals("10", c.matchedPrefixCustomer());
        assertNull(c.zAmount());
        assertNull(c.costAnsIn());
        assertNull(c.additionalSystemCodes());
        assertNull(c.additionalPartyNumber());
    }

    @Test
    void the_bc_0007_session_fixture_decodes_to_the_values_it_had_at_7d63ae6() {
        Cdr c = decode(WifiSessionTestSupport.batchOf(WifiSessionTestSupport.session(START)));

        assertEquals(0, c.switchId());
        assertEquals(7, c.inPartnerId());
        assertNull(c.outPartnerId());
        assertEquals("site-mirpur-10", c.incomingRoute());
        assertNull(c.outgoingRoute());
        assertNull(c.originatingIp());
        assertNull(c.terminatingIp());
        assertEquals(START, c.connectTime());
        assertEquals(1, c.chargingStatus());
        assertEquals(0, new BigDecimal("1800").compareTo(c.durationSec()));
        assertEquals(0, new BigDecimal("1800").compareTo(c.roundedDuration()));
        assertEquals(0, new BigDecimal("1800").compareTo(c.duration1()));
        assertEquals(START, c.startTime());
        assertNull(c.matchedPrefixY());
        assertNull(c.matchedPrefixCustomer());
        assertNull(c.zAmount());
        assertNull(c.additionalSystemCodes());
        assertNull(c.additionalPartyNumber());
    }
}
