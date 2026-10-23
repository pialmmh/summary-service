package com.telcobright.summary.config.internal;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryKind;
import com.telcobright.summary.registry.internal.ConfigInstantiatedBeans;
import com.telcobright.summary.registry.internal.KnownKinds;
import com.telcobright.summary.registry.internal.TenantKinds;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SS-0008 item 2: the profiles the jar ships START under the kinds — every bean each enables is of a kind it enables. The
 * voice deployment's (tcbl/dev) names no kind (= the call alone) and needs none; the wifi tenant's lab and bed profiles
 * (btcl/lab, btcl/radius2) name the three. A bed whose rendered profile lacks the line is refused at its start, in words.
 */
class ProfileKindsTest {

    private static Map<String, String> profile(String tenant, String name) {
        return ProfileYamlLoader.loadProfile(new ProfileYamlLoader.ActiveTenant(tenant, name));
    }

    private static Config configOf(Map<String, String> profile) {
        return new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(profile, "the profile", 300)).build();
    }

    /** The kind of each bean the profile enables: config-instantiated as the bootstrap makes it, a catalog class by its declaration (a voice class by its group). */
    private static List<String> kindsOfTheEnabledBeans(Map<String, String> profile) {
        Config config = configOf(profile);
        List<String> kinds = new ArrayList<>();
        for (String name : config.getOptionalValues("summary.enabledSummary", String.class).orElse(List.of())) {
            SummaryBean<?> made = ConfigInstantiatedBeans.from(config, name);
            if (made != null) {
                kinds.add(name + "=" + KnownKinds.kindOf(made).word());
                continue;
            }
            SummaryKind declaring = KnownKinds.all().stream().filter(kind -> kind.catalogBeans().contains(name)).findFirst().orElseThrow();
            String group = profile.get("summary.beans." + name + ".service-group");
            kinds.add(name + "=" + (group == null ? declaring.word() : KnownKinds.ofServiceGroup(Integer.parseInt(group)).word()));
        }
        return kinds;
    }

    private static void assertStarts(String tenant, String name) {
        Map<String, String> profile = profile(tenant, name);
        TenantKinds enabled = TenantKinds.of(configOf(profile));
        for (String beanAndKind : kindsOfTheEnabledBeans(profile)) {
            String kind = beanAndKind.substring(beanAndKind.indexOf('=') + 1);
            assertTrue(enabled.words().contains(kind), tenant + "/" + name + ": " + beanAndKind + " is of a kind the profile does not enable " + enabled.words());
        }
    }

    @Test
    void the_voice_profile_names_no_kind_and_every_bean_it_enables_is_the_calls() {
        assertEquals(List.of("call"), TenantKinds.of(configOf(profile("tcbl", "dev"))).words());
        assertEquals(List.of("dailyCallSummary=call", "hourlyCallSummary=call", "dailyCallSummarySg11=call", "hourlyCallSummarySg11=call",
                "dailyChargeableSummary=call", "hourlyChargeableSummary=call"), kindsOfTheEnabledBeans(profile("tcbl", "dev")));
        assertStarts("tcbl", "dev");
    }

    @Test
    void the_wifi_tenants_lab_and_bed_profiles_name_the_three_kinds_and_their_twelve_beans_are_of_them() {
        for (String name : List.of("lab", "radius2")) {
            assertEquals(List.of("call", "ad", "wifi"), TenantKinds.of(configOf(profile("btcl", name))).words(), name);
            assertEquals(List.of("dailyAdSummary=ad", "hourlyAdSummary=ad", "dailyCallSummarySg30=ad", "hourlyCallSummarySg30=ad",
                    "dailyCallSummarySg101=wifi", "hourlyCallSummarySg101=wifi", "dailyWifiSessionSummary=wifi", "hourlyWifiSessionSummary=wifi",
                    "dailyWifiDstSummary=wifi", "hourlyWifiDstSummary=wifi", "dailyChargeableSummary=call", "hourlyChargeableSummary=call"),
                    kindsOfTheEnabledBeans(profile("btcl", name)), name);
            assertStarts("btcl", name);
        }
    }
}
