package com.telcobright.summary.config.internal;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ARCH-0102 / SS-0009 item 3 — the AD category's summary-service: the bundled profile {@code btcl/ad-radius2} pinned value by
 * value: the store {@code ad_services}, kinds = ad, the beans sum_ad_* + sum_chargeable_* only, the category word, the ping topic
 * per category, secrets by the NAME of a variable; and nothing of the pre-split bed's record store or ping in common.
 */
class AdPairProfileTest {

    private static Map<String, String> profile(String name) {
        return ProfileYamlLoader.loadProfile(new ProfileYamlLoader.ActiveTenant("btcl", name));
    }

    private static List<String> listOf(String commaJoined) {
        return Arrays.stream(commaJoined.split(",")).map(String::trim).toList();
    }

    @Test
    void the_store_is_the_ad_categorys_services_database_with_the_password_by_name_only() {
        Map<String, String> adPair = profile("ad-radius2");
        assertEquals("postgresql", adPair.get("summary.store.kind"));
        assertEquals("jdbc:postgresql://127.0.0.1:5434/ad_services", adPair.get("summary.store.url"), "the ad category's records — never the switch database");
        assertEquals("summary_service", adPair.get("summary.store.username"));
        assertEquals("env:TENANT_BTCL_AD_SUMMARY_SERVICE_PASSWORD", adPair.get("summary.store.password-ref"), "the NAME of the variable");
        assertNull(adPair.get("summary.store.password"), "never a value in the file");
    }

    @Test
    void the_kinds_the_category_and_the_beans_are_the_ad_categorys_alone() {
        Map<String, String> adPair = profile("ad-radius2");
        assertEquals(List.of("ad"), listOf(adPair.get("summary.kinds")));
        assertEquals("ad", adPair.get("summary.category"), "names the consumer groups: summary-service-ad-ping-…, summary-service-ad-doorbell-…");
        assertEquals(List.of("dailyAdSummary", "hourlyAdSummary", "dailyChargeableSummary", "hourlyChargeableSummary"),
                listOf(adPair.get("summary.enabledSummary")), "the sum_ad_* and sum_chargeable_* beans only");
        assertTrue(adPair.keySet().stream().noneMatch(key -> key.startsWith("summary.beans.")), "no config-instantiated bean: no call-shaped 30/101, no WiFi bean");
        assertEquals("true", adPair.get("summary.autostart"));
    }

    @Test
    void the_ping_is_the_categorys_and_the_tree_and_the_doorbell_are_the_roots() {
        Map<String, String> adPair = profile("ad-radius2");
        assertEquals("cdr_summary_ping_ad_btcl", adPair.get("summary.outbox.ping-topic"), "the same name in the ad billing-core's billing.summary.ping-topic");
        assertEquals("tree", adPair.get("summary.tenants.mode"));
        assertEquals("btcl", adPair.get("summary.tenants.root"));
        assertEquals("config_event_loader", adPair.get("summary.tenants.doorbell.topic-base"), "prime-context rings ONE topic per root");
        assertEquals("http://10.10.188.2:7091", adPair.get("summary.tenants.prime-context.base-url"));
    }

    @Test
    void nothing_of_the_pre_split_beds_record_store_or_ping_is_shared() {
        Map<String, String> adPair = profile("ad-radius2");
        Map<String, String> preSplit = profile("radius2");
        assertNotEquals(preSplit.get("summary.store.url"), adPair.get("summary.store.url"));
        assertTrue(preSplit.get("summary.store.url").endsWith("/routesphere"), "the pre-split bed read the switch database");
        assertNotEquals(preSplit.get("summary.outbox.ping-topic"), adPair.get("summary.outbox.ping-topic"));
        assertEquals(preSplit.get("summary.tenants.doorbell.topic-base"), adPair.get("summary.tenants.doorbell.topic-base"), "the root's one doorbell topic, shared by design");
        assertNull(preSplit.get("summary.category"), "the pre-split profile names no category: its groups are the names of before");
    }

    @Test
    void the_profile_file_holds_no_secret_value_only_the_names_of_variables() throws Exception {
        String yaml;
        try (InputStream in = AdPairProfileTest.class.getClassLoader().getResourceAsStream("config/tenants/btcl/ad-radius2/profile-ad-radius2.yml")) {
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertFalse(Pattern.compile("(?m)^\\s*password:\\s*\\S").matcher(yaml).find(), "a password: key with a value");
        assertFalse(Pattern.compile("(?m)^\\s*token:\\s*\\S").matcher(yaml).find(), "a token: key with a value");
        assertTrue(Pattern.compile("(?m)^\\s*password-ref:\\s*\"?env:[A-Z0-9_]+").matcher(yaml).find());
        assertFalse(Pattern.compile("(?m)^\\s*token-ref:\\s*\"?(?!env:)").matcher(yaml).find(), "a token-ref that is not env:<NAME>");
    }
}
