package com.telcobright.summary.summarybeans.call;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.summarybeans.call.model.CallSummary;
import com.telcobright.summary.testkit.CdrTestSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The config-instantiated path (§12g): an extra call bean under its OWN name — how the second service group
 * (legacy summarised SG10 AND SG11 in parallel) gets its own worker/offset/table without a per-SG class.
 */
class CallSummariesTest {

    @Test
    void for_window_assembles_a_named_sg11_bean_with_its_own_table() {
        SummaryBean<CallSummary> sg11Daily = CallSummaries.forWindow("dailyCallSummarySg11", "daily", "2", 11, null);

        assertEquals("dailyCallSummarySg11", sg11Daily.name(), "its own name = its own offset bookmark");
        assertEquals("cdr", sg11Daily.entityType());
        assertEquals("sum_voice_day_2", sg11Daily.table(), "suffix 2 -> the SG11 pre-provisioned set");
        assertEquals(LocalDateTime.of(2026, 6, 19, 0, 0),
                sg11Daily.window().bucketStart(LocalDateTime.of(2026, 6, 19, 14, 30)));
    }

    @Test
    void the_sg11_bean_keeps_only_sg11_records() {
        LocalDateTime t = CdrTestSupport.at(2026, 6, 19, 14, 30);
        byte[] mixed = CdrTestSupport.batchJson(List.of(CdrTestSupport.sg10Entry(t), CdrTestSupport.sg11Entry(t)));

        SummaryBean<CallSummary> sg11Hourly = CallSummaries.forWindow("hourlyCallSummarySg11", "hourly", "2", 11, null);
        List<CallSummary> built = sg11Hourly.buildBatch(mixed);

        assertEquals(1, built.size(), "the SG10 record is filtered out");
        assertEquals(LocalDateTime.of(2026, 6, 19, 14, 0), built.get(0).tup_starttime, "hourly bucket");
        assertEquals("sum_voice_hr_2", sg11Hourly.table());
    }

    @Test
    void an_unknown_window_token_is_rejected_at_assembly() {
        assertThrows(RuntimeException.class,
                () -> CallSummaries.forWindow("badBean", "fortnightly", "2", 11, null),
                "a bad window: value must fail at activation, not at first drain");
    }
}
