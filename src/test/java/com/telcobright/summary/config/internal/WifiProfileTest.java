package com.telcobright.summary.config.internal;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.summarybeans.call.CallSummaries;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Brief S4's "done when": the profile of the wifi tenant lists the beans of service group 30 — the ad beans, the
 * call bean of group 30 (config-instantiated: a profile, no class) and the chargeable beans. The profile file is
 * read exactly as the service reads it ({@link ProfileYamlLoader}); the two call entries are then built the way
 * the bootstrap builds a config-instantiated bean, so a wrong key in the file is red here and not at a start.
 */
class WifiProfileTest {

    private static final Map<String, String> PROFILE = ProfileYamlLoader.loadProfile(new ProfileYamlLoader.ActiveTenant("btcl", "lab"));

    @Test
    void the_wifi_tenants_profile_lists_every_bean_that_sums_service_group_30() {
        List<String> enabled = Arrays.asList(PROFILE.get("summary.enabledSummary").split(","));

        assertTrue(enabled.containsAll(List.of("dailyAdSummary", "hourlyAdSummary")), "the ad category: " + enabled);
        assertTrue(enabled.containsAll(List.of("dailyCallSummarySg30", "hourlyCallSummarySg30")), "the call category for group 30: " + enabled);
        assertTrue(enabled.containsAll(List.of("dailyChargeableSummary", "hourlyChargeableSummary")), "the chargeable category: " + enabled);
        assertEquals(6, enabled.size(), "and nothing else: a bean of group 10 or 11 has no record to sum in an ad tenant");
    }

    @Test
    void the_call_bean_of_group_30_is_made_from_the_profile_alone() {
        for (String[] bean : new String[][] {{"dailyCallSummarySg30", "sum_voice_day_30"}, {"hourlyCallSummarySg30", "sum_voice_hr_30"}}) {
            String prefix = "summary.beans." + bean[0] + ".";
            assertEquals("30", PROFILE.get(prefix + "service-group"), bean[0]);

            SummaryBean<?> built = CallSummaries.forWindow(bean[0], PROFILE.get(prefix + "window"), PROFILE.get(prefix + "table-suffix"),
                    Integer.parseInt(PROFILE.get(prefix + "service-group")), PROFILE.get(prefix + "context"));

            assertEquals(bean[1], built.table());
            assertEquals(bean[0], built.name(), "its own name: its own bookmark and worker");
            assertEquals("cdr", built.entityType());
        }
    }

    @Test
    void the_wifi_tenant_is_registered_and_not_the_active_one() {
        // tenants.yml selects the build's tenant: tcbl/dev stays the first enabled; btcl/lab is chosen at a start
        assertEquals(new ProfileYamlLoader.ActiveTenant("tcbl", "dev"), ProfileYamlLoader.activeTenant("config/tenants.yml").orElseThrow());
        assertFalse(PROFILE.isEmpty(), "config/tenants/btcl/lab/profile-lab.yml is on the class path");
        assertEquals("cdr", PROFILE.get("summary.outbox.entity-type"));
    }
}
