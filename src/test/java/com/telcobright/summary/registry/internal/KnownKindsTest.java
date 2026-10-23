package com.telcobright.summary.registry.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryKind;
import com.telcobright.summary.summarybeans.ad.AdKind;
import com.telcobright.summary.summarybeans.ad.DailyAdSummary;
import com.telcobright.summary.summarybeans.call.CallKind;
import com.telcobright.summary.summarybeans.call.CallSummaries;
import com.telcobright.summary.summarybeans.call.DailySummary;
import com.telcobright.summary.summarybeans.call.chargeable.HourlyChargeableSummary;
import com.telcobright.summary.summarybeans.wifi.WifiBeanKind;
import com.telcobright.summary.summarybeans.wifi.WifiKind;
import com.telcobright.summary.summarybeans.wifi.WifiSummaries;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SS-0008 item 1: the kind contract ({@code bean/spi/SummaryKind}) and the three kinds — call, ad, wifi — each in ONE
 * package with its beans, the service groups each reads, and which kind every bean of the service belongs to.
 */
class KnownKindsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void the_known_kinds_are_the_call_the_ad_and_the_wifi_in_that_order() {
        assertEquals(List.of("call", "ad", "wifi"), KnownKinds.words());
        assertEquals(CallKind.class, KnownKinds.byWord("call").getClass());
        assertEquals(AdKind.class, KnownKinds.byWord("ad").getClass());
        assertEquals(WifiKind.class, KnownKinds.byWord("wifi").getClass());
        assertEquals(null, KnownKinds.byWord("sms"), "no sms kind yet");
    }

    @Test
    void a_service_group_is_the_kind_that_names_it_and_a_group_no_kind_names_is_the_calls() {
        assertEquals("call", KnownKinds.ofServiceGroup(10).word());
        assertEquals("call", KnownKinds.ofServiceGroup(11).word());
        assertEquals("ad", KnownKinds.ofServiceGroup(30).word());
        assertEquals("wifi", KnownKinds.ofServiceGroup(101).word());
        assertEquals("call", KnownKinds.ofServiceGroup(4).word(), "a group no kind names: the call's, as every group was summed before kinds");
        assertEquals("call", KnownKinds.ofServiceGroup(0).word(), "a record that names no group: the call's");
    }

    @Test
    void no_two_kinds_name_one_group_and_exactly_one_kind_takes_the_unnamed_ones() {
        Set<Integer> named = new HashSet<>();
        for (SummaryKind kind : KnownKinds.all()) {
            for (int group : kind.serviceGroups()) {
                assertTrue(named.add(group), "service group " + group + " is named by two kinds");
            }
        }
        assertEquals(List.of("call"), KnownKinds.all().stream().filter(SummaryKind::takesEveryUnnamedServiceGroup).map(SummaryKind::word).toList());
        assertEquals(Set.of(10, 11), KnownKinds.byWord("call").serviceGroups());
        assertEquals(Set.of(30), KnownKinds.byWord("ad").serviceGroups());
        assertEquals(Set.of(101), KnownKinds.byWord("wifi").serviceGroups());
    }

    @Test
    void the_wifi_kind_reads_the_cdr_and_the_wifi_flow_entities_the_call_and_the_ad_the_cdr() {
        assertEquals(Set.of("cdr"), KnownKinds.byWord("call").entityTypes());
        assertEquals(Set.of("cdr"), KnownKinds.byWord("ad").entityTypes());
        assertEquals(Set.of("cdr", "wifi_flow"), KnownKinds.byWord("wifi").entityTypes());
    }

    @Test
    void a_call_shaped_bean_is_the_kind_of_the_group_it_reads() {
        assertEquals("call", KnownKinds.kindOf(CallSummaries.forWindow("c10", "daily", "03", 10, null)).word());
        assertEquals("call", KnownKinds.kindOf(CallSummaries.forWindow("c11", "hourly", "02", 11, null)).word());
        assertEquals("ad", KnownKinds.kindOf(CallSummaries.forWindow("c30", "daily", "30", 30, null)).word(), "sum_voice_day_30: the ad's view as a call");
        assertEquals("wifi", KnownKinds.kindOf(CallSummaries.forWindow("c101", "daily", "101", 101, null)).word(), "sum_voice_day_101: the WiFi's");
        assertEquals("ad", KnownKinds.kindOf(new DailySummary(MAPPER, "30", 30, null)).word(), "a catalog call class configured with group 30 is the ad's too");
    }

    @Test
    void the_beans_of_the_kinds_own_shapes_are_their_kinds() {
        assertEquals("ad", KnownKinds.kindOf(new DailyAdSummary(MAPPER, null)).word());
        assertEquals("call", KnownKinds.kindOf(new HourlyChargeableSummary(MAPPER, null)).word(), "the chargeable roll-up is the call's");
        assertEquals("wifi", KnownKinds.kindOf(WifiSummaries.forWindow("s", WifiBeanKind.SESSION, "daily", null)).word());
        assertEquals("wifi", KnownKinds.kindOf(WifiSummaries.forWindow("d", WifiBeanKind.DESTINATION, "hourly", null)).word());
    }

    @Test
    void the_shapes_are_the_wifi_kinds_two_and_they_make_its_beans() {
        assertEquals(List.of("wifi-session", "wifi-dst"), List.copyOf(KnownKinds.byWord("wifi").shapes()), "in this order: the refusal names them so");
        assertTrue(KnownKinds.byWord("call").shapes().isEmpty());
        assertTrue(KnownKinds.byWord("ad").shapes().isEmpty());
        SummaryBean<?> session = KnownKinds.shaped("s", " Wifi-Session ", "daily", null);
        assertEquals("sum_wifi_day_101", session.table());
        assertSame(KnownKinds.byWord("wifi"), KnownKinds.kindOf(session));
        assertEquals("sum_wifi_dst_hr", KnownKinds.shaped("d", "wifi-dst", "hourly", null).table());
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> KnownKinds.shaped("w", "wifi-nope", "daily", null));
        assertEquals("summary.beans.w.kind is 'wifi-nope' — it must be wifi-session or wifi-dst (or absent: a call bean, as before)", refused.getMessage());
    }

    @Test
    void every_catalog_bean_class_is_declared_by_exactly_one_kind_owned_by_it_and_lives_in_its_package() throws Exception {
        Map<String, Class<?>> catalog = catalogBeanClasses();
        assertEquals(Set.of("dailyCallSummary", "hourlyCallSummary", "dailyChargeableSummary", "hourlyChargeableSummary",
                "dailyAdSummary", "hourlyAdSummary"), catalog.keySet(), "the catalog this round found");
        for (Map.Entry<String, Class<?>> bean : catalog.entrySet()) {
            List<String> declaring = KnownKinds.all().stream().filter(kind -> kind.catalogBeans().contains(bean.getKey())).map(SummaryKind::word).toList();
            assertEquals(1, declaring.size(), bean.getKey() + " is declared by " + declaring);
            SummaryKind kind = KnownKinds.byWord(declaring.get(0));
            assertTrue(bean.getValue().getPackageName().startsWith(kind.getClass().getPackageName()),
                    bean.getValue().getName() + " lives outside its kind's package " + kind.getClass().getPackageName());
        }
        Set<String> declared = new HashSet<>();
        KnownKinds.all().forEach(kind -> declared.addAll(kind.catalogBeans()));
        assertEquals(catalog.keySet(), declared, "a kind declares no catalog bean that does not exist");
    }

    @Test
    void every_kind_class_lives_in_its_own_package_under_summarybeans() {
        assertEquals("com.telcobright.summary.summarybeans.call", CallKind.class.getPackageName());
        assertEquals("com.telcobright.summary.summarybeans.ad", AdKind.class.getPackageName());
        assertEquals("com.telcobright.summary.summarybeans.wifi", WifiKind.class.getPackageName());
        assertFalse(HourlyChargeableSummary.class.getPackageName().endsWith(".summarybeans.chargeable"), "the chargeable roll-up moved under the call kind");
    }

    @Test
    void a_bean_no_kind_owns_is_refused_in_words() {
        SummaryBean<?> stranger = (SummaryBean<?>) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {SummaryBean.class},
                (proxy, method, args) -> method.getName().equals("name") ? "stranger" : null);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> KnownKinds.kindOf(stranger));
        assertTrue(refused.getMessage().startsWith("bean 'stranger' belongs to no kind"), refused.getMessage());
    }

    /** Every concrete {@code @Singleton} {@link SummaryBean} class compiled under {@code summarybeans/}, by its {@code NAME}. */
    private static Map<String, Class<?>> catalogBeanClasses() throws IOException, URISyntaxException, ReflectiveOperationException {
        Path root = Path.of(DailySummary.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path summarybeans = root.resolve("com/telcobright/summary/summarybeans");
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.walk(summarybeans)) {
            files.filter(f -> f.toString().endsWith(".class") && !f.getFileName().toString().contains("$") && !f.getFileName().toString().startsWith("package-info"))
                    .forEach(f -> names.add(root.relativize(f).toString().replace('/', '.').replaceAll("\\.class$", "")));
        }
        Map<String, Class<?>> catalog = new TreeMap<>();
        for (String name : names) {
            Class<?> c = Class.forName(name);
            if (SummaryBean.class.isAssignableFrom(c) && !Modifier.isAbstract(c.getModifiers()) && c.isAnnotationPresent(Singleton.class)) {
                catalog.put((String) c.getField("NAME").get(null), c);
            }
        }
        return catalog;
    }
}
