package com.telcobright.summary.summarybeans.wifi.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The WiFi session entity's math + SQL fragments: every sum adds and scales; the average is DERIVED, never added. */
class WifiSessionSummaryTest {

    static WifiSessionSummary row() {
        WifiSessionSummary s = new WifiSessionSummary();
        s.tup_switchid = 0;
        s.tup_inpartnerid = 7;
        s.tup_site = "mirpur-10";
        s.tup_zone = "dhaka-north";
        s.tup_granttier = "paid";
        s.tup_category = 2;
        s.tup_packagecode = "wifi-30";
        s.tup_customercurrency = "TF_min";
        s.tup_causefamily = "other";
        s.tup_starttime = LocalDateTime.of(2026, 10, 16, 9, 0);
        s.sessions = 1;
        s.sessions_connected = 1;
        s.total_duration_sec = 1800;
        s.bytes_up = 1_234_567;
        s.bytes_dn = 12_345_678;
        s.customercost = new BigDecimal("30");
        s.recomputeAverage();
        return s;
    }

    @Test
    void merge_adds_every_sum_recomputes_the_average_and_leaves_the_dimensions_alone() {
        WifiSessionSummary a = row();
        WifiSessionSummary b = row();
        b.total_duration_sec = 600;
        b.bytes_up = 1_000;
        b.bytes_dn = 2_000;
        b.customercost = new BigDecimal("10");
        b.recomputeAverage();
        assertEquals(new BigDecimal("600.000000"), b.avg_session_sec);

        a.merge(b);

        assertEquals(2, a.sessions);
        assertEquals(2, a.sessions_connected);
        assertEquals(2400, a.total_duration_sec);
        assertEquals(new BigDecimal("1200.000000"), a.avg_session_sec, "2400 / 2 — derived, not 1800 + 600");
        assertEquals(1_235_567, a.bytes_up);
        assertEquals(12_347_678, a.bytes_dn);
        assertEquals(new BigDecimal("40"), a.customercost);
        assertEquals("mirpur-10", a.tup_site, "dimensions never merge");
    }

    @Test
    void a_zero_second_session_merged_in_counts_in_sessions_and_leaves_the_average_over_the_connected_ones() {
        WifiSessionSummary a = row();
        WifiSessionSummary zero = new WifiSessionSummary();
        zero.sessions = 1;
        zero.recomputeAverage();
        assertEquals(BigDecimal.ZERO, zero.avg_session_sec, "no connected session: 0, not a division by zero");

        a.merge(zero);

        assertEquals(2, a.sessions);
        assertEquals(1, a.sessions_connected);
        assertEquals(new BigDecimal("1800.000000"), a.avg_session_sec, "1800 / 1: the zero-second session is not in the average");
    }

    @Test
    void the_average_is_to_six_places_half_up() {
        WifiSessionSummary a = row();
        a.sessions_connected = 3;
        a.total_duration_sec = 1000;

        a.recomputeAverage();

        assertEquals(new BigDecimal("333.333333"), a.avg_session_sec);
    }

    @Test
    void multiply_scales_every_sum_including_sessions_the_subtract_path_and_derives_the_average_again() {
        WifiSessionSummary a = row();
        a.multiply(-1);

        assertEquals(-1, a.sessions, "sessions scale too — a subtract row takes the session back");
        assertEquals(-1, a.sessions_connected);
        assertEquals(-1800, a.total_duration_sec);
        assertEquals(-1_234_567, a.bytes_up);
        assertEquals(-12_345_678, a.bytes_dn);
        assertEquals(new BigDecimal("-30"), a.customercost);
        assertEquals(new BigDecimal("1800.000000"), a.avg_session_sec, "-1800 / -1: derived from the scaled sums");
    }

    @Test
    void tuple_key_is_the_eleven_dimension_tokens_every_one_a_column_of_the_record() {
        assertEquals(List.of("0", "7", "", "mirpur-10", "dhaka-north", "paid", "2", "wifi-30", "TF_min", "other", "2026-10-16 09:00:00"),
                row().tupleKey().tokens());
        assertEquals(row().tupleKey(), row().tupleKey(), "same dimensions -> same key");
        WifiSessionSummary money = row();
        money.tup_customercurrency = "BDT";
        assertNotEquals(row().tupleKey(), money.tupleKey(), "the unit keys apart");
        WifiSessionSummary idle = row();
        idle.tup_causefamily = "idle";
        assertNotEquals(row().tupleKey(), idle.tupleKey(), "and so does the cause family");
        WifiSessionSummary free = row();
        free.tup_granttier = "free";
        free.tup_category = 1;
        assertNotEquals(row().tupleKey(), free.tupleKey());
    }

    @Test
    void clone_with_fake_id_copies_everything_but_the_id() {
        WifiSessionSummary a = row();
        a.setId(99L);
        WifiSessionSummary c = a.cloneWithFakeId();

        assertNull(c.id(), "a clone is a fresh INSERT candidate");
        assertNotSame(a, c);
        assertEquals(a.tupleKey(), c.tupleKey());
        assertEquals(a.avg_session_sec, c.avg_session_sec);
        assertEquals(a.bytes_dn, c.bytes_dn);
        assertEquals(a.customercost, c.customercost);
    }

    @Test
    void sql_fragments_line_up_with_the_insert_columns() {
        WifiSessionSummary a = row();
        String values = a.insertValues();

        assertEquals(WifiSessionSummary.INSERT_COLUMNS.split(",").length, values.split(",").length, "one value per INSERT column");
        assertEquals("(0,7,'','mirpur-10','dhaka-north','paid',2,'wifi-30','TF_min','other','2026-10-16 09:00:00',1,1,1800,1800.000000,1234567,12345678,30)", values);
        assertEquals("sessions=1,sessions_connected=1,total_duration_sec=1800,avg_session_sec=1800.000000,bytes_up=1234567,bytes_dn=12345678,customercost=30",
                a.updateAssignments(), "UPDATE assigns the sums and the derived average only");
        assertEquals("'2026-10-16 09:00:00'", a.bucketLiteral(), "partition-pruning literal");
    }
}
