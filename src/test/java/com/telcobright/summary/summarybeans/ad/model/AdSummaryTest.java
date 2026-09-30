package com.telcobright.summary.summarybeans.ad.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ad entity's math + SQL fragments — net-new (design §2.9), so multiply scales ALL measures. */
class AdSummaryTest {

    static AdSummary row() {
        AdSummary s = new AdSummary();
        s.tup_tenant = "res_44";
        s.tup_partnerid = 61;
        s.tup_campaignid = 5;
        s.tup_rulecode = "1001";
        s.tup_zone = "dhaka-01";
        s.tup_site = "";
        s.tup_app = "wifi";
        s.tup_mediakind = "video";
        s.tup_outcome = "done";
        s.tup_starttime = LocalDateTime.of(2026, 9, 29, 0, 0);
        s.views = 1;
        s.shown = 1;
        s.completed = 1;
        s.failed = 0;
        s.watchedsec = 15;
        s.chargedamount = new BigDecimal("0.50");
        return s;
    }

    @Test
    void merge_adds_every_measure_and_leaves_dimensions_alone() {
        AdSummary a = row();
        a.merge(row());

        assertEquals(2, a.views);
        assertEquals(2, a.shown);
        assertEquals(2, a.completed);
        assertEquals(0, a.failed);
        assertEquals(30, a.watchedsec);
        assertEquals(new BigDecimal("1.00"), a.chargedamount);
        assertEquals("res_44", a.tup_tenant, "dimensions never merge");
        assertEquals(61, a.tup_partnerid);
    }

    @Test
    void multiply_scales_all_measures_including_views_the_subtract_path() {
        AdSummary a = row();
        a.multiply(-1);

        assertEquals(-1, a.views, "views scale too — a subtract row takes the view back");
        assertEquals(-1, a.shown);
        assertEquals(-15, a.watchedsec);
        assertEquals(new BigDecimal("-0.50"), a.chargedamount);
    }

    @Test
    void tuple_key_is_the_ten_dimension_tokens_tenant_first() {
        assertEquals(java.util.List.of("res_44", "61", "5", "1001", "dhaka-01", "", "wifi", "video", "done", "2026-09-29 00:00:00"),
                row().tupleKey().tokens());
        assertEquals(row().tupleKey(), row().tupleKey(), "same dimensions -> same key");
        AdSummary other = row();
        other.tup_tenant = "btcl";
        other.tup_partnerid = 44;
        assertTrue(!row().tupleKey().equals(other.tupleKey()), "the tier above keys separately: one row per tier");
    }

    @Test
    void clone_with_fake_id_copies_everything_but_the_id() {
        AdSummary a = row();
        a.setId(99L);
        AdSummary c = a.cloneWithFakeId();

        assertNull(c.id(), "a clone is a fresh INSERT candidate");
        assertNotSame(a, c);
        assertEquals(a.tupleKey(), c.tupleKey());
        assertEquals(a.views, c.views);
        assertEquals(a.chargedamount, c.chargedamount);
    }

    @Test
    void sql_fragments_line_up_with_the_insert_columns() {
        AdSummary a = row();
        String values = a.insertValues();

        assertEquals(AdSummary.INSERT_COLUMNS.split(",").length, values.split(",").length, "one value per INSERT column");
        assertTrue(values.startsWith("('res_44',61,5,'1001','dhaka-01','','wifi','video','done','2026-09-29 00:00:00',1,1,1,0,0,15,0.50)"),
                "dimension order matches the column list: " + values);
        assertEquals("views=1,shown=1,completed=1,credited=0,failed=0,watchedsec=15,chargedamount=0.50", a.updateAssignments(),
                "UPDATE assigns measures only");
        assertEquals("'2026-09-29 00:00:00'", a.bucketLiteral(), "partition-pruning literal");
    }

    @Test
    void a_quote_in_a_key_string_is_escaped_never_broken() {
        AdSummary a = row();
        a.tup_site = "o'neil";
        assertTrue(a.insertValues().contains("'o''neil'"), a.insertValues());
    }
}
