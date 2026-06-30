package com.telcobright.summary.beans;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.summarybeans.call.internal.CdrBlobMapper;
import com.telcobright.summary.summarybeans.call.model.CallSummary;
import com.telcobright.summary.testkit.CdrTestSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The public builder API and its enforced contract: the fluent chain assembles a fully-wired bean, the table is
 * DERIVED as {@code sum_voice_<window>_<table-suffix>} (the suffix selects a pre-provisioned set; service-group
 * does NOT name the table, it only filters), each builder fixes the right window, and the shared {@code build()}
 * requires both a service group and a table suffix.
 */
class SummaryBeanBuilderTest {

    private static final ObjectMapper MAPPER = CdrBlobMapper.create();

    @Test
    void daily_builder_assembles_a_daily_call_bean() {
        SummaryBean<CallSummary> daily = DailySummaryBuilder.create(MAPPER)
                .serviceGroup(10).tableSuffix("3").context("mediationContext").build();

        assertEquals("dailyCallSummary", daily.name());
        assertEquals("cdr", daily.entityType());
        assertEquals("sum_voice_day_3", daily.table());
        // window fixed to daily: a 14:30 call buckets to day-start
        assertEquals(LocalDateTime.of(2026, 6, 19, 0, 0),
                daily.window().bucketStart(LocalDateTime.of(2026, 6, 19, 14, 30)));
    }

    @Test
    void hourly_builder_assembles_an_hourly_call_bean() {
        SummaryBean<CallSummary> hourly = HourlySummaryBuilder.create(MAPPER)
                .serviceGroup(10).tableSuffix("3").build();

        assertEquals("hourlyCallSummary", hourly.name());
        assertEquals("sum_voice_hr_3", hourly.table());
        // window fixed to hourly: the same call buckets to hour-start
        assertEquals(LocalDateTime.of(2026, 6, 19, 14, 0),
                hourly.window().bucketStart(LocalDateTime.of(2026, 6, 19, 14, 30)));
    }

    @Test
    void table_is_window_token_plus_suffix_independent_of_service_group() {
        // the SUFFIX names the table; service group does not appear in it
        assertEquals("sum_voice_day_3", DailySummaryBuilder.create(MAPPER).serviceGroup(10).tableSuffix("3").build().table());
        assertEquals("sum_voice_day_2", DailySummaryBuilder.create(MAPPER).serviceGroup(11).tableSuffix("2").build().table());
        assertEquals("sum_voice_hr_1", HourlySummaryBuilder.create(MAPPER).serviceGroup(10).tableSuffix("1").build().table());
        // a zero-padded suffix is used verbatim (to match legacy names)
        assertEquals("sum_voice_day_03", DailySummaryBuilder.create(MAPPER).serviceGroup(10).tableSuffix("03").build().table());
    }

    @Test
    void service_group_setting_flows_into_the_bean_filter() {
        LocalDateTime t = CdrTestSupport.at(2026, 6, 19, 14, 30);
        byte[] mixed = CdrTestSupport.batchJson(List.of(CdrTestSupport.sg10Entry(t), CdrTestSupport.sg11Entry(t)));

        SummaryBean<CallSummary> sg10 = DailySummaryBuilder.create(MAPPER).serviceGroup(10).tableSuffix("3").build();
        SummaryBean<CallSummary> sg11 = DailySummaryBuilder.create(MAPPER).serviceGroup(11).tableSuffix("2").build();

        assertEquals(1, sg10.buildBatch(mixed).size(), "SG10 builder keeps only the SG10 record");
        assertEquals(1, sg11.buildBatch(mixed).size(), "SG11 builder keeps only the SG11 record");
    }

    @Test
    void build_without_a_service_group_is_rejected_by_the_contract() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DailySummaryBuilder.create(MAPPER).tableSuffix("3").build());
        assertTrue(e.getMessage().contains("service group"), "the shared contract requires a service group");
    }

    @Test
    void build_without_a_table_suffix_is_rejected_by_the_contract() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DailySummaryBuilder.create(MAPPER).serviceGroup(10).build());
        assertTrue(e.getMessage().contains("table suffix"), "the shared contract requires a table suffix");
    }
}
