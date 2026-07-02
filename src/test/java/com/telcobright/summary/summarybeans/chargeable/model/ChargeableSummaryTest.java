package com.telcobright.summary.summarybeans.chargeable.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The chargeable entity's math + SQL fragments — net-new (§4), so multiply scales ALL measures (no quirk). */
class ChargeableSummaryTest {

    private static ChargeableSummary row() {
        ChargeableSummary s = new ChargeableSummary();
        s.tup_servicegroup = 10;
        s.tup_servicefamily = 10;
        s.tup_assigneddirection = 1;
        s.tup_productid = 7001L;
        s.tup_billeduom = "BDT";
        s.tup_prefix = "1712";
        s.tup_transactiontime = LocalDateTime.of(2026, 6, 19, 0, 0);
        s.totalcount = 1;
        s.BilledAmount = new BigDecimal("1.5");
        s.Quantity = new BigDecimal("60");
        s.TaxAmount1 = new BigDecimal("0.5");
        s.OtherDecAmount3 = new BigDecimal("0.25");
        return s;
    }

    @Test
    void merge_adds_every_measure_and_leaves_dimensions_alone() {
        ChargeableSummary a = row();
        a.merge(row());

        assertEquals(2, a.totalcount);
        assertEquals(new BigDecimal("3.0"), a.BilledAmount);
        assertEquals(new BigDecimal("120"), a.Quantity);
        assertEquals(new BigDecimal("1.0"), a.TaxAmount1);
        assertEquals(new BigDecimal("0.50"), a.OtherDecAmount3);
        assertEquals(10, a.tup_servicegroup, "dimensions never merge");
    }

    @Test
    void multiply_scales_all_measures_including_totalcount() {
        ChargeableSummary a = row();
        a.multiply(-1);

        assertEquals(-1, a.totalcount, "totalcount scales too — net-new has NO legacy omission quirk");
        assertEquals(new BigDecimal("-1.5"), a.BilledAmount);
        assertEquals(new BigDecimal("-0.5"), a.TaxAmount1);
    }

    @Test
    void tuple_key_is_the_seven_dimension_tokens() {
        assertEquals(java.util.List.of("10", "10", "1", "7001", "BDT", "1712", "2026-06-19 00:00:00"),
                row().tupleKey().tokens());
        assertEquals(row().tupleKey(), row().tupleKey(), "same dimensions -> same key");
    }

    @Test
    void clone_with_fake_id_copies_everything_but_the_id() {
        ChargeableSummary a = row();
        a.setId(99L);
        ChargeableSummary c = a.cloneWithFakeId();

        assertNull(c.id(), "a clone is a fresh INSERT candidate");
        assertNotSame(a, c);
        assertEquals(a.tupleKey(), c.tupleKey());
        assertEquals(a.totalcount, c.totalcount);
        assertEquals(a.BilledAmount, c.BilledAmount);
    }

    @Test
    void sql_fragments_line_up_with_the_insert_columns() {
        ChargeableSummary a = row();
        String values = a.insertValues();

        assertEquals(ChargeableSummary.INSERT_COLUMNS.split(",").length,
                values.split(",").length, "one value per INSERT column");
        assertTrue(values.startsWith("(10,10,1,7001,'BDT','1712','2026-06-19 00:00:00',1,1.5,"),
                "dimension order matches the column list: " + values);
        assertTrue(a.updateAssignments().startsWith("totalcount=1,BilledAmount=1.5,"),
                "UPDATE assigns measures only");
        assertEquals("'2026-06-19 00:00:00'", a.bucketLiteral(), "partition-pruning literal");
    }
}
