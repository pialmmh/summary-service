package com.telcobright.summary.registry.internal;

import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.outbox.api.OutboxReader;
import com.telcobright.summary.ping.internal.PingListener;
import com.telcobright.summary.registry.api.SummaryBeanRegistry;
import com.telcobright.summary.tenancy.api.TenantWatcher;
import com.telcobright.summary.tenancy.internal.DoorbellListener;
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ARCH-0102 / SS-0009 item 3: one summary-service per service CATEGORY, each on one set of brokers — so each one's consumer groups
 * carry its category's name ({@code summary.category}): {@code summary-service-<category>-ping-<uuid>} and
 * {@code summary-service-<category>-doorbell-<uuid>}; unique per start as before (a ping and a ring are broadcasts). No category
 * named = the names of before.
 */
class CategoryNamedGroupsTest {

    private static Config configOf(Map<String, String> keys) {
        return new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(keys, "the profile", 300)).build();
    }

    private static SummaryBeanRegistry aRegistry() {
        return new SummaryBeanRegistry(new OutboxReader(new FakeUnitOfWorkFactory(), new SummaryEngine(), 1000, 1, 8), 3600);
    }

    @Test
    void no_category_named_keeps_the_group_names_of_before() {
        assertTrue(ServiceCategory.consumerGroup("", "ping").matches("summary-ping-[0-9a-f-]{36}"));
        assertTrue(ServiceCategory.consumerGroup(null, "doorbell").matches("summary-doorbell-[0-9a-f-]{36}"));
        assertEquals("", ServiceCategory.of(configOf(Map.of("summary.kinds", "call"))), "the key is optional");
    }

    @Test
    void a_category_names_the_groups_and_every_start_gets_its_own_group_still() {
        String one = ServiceCategory.consumerGroup("ad", "ping");
        String two = ServiceCategory.consumerGroup("ad", "ping");
        assertTrue(one.matches("summary-service-ad-ping-[0-9a-f-]{36}"), one);
        assertNotEquals(one, two, "unique per start: a broadcast, as before");
        assertTrue(ServiceCategory.consumerGroup(" Ad ", "doorbell").startsWith("summary-service-ad-doorbell-"), "the word, trimmed and lower-cased");
        assertEquals("ad", ServiceCategory.of(configOf(Map.of(ServiceCategory.KEY, " AD "))));
    }

    @Test
    void the_ping_listener_and_the_doorbell_listener_join_their_categorys_groups() {
        assertTrue(new PingListener(aRegistry(), "cdr_summary_ping_ad_btcl", "127.0.0.1:1", "ad").consumerGroup().startsWith("summary-service-ad-ping-"));
        assertTrue(new PingListener(aRegistry(), "cdr_summary_ping_btcl", "127.0.0.1:1").consumerGroup().startsWith("summary-ping-"), "no category: as before");
        assertTrue(new DoorbellListener("config_event_loader_btcl", "127.0.0.1:1", () -> { }, "ad").consumerGroup().startsWith("summary-service-ad-doorbell-"));
        assertTrue(new DoorbellListener("config_event_loader_btcl", "127.0.0.1:1", () -> { }).consumerGroup().startsWith("summary-doorbell-"), "no category: as before");
    }

    @Test
    void the_tenant_watcher_hands_the_profiles_category_to_its_doorbell() {
        Map<String, String> tree = Map.of("summary.tenants.mode", "tree", "summary.tenants.root", "btcl",
                "summary.tenants.prime-context.base-url", "http://127.0.0.1:1", "summary.category", "ad");
        TenantWatcher adPair = new TenantWatcher(aRegistry(), configOf(tree), name -> null);
        assertTrue(adPair.doorbellConsumerGroup().startsWith("summary-service-ad-doorbell-"), adPair.doorbellConsumerGroup());

        Map<String, String> noCategory = Map.of("summary.tenants.mode", "tree", "summary.tenants.root", "btcl",
                "summary.tenants.prime-context.base-url", "http://127.0.0.1:1");
        assertTrue(new TenantWatcher(aRegistry(), configOf(noCategory), name -> null).doorbellConsumerGroup().startsWith("summary-doorbell-"));

        assertNull(new TenantWatcher(aRegistry(), configOf(Map.of("summary.tenants.mode", "single")), name -> null).doorbellConsumerGroup(), "single mode: no doorbell");
    }
}
