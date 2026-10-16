package com.telcobright.summary.summarybeans.call.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.summarybeans.call.internal.CdrBlobMapper;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.WifiSessionTestSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SS-0006 item 1: the blob's {@link Cdr} model reads the columns billing-core's WiFi dictionary fills on a 101 row
 * (BC-0008, {@code WifiColumns.java:82-104}) and the ratified wire's package stamps — and an older blob that has none
 * of them decodes to nulls, with no error.
 */
class CdrBlobWifiColumnsTest {

    private static final ObjectMapper MAPPER = CdrBlobMapper.create();
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 16, 9, 41);

    private static Cdr decode(byte[] batch) {
        try {
            List<CdrBlobEntry> entries = MAPPER.readValue(batch, MAPPER.getTypeFactory().constructCollectionType(List.class, CdrBlobEntry.class));
            return entries.get(0).cdr();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void a_101_blob_of_bc_0008s_shape_decodes_every_wifi_column() {
        Cdr c = decode(WifiSessionTestSupport.batchOf(WifiSessionTestSupport.measuredSession(START)));

        assertEquals(0, new BigDecimal("1234567").compareTo(c.xAmount()), "XAmount: bytes up");
        assertEquals(0, new BigDecimal("12345678").compareTo(c.yAmount()), "YAmount: bytes down");
        assertEquals(0, new BigDecimal("5000000000").compareTo(c.zAmount()), "ZAmount: the volume budget, exact");
        assertEquals("mirpur-10", c.inTrunkAdditionalInfo(), "the site");
        assertEquals("dhaka-north", c.outTrunkAdditionalInfo(), "the zone");
        assertEquals("paid", c.matchedPrefixY(), "the grant tier's word");
        assertEquals(2, c.category(), "the grant tier's number");
        assertEquals(1, c.validFlag(), "measured");
        assertEquals("aa:bb:cc:dd:ee:01", c.terminatingCallingNumber(), "the MAC");
        assertEquals("10.44.0.7", c.originatingIp(), "the device IP");
        assertEquals(0, new BigDecimal("1800").compareTo(c.duration1()), "the granted seconds");
        assertEquals(4, c.releaseCauseSystem(), "the RADIUS cause");
        assertEquals(1, c.releaseDirection(), "ended by the network");
        assertEquals(0, new BigDecimal("30").compareTo(c.packageAmount()), "the minutes taken from the bucket");
        assertEquals(5101L, c.idPackageAccount(), "the bucket");
        assertEquals("wifi-30", c.matchedPrefixCustomer(), "the package code");
        assertEquals("NORMAL_CLEARING", c.hangupCause());
    }

    @Test
    void an_older_blob_without_the_columns_decodes_them_as_null_and_nothing_fails() {
        Cdr voice = decode(CdrTestSupport.batchJson(List.of(CdrTestSupport.sg10Entry(START))));
        Cdr view = decode(AdTestSupport.batchOf(AdTestSupport.leafView(START)));
        Cdr session = decode(WifiSessionTestSupport.batchOf(WifiSessionTestSupport.session(START)));

        for (Cdr c : List.of(voice, view, session)) {
            assertNull(c.xAmount());
            assertNull(c.yAmount());
            assertNull(c.inTrunkAdditionalInfo());
            assertNull(c.outTrunkAdditionalInfo());
            assertNull(c.category());
            assertNull(c.validFlag());
            assertNull(c.releaseCauseSystem());
            assertNull(c.releaseDirection());
        }
        assertNull(voice.hangupCause(), "the voice fixture names no cause");
        assertEquals("NORMAL_CLEARING", view.hangupCause(), "the view's cause was always on the blob");
        assertEquals(0, new BigDecimal("30").compareTo(session.packageAmount()), "BC-0007's session carried the ratified wire's stamps already");
        assertEquals(5101L, session.idPackageAccount());
        assertEquals("8801711000002", session.terminatingCallingNumber(), "BC-0007 put the msisdn there; BC-0008 puts the MAC");
    }

    @Test
    void the_columns_are_read_case_insensitively_as_every_other_field_of_the_blob() {
        String lowerCase = "[{\"cdr\":{\"switchid\":0,\"starttime\":[2026,10,16,9,41],\"xamount\":5000000000,\"yamount\":7,\"intrunkadditionalinfo\":\"s\","
                + "\"category\":1,\"validflag\":0,\"hangupcause\":\"MEDIA_TIMEOUT\",\"idpackageaccount\":9},\"chargeables\":[]}]";

        Cdr c = decode(lowerCase.getBytes(StandardCharsets.UTF_8));

        assertEquals(0, new BigDecimal("5000000000").compareTo(c.xAmount()), "5 000 000 000 round-trips as itself");
        assertEquals(0, new BigDecimal("7").compareTo(c.yAmount()));
        assertEquals("s", c.inTrunkAdditionalInfo());
        assertEquals(1, c.category());
        assertEquals(0, c.validFlag());
        assertEquals("MEDIA_TIMEOUT", c.hangupCause());
        assertEquals(9L, c.idPackageAccount());
    }
}
