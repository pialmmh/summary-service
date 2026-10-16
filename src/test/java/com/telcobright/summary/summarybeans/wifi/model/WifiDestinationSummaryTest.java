package com.telcobright.summary.summarybeans.wifi.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The WiFi destination entity's math + SQL fragments — net-new, so multiply scales ALL sums; no money, no devices. */
class WifiDestinationSummaryTest {

    static WifiDestinationSummary row() {
        WifiDestinationSummary s = new WifiDestinationSummary();
        s.tup_gw = "bras-2";
        s.tup_site = "mirpur-10";
        s.tup_zone = "dhaka-north";
        s.tup_remote = "142.250.74.78";
        s.tup_proto = 6;
        s.tup_port = 443;
        s.tup_starttime = LocalDateTime.of(2026, 10, 16, 15, 0);
        s.bytes_up = 15_200;
        s.bytes_dn = 410_300;
        s.packets_up = 120;
        s.packets_dn = 340;
        s.flows = 1;
        return s;
    }

    @Test
    void merge_adds_every_sum_and_leaves_the_dimensions_alone() {
        WifiDestinationSummary a = row();
        a.merge(row());

        assertEquals(30_400, a.bytes_up);
        assertEquals(820_600, a.bytes_dn);
        assertEquals(240, a.packets_up);
        assertEquals(680, a.packets_dn);
        assertEquals(2, a.flows);
        assertEquals("142.250.74.78", a.tup_remote, "dimensions never merge");
    }

    @Test
    void multiply_scales_every_sum_including_flows_the_subtract_path() {
        WifiDestinationSummary a = row();
        a.multiply(-1);

        assertEquals(-15_200, a.bytes_up);
        assertEquals(-410_300, a.bytes_dn);
        assertEquals(-120, a.packets_up);
        assertEquals(-340, a.packets_dn);
        assertEquals(-1, a.flows, "flows scale too — a subtract row takes the row back");
    }

    @Test
    void tuple_key_is_the_seven_tokens_gateway_first_the_port_and_the_protocol_apart() {
        assertEquals(List.of("bras-2", "mirpur-10", "dhaka-north", "142.250.74.78", "6", "443", "2026-10-16 15:00:00"), row().tupleKey().tokens());
        assertEquals(row().tupleKey(), row().tupleKey());
        WifiDestinationSummary udp = row();
        udp.tup_proto = 17;
        assertNotEquals(row().tupleKey(), udp.tupleKey(), "the protocol keys apart");
        WifiDestinationSummary otherPort = row();
        otherPort.tup_port = 80;
        assertNotEquals(row().tupleKey(), otherPort.tupleKey(), "and so does the port — a report rolls ports up when it wants an address only");
        WifiDestinationSummary otherGateway = row();
        otherGateway.tup_gw = "bras-1";
        assertNotEquals(row().tupleKey(), otherGateway.tupleKey());
    }

    @Test
    void clone_with_fake_id_copies_everything_but_the_id() {
        WifiDestinationSummary a = row();
        a.setId(99L);
        WifiDestinationSummary c = a.cloneWithFakeId();

        assertNull(c.id());
        assertNotSame(a, c);
        assertEquals(a.tupleKey(), c.tupleKey());
        assertEquals(a.bytes_dn, c.bytes_dn);
        assertEquals(a.flows, c.flows);
    }

    @Test
    void sql_fragments_line_up_with_the_insert_columns() {
        WifiDestinationSummary a = row();
        String values = a.insertValues();

        assertEquals(WifiDestinationSummary.INSERT_COLUMNS.split(",").length, values.split(",").length, "one value per INSERT column");
        assertEquals("('bras-2','mirpur-10','dhaka-north','142.250.74.78',6,443,'2026-10-16 15:00:00',15200,410300,120,340,1)", values);
        assertEquals("bytes_up=15200,bytes_dn=410300,packets_up=120,packets_dn=340,flows=1", a.updateAssignments(), "UPDATE assigns the sums only");
        assertEquals("'2026-10-16 15:00:00'", a.bucketLiteral());
    }
}
