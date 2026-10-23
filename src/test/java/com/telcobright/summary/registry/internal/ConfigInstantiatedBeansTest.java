package com.telcobright.summary.registry.internal;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.summarybeans.call.internal.CallSummaryBean;
import com.telcobright.summary.summarybeans.wifi.internal.WifiDestinationSummaryBean;
import com.telcobright.summary.summarybeans.wifi.internal.WifiSessionSummaryBean;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SS-0006 item 4: the one optional key {@code summary.beans.<name>.kind} — {@code wifi-session} | {@code wifi-dst} makes a
 * WiFi bean of the window; absent makes the call bean exactly as before; any other word is refused in words.
 */
class ConfigInstantiatedBeansTest {

    private static Config config(Map<String, String> keys) {
        return new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(keys, "a profile", 300)).build();
    }

    @Test
    void kind_wifi_session_makes_the_session_bean_of_the_window_with_the_group_and_the_suffix_fixed_by_the_kind() {
        SummaryBean<?> daily = ConfigInstantiatedBeans.from(config(Map.of("summary.beans.d.window", "daily", "summary.beans.d.kind", "wifi-session")), "d");
        SummaryBean<?> hourly = ConfigInstantiatedBeans.from(config(Map.of("summary.beans.h.window", "hourly", "summary.beans.h.kind", "wifi-session")), "h");

        assertInstanceOf(WifiSessionSummaryBean.class, daily);
        assertEquals("sum_wifi_day_101", daily.table());
        assertEquals("sum_wifi_hr_101", hourly.table());
        assertEquals("cdr", daily.entityType());
        assertEquals("d", daily.name());
        assertEquals("daily", daily.window().toString());
    }

    @Test
    void kind_wifi_dst_makes_the_destination_bean_of_the_window_on_the_wifi_flow_entity() {
        SummaryBean<?> hourly = ConfigInstantiatedBeans.from(config(Map.of("summary.beans.x.window", "hourly", "summary.beans.x.kind", "wifi-dst")), "x");

        assertInstanceOf(WifiDestinationSummaryBean.class, hourly);
        assertEquals("sum_wifi_dst_hr", hourly.table());
        assertEquals("wifi_flow", hourly.entityType());
        assertEquals("x", hourly.name());
    }

    @Test
    void the_kind_is_read_whatever_its_case_or_blanks() {
        assertEquals("sum_wifi_day_101", ConfigInstantiatedBeans.from(config(Map.of("summary.beans.d.window", "daily", "summary.beans.d.kind", " Wifi-Session ")), "d").table());
    }

    @Test
    void no_kind_makes_the_call_bean_exactly_as_before() {
        SummaryBean<?> call = ConfigInstantiatedBeans.from(config(Map.of("summary.beans.c.window", "daily", "summary.beans.c.service-group", "30",
                "summary.beans.c.table-suffix", "30", "summary.beans.c.context", "mediationContext")), "c");

        assertInstanceOf(CallSummaryBean.class, call);
        assertEquals("sum_voice_day_30", call.table());
        assertEquals("cdr", call.entityType());
        assertEquals("mediationContext", call.contextName());
    }

    @Test
    void a_call_bean_with_no_service_group_is_none_as_before_and_a_name_with_no_window_is_not_config_instantiated() {
        assertNull(ConfigInstantiatedBeans.from(config(Map.of("summary.beans.c.window", "daily")), "c"), "the group is required: said, none made");
        assertNull(ConfigInstantiatedBeans.from(config(Map.of("summary.beans.c.kind", "wifi-session")), "c"), "a kind with no window is not this road's");
        assertNull(ConfigInstantiatedBeans.from(config(Map.of()), "c"));
    }

    @Test
    void an_unknown_kind_is_refused_in_words_that_name_the_two_kinds() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ConfigInstantiatedBeans.from(config(Map.of("summary.beans.w.window", "daily", "summary.beans.w.kind", "wifi-nope")), "w"));

        assertEquals("summary.beans.w.kind is 'wifi-nope' — it must be wifi-session or wifi-dst (or absent: a call bean, as before)", refused.getMessage());
    }

    @Test
    void the_kind_never_makes_a_call_bean_the_mutant_that_ignores_it_is_red_here() {
        // the item's mutant: the kind ignored -> the bean built as a call bean (which would need a table-suffix and keep no WiFi column)
        SummaryBean<?> session = ConfigInstantiatedBeans.from(config(Map.of("summary.beans.s.window", "daily", "summary.beans.s.kind", "wifi-session",
                "summary.beans.s.service-group", "30", "summary.beans.s.table-suffix", "30")), "s");

        assertInstanceOf(WifiSessionSummaryBean.class, session, "the kind decides, whatever call keys stand beside it");
        assertTrue(session.insertColumnsCsv().contains("avg_session_sec"), "a WiFi session row, not a voice row");
        assertEquals("sum_wifi_day_101", session.table(), "never sum_voice_day_30");
    }
}
