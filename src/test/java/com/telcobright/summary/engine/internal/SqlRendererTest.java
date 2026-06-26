package com.telcobright.summary.engine.internal;

import com.telcobright.summary.bean.spi.WindowDef;
import com.telcobright.summary.beans.cdr.CdrVoiceSummaryBean;
import com.telcobright.summary.beans.cdr.RatedCdrEvent;
import com.telcobright.summary.engine.spi.SummaryRow;
import com.telcobright.summary.engine.spi.WindowSchema;
import com.telcobright.summary.testkit.CdrTestSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The generic SQL rendering: column/value parity, quoting, null-string -> '', and id-targeted updates. */
class SqlRendererTest {

    private final CdrVoiceSummaryBean bean = CdrTestSupport.bean();
    private final WindowDef dayWindow = bean.windows().get(0);
    private final WindowSchema schema = WindowSchemaFactory.build(bean, dayWindow);

    @Test
    void insert_header_and_value_tuple_have_the_same_column_count() {
        SummaryRow row = RowFactory.delta(bean, dayWindow, CdrTestSupport.sg10Call(CdrTestSupport.at(2026, 6, 19, 14, 0)));

        String header = SqlRenderer.insertHeader(schema);
        String tuple = SqlRenderer.insertTuple(schema, row);

        long headerColumns = header.substring(header.indexOf('(') + 1, header.indexOf(')')).split(",").length;
        long tupleValues = tuple.substring(1, tuple.length() - 1).split(",").length;
        assertEquals(schema.columns().size(), headerColumns);
        assertEquals(headerColumns, tupleValues, "one value per column");
    }

    @Test
    void values_render_numbers_dates_strings_and_null_strings() {
        // an SG10 call but with a NULL incoming route -> normalized to '' -> rendered ''
        RatedCdrEvent call = new RatedCdrEvent(
                CdrTestSupport.at(2026, 6, 19, 14, 0).toInstant().toEpochMilli(),
                1, 5, 0, null, "out",
                new BigDecimal("1.500000"), new BigDecimal("0.8"),
                "1.1.1.1", "2.2.2.2", "880", "1712", "1712", "0", "42", "BDT", "BDT",
                1, true, new BigDecimal("60"), new BigDecimal("60"), new BigDecimal("60"),
                new BigDecimal("1.0"), BigDecimal.ZERO, new BigDecimal("0.5"), BigDecimal.ZERO);
        SummaryRow row = RowFactory.delta(bean, dayWindow, call);

        String tuple = SqlRenderer.insertTuple(schema, row);

        assertTrue(tuple.contains("'2026-06-19 00:00:00'"), "day bucket datetime quoted");
        assertTrue(tuple.contains("'880'"), "country code string quoted");
        assertTrue(tuple.contains("''"), "null incoming route -> ''");
        assertTrue(tuple.contains("1.5"), "decimal key canonicalized (1.500000 -> 1.5)");
    }

    @Test
    void update_sets_counters_and_targets_by_id() {
        SummaryRow row = RowFactory.delta(bean, dayWindow, CdrTestSupport.sg10Call(CdrTestSupport.at(2026, 6, 19, 14, 0)));
        row.setId(100L);

        String update = SqlRenderer.updateStatement(schema, row);

        assertTrue(update.startsWith("update " + CdrTestSupport.DAY_TABLE + " set "));
        assertTrue(update.contains("totalcalls=1"));
        assertTrue(update.endsWith("where id=100"));
    }
}
