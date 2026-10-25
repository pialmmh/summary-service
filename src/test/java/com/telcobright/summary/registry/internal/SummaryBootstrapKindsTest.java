package com.telcobright.summary.registry.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.context.api.ContextRegistry;
import com.telcobright.summary.context.internal.ConfigManagerClient;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.outbox.api.OutboxReader;
import com.telcobright.summary.outbox.internal.OutboxReaper;
import com.telcobright.summary.ping.internal.PingListener;
import com.telcobright.summary.registry.api.SummaryBeanRegistry;
import com.telcobright.summary.runtime.internal.StoreDataSource;
import com.telcobright.summary.runtime.internal.TestPools;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.summarybeans.call.chargeable.DailyChargeableSummary;
import com.telcobright.summary.summarybeans.call.chargeable.model.ChargeableSummary;
import com.telcobright.summary.tenancy.api.TenantWatcher;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.FakeUnitOfWorkFactory;
import com.telcobright.summary.testkit.LogCapture;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.telcobright.summary.testkit.CdrTestSupport.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SS-0008 item 2 at the start: {@code summary.kinds} switches the kinds on (none named = the call alone); a bean the profile
 * enables whose kind the tenant did not enable refuses the start in words, before any bean is registered; the call kind's
 * chargeable roll-up is told the enabled kinds' groups; the gate counts with the first cdr bean. Over the unit test's
 * profile (the jar's tcbl/dev: no kinds named), with keys set over it; autostart off — nothing is dialled.
 */
class SummaryBootstrapKindsTest {

    private final ConfigManagerClient nothingDialled = new ConfigManagerClient() {
        @Override
        public Optional<String> fetchTenantRoot(String baseUrl, String tenant, com.telcobright.summary.treeroads.api.TreeRoadBearer bearer) {
            throw new AssertionError("nothing is dialled with autostart off");
        }
    };
    private final FakeUnitOfWorkFactory database = new FakeUnitOfWorkFactory();
    private final OutboxReader reader = new OutboxReader(database, new SummaryEngine(), 1000, 1, 8);
    private final SummaryBeanRegistry registry = new SummaryBeanRegistry(reader, 3600);
    private final KindGate gate = new KindGate();
    private final DailyChargeableSummary chargeable = new DailyChargeableSummary(new ObjectMapper(), null);
    private final List<String> keys = new ArrayList<>();

    @AfterEach
    void clearTheKeys() {
        keys.forEach(System::clearProperty);
    }

    private void set(String... keysAndValues) {
        for (int i = 0; i < keysAndValues.length; i += 2) {
            System.setProperty(keysAndValues[i], keysAndValues[i + 1]);
            keys.add(keysAndValues[i]);
        }
    }

    /** The bootstrap with ONE catalog bean — the chargeable day roll-up — and the gate under test. */
    private SummaryBootstrap bootstrap(StoreDataSource store) {
        @SuppressWarnings("unchecked")
        Instance.Handle<SummaryBean<?>> handle = (Instance.Handle<SummaryBean<?>>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Instance.Handle.class}, (proxy, method, args) -> {
                    if (method.getName().equals("get")) return chargeable;
                    throw new UnsupportedOperationException(method.getName());
                });
        @SuppressWarnings("unchecked")
        Instance<SummaryBean<?>> catalog = (Instance<SummaryBean<?>>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Instance.class}, (proxy, method, args) -> {
                    if (method.getName().equals("handles")) return List.of(handle);
                    throw new UnsupportedOperationException(method.getName());
                });
        return new SummaryBootstrap(registry, catalog, new ContextRegistry(nothingDialled), new OutboxReaper(reader, registry, 60),
                new PingListener(registry, "cdr_summary_ping", "127.0.0.1:1"), new TenantWatcher(registry), store, gate, false);
    }

    private SummaryBootstrap bootstrap() {
        return bootstrap(TestPools.store(Map.of(), Map.of()));
    }

    /** Three beans of three kinds: the voice SG11 day bean (call), the WiFi session day bean (wifi), the view as a call (ad). */
    private void threeBeansOfThreeKinds() {
        set("summary.enabledSummary", "dailyCallSummarySg11,dailyWifiSessionSummary,dailyCallSummarySg30,dailyChargeableSummary",
                "summary.beans.dailyWifiSessionSummary.window", "daily", "summary.beans.dailyWifiSessionSummary.kind", "wifi-session",
                "summary.beans.dailyCallSummarySg30.window", "daily", "summary.beans.dailyCallSummarySg30.service-group", "30",
                "summary.beans.dailyCallSummarySg30.table-suffix", "30");
    }

    @Test
    void a_bean_of_a_kind_the_tenant_did_not_enable_refuses_the_start_in_words_before_any_bean_is_registered() {
        threeBeansOfThreeKinds();                                   // the profile names no kind: the call alone

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> bootstrap().onStart(null));

        assertEquals("REFUSING TO START: summary.enabledSummary names beans of kinds this tenant did not enable — dailyWifiSessionSummary (kind wifi), "
                + "dailyCallSummarySg30 (kind ad); the tenant's kinds: call (summary.kinds names none: the call alone, as before kinds). Name their kinds "
                + "in summary.kinds (the kinds: call, ad, wifi), or take the beans out of summary.enabledSummary", refused.getMessage());
        assertTrue(registry.beanNames().isEmpty(), "not a bean was registered — not even the call's");
    }

    @Test
    void with_their_kinds_named_the_same_beans_register_and_the_kinds_are_said() {
        threeBeansOfThreeKinds();
        set("summary.kinds", "call,ad,wifi");

        try (LogCapture said = LogCapture.of(SummaryBootstrap.class)) {
            bootstrap().onStart(null);

            assertEquals(Set.of("dailyCallSummarySg11", "dailyWifiSessionSummary", "dailyCallSummarySg30", "dailyChargeableSummary"), registry.beanNames());
            assertTrue(said.lines().contains("KINDS call, ad, wifi (summary.kinds) — call = service groups 10, 11 and every group no kind names; "
                    + "ad = service group 30; wifi = service group 101"), said.lines().toString());
        }
        assertFalse(gate.counts("btcl", registry.beans().get(0)), "every kind enabled: nothing to count");
    }

    @Test
    void none_named_the_voice_profile_starts_as_before_and_says_what_becomes_of_the_other_kinds_rows() {
        try (LogCapture said = LogCapture.of(SummaryBootstrap.class)) {
            bootstrap().onStart(null);

            assertTrue(registry.beanNames().containsAll(List.of("dailyCallSummarySg11", "hourlyCallSummarySg11", "dailyChargeableSummary")), registry.beanNames().toString());
            assertTrue(said.lines().contains("KINDS call (summary.kinds names none: the call alone, as before kinds) — call = service groups 10, 11 and every "
                    + "group no kind names. NOT enabled: ad = service group 30; wifi = service group 101 — their cdr rows are summed by no bean here; "
                    + "they are counted and said (KIND_NOT_ENABLED, once per tenant, kind and day)"), said.lines().toString());
        }
        SummaryBean<?> first = registry.beans().stream().filter(b -> b.name().equals("dailyCallSummarySg11")).findFirst().orElseThrow();
        assertTrue(gate.counts("btcl", first), "the gate counts with the profile's first cdr bean that was made (tcbl/dev: dailyCallSummarySg11)");
        assertFalse(gate.counts("btcl", chargeable), "and with no other");
    }

    @Test
    void the_chargeable_roll_up_is_told_the_enabled_kinds_groups() {
        byte[] aViewAndACall = AdTestSupport.batchJson(List.of(AdTestSupport.leafView(at(2026, 10, 10, 21, 14)).json(),
                CdrTestSupport.entryJson(CdrTestSupport.sg10Entry(at(2026, 10, 10, 9, 0)))));

        bootstrap().onStart(null);                                   // the call alone

        List<ChargeableSummary> legs = chargeable.buildBatch(aViewAndACall, "btcl");
        assertEquals(List.of(10, 10), legs.stream().map(leg -> leg.tup_servicegroup).toList(), "the call's two legs; the view's is not summed");
    }

    @Test
    void with_the_ad_enabled_the_chargeable_roll_up_sums_the_views_legs_too() {
        set("summary.kinds", "call, ad");
        byte[] aViewAndACall = AdTestSupport.batchJson(List.of(AdTestSupport.leafView(at(2026, 10, 10, 21, 14)).json(),
                CdrTestSupport.entryJson(CdrTestSupport.sg10Entry(at(2026, 10, 10, 9, 0)))));

        bootstrap().onStart(null);

        assertEquals(List.of(30, 10, 10), chargeable.buildBatch(aViewAndACall, "btcl").stream().map(leg -> leg.tup_servicegroup).toList());
    }

    @Test
    void a_word_that_is_no_kind_refuses_the_start_in_words() {
        set("summary.kinds", "call,sms");

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> bootstrap().onStart(null));

        assertEquals("REFUSING TO START: summary.kinds names 'sms', which is no kind — the kinds are call, ad, wifi", refused.getMessage());
        assertTrue(registry.beanNames().isEmpty());
    }

    @Test
    void print_only_says_the_kinds_and_exits_with_4_when_a_bean_of_a_kind_not_enabled_is_listed() {
        threeBeansOfThreeKinds();
        set("summary.endpoints.print-only", "true", "summary.store.url", "jdbc:mysql://127.0.0.1:1/telcobright",
                "summary.outbox.ping-bootstrap-servers", "127.0.0.1:1", "summary.contexts.mediationContext.base-url", "http://127.0.0.1:1", "quarkus.http.host", "127.0.0.1");
        List<Integer> exits = new ArrayList<>();
        PrintStream standardOutput = System.out;
        ByteArrayOutputStream heard = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(heard, true, StandardCharsets.UTF_8));
            SummaryBootstrap printing = bootstrap(TestPools.store(Map.of("summary.store.url", "jdbc:mysql://127.0.0.1:1/telcobright"), Map.of()));
            printing.exit = exits::add;
            printing.onStart(null);
        } finally {
            System.setOut(standardOutput);
        }
        List<String> lines = heard.toString(StandardCharsets.UTF_8).lines().toList();

        assertEquals(List.of(4), exits, lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("REFUSING TO START: summary.enabledSummary names beans of kinds this tenant did not enable — "
                + "dailyWifiSessionSummary (kind wifi), dailyCallSummarySg30 (kind ad)")), lines.toString());
        assertTrue(registry.beanNames().isEmpty(), "nothing was started");

        set("summary.kinds", "wifi, call,ad");
        heard.reset();
        exits.clear();
        try {
            System.setOut(new PrintStream(heard, true, StandardCharsets.UTF_8));
            SummaryBootstrap printing = bootstrap(TestPools.store(Map.of("summary.store.url", "jdbc:mysql://127.0.0.1:1/telcobright"), Map.of()));
            printing.exit = exits::add;
            printing.onStart(null);
        } finally {
            System.setOut(standardOutput);
        }
        lines = heard.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(List.of(0), exits, lines.toString());
        assertTrue(lines.contains("KINDS call, ad, wifi (summary.kinds) — call = service groups 10, 11 and every group no kind names; ad = service group 30; "
                + "wifi = service group 101"), lines.toString());
    }
}
