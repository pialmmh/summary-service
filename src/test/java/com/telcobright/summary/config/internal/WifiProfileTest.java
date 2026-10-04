package com.telcobright.summary.config.internal;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.summarybeans.call.CallSummaries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void a_start_names_the_tenant_it_serves_and_the_same_jar_serves_either_engine() {
        // no profile key is fixed at build time: the jar that serves tcbl on MySQL serves btcl on PostgreSQL when told
        String before = System.getProperty(ProfileYamlLoader.ACTIVE_TENANT_PROPERTY);
        try {
            System.clearProperty(ProfileYamlLoader.ACTIVE_TENANT_PROPERTY);
            TenantProfileConfigSource byRegistry = new TenantProfileConfigSource();
            assertEquals("mysql", byRegistry.getValue("summary.store.kind"), "not told: the registry's first enabled entry, tcbl/dev");

            System.setProperty(ProfileYamlLoader.ACTIVE_TENANT_PROPERTY, "btcl/lab");
            TenantProfileConfigSource told = new TenantProfileConfigSource();
            assertEquals("postgresql", told.getValue("summary.store.kind"), "told btcl/lab: the wifi tenant's store");
            assertTrue(told.getValue("summary.store.url").startsWith("jdbc:postgresql://"));
            assertTrue(told.getValue("summary.enabledSummary").contains("dailyAdSummary"));
        } finally {
            if (before == null) System.clearProperty(ProfileYamlLoader.ACTIVE_TENANT_PROPERTY); else System.setProperty(ProfileYamlLoader.ACTIVE_TENANT_PROPERTY, before);
        }
    }

    @Test
    void the_profile_source_answers_by_name_and_lists_nothing_so_no_tenants_value_is_baked_into_the_jar() {
        // Quarkus records what a config source LISTS at build time as a run-time default of the jar. The build sees
        // tcbl/dev: listed, its config-manager and its Kafka broker became the defaults of a start that serves btcl
        // (found on the lab: the btcl/lab start dialled tcbl's config-manager). Listing nothing bakes nothing.
        TenantProfileConfigSource source = new TenantProfileConfigSource();

        assertTrue(source.getPropertyNames().isEmpty(), "no name is listed");
        assertTrue(source.getProperties().isEmpty(), "no value is listed");
        assertEquals("mysql", source.getValue("summary.store.kind"), "and a key is still answered by its name");
        assertFalse(source.profile().isEmpty());
    }

    @Test
    void a_profile_answers_the_services_keys_only_never_one_of_quarkus() {
        for (String tenant : new String[] {"tcbl/dev", "btcl/lab"}) {
            Map<String, String> profile = ProfileYamlLoader.loadProfile(ProfileYamlLoader.parseSelection(tenant));
            assertTrue(profile.keySet().stream().allMatch(key -> key.startsWith("summary.")), tenant + " holds summary.* keys only: " + profile.keySet());
        }
        // a profile that DOES carry one of Quarkus's keys: it is not answered, so it can never be baked as a default
        TenantProfileConfigSource source = new TenantProfileConfigSource(Map.of("quarkus.http.host", "10.10.199.9", "summary.zone", "Asia/Dhaka"));
        assertEquals(null, source.getValue("quarkus.http.host"), "asked by name while the jar is built: nothing of a tenant may answer");
        assertEquals("Asia/Dhaka", source.getValue("summary.zone"));
        assertEquals(null, source.getValue(null));
    }

    @Test
    void a_key_the_wifi_profile_does_not_set_is_not_set_never_another_tenants() {
        // the keys that leaked: a context the chargeable beans of tcbl name, and tcbl's addresses
        for (String key : new String[] {"summary.beans.dailyChargeableSummary.context", "summary.contexts.mediationContext.base-url",
                "summary.contexts.mediationContext.tenant", "summary.beans.dailyCallSummary.table-suffix"}) {
            assertEquals(null, PROFILE.get(key), key + " is tcbl's; the wifi tenant's profile does not set it");
        }
        assertTrue(PROFILE.values().stream().noneMatch(value -> value.contains("103.95.96.")), "no address of another tenant in the wifi profile");
    }

    @Test
    void a_deployments_profile_is_a_file_beside_its_working_directory_and_wins_over_the_one_in_the_jar(@TempDir Path workingDirectory) throws IOException {
        // the bed's profile is rendered by the deploy tool on the box: its addresses are never committed here, the jar is not rebuilt
        Path bed = workingDirectory.resolve("config/tenants/btcl/bed/profile-bed.yml");
        Files.createDirectories(bed.getParent());
        Files.writeString(bed, "summary:\n  store:\n    kind: postgresql\n    url: jdbc:postgresql://127.0.0.1:5432/routesphere\n"
                + "    username: summary_service\n    password-ref: env:TENANT_BTCL_SWITCH_SUMMARY_SERVICE_PASSWORD\n  enabledSummary:\n    - dailyAdSummary\n");

        Map<String, String> fromTheFile = ProfileYamlLoader.loadProfile(new ProfileYamlLoader.ActiveTenant("btcl", "bed"), workingDirectory);

        assertEquals("env:TENANT_BTCL_SWITCH_SUMMARY_SERVICE_PASSWORD", fromTheFile.get("summary.store.password-ref"), "a profile no jar holds");
        assertEquals("dailyAdSummary", fromTheFile.get("summary.enabledSummary"));

        // no file for btcl/lab there: the jar's is read
        assertEquals(PROFILE, ProfileYamlLoader.loadProfile(new ProfileYamlLoader.ActiveTenant("btcl", "lab"), workingDirectory));
        // a file for btcl/lab there: it wins over the jar's
        Path lab = workingDirectory.resolve("config/tenants/btcl/lab/profile-lab.yml");
        Files.createDirectories(lab.getParent());
        Files.writeString(lab, "summary:\n  zone: Asia/Kathmandu\n");
        assertEquals(Map.of("summary.zone", "Asia/Kathmandu"), ProfileYamlLoader.loadProfile(new ProfileYamlLoader.ActiveTenant("btcl", "lab"), workingDirectory));
    }

    @Test
    void a_profile_file_that_cannot_be_parsed_is_no_configuration_never_a_crash(@TempDir Path workingDirectory) throws IOException {
        Path broken = workingDirectory.resolve("config/tenants/btcl/bed/profile-bed.yml");
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, "summary:\n\tstore: [unclosed");

        assertTrue(ProfileYamlLoader.loadProfile(new ProfileYamlLoader.ActiveTenant("btcl", "bed"), workingDirectory).isEmpty());
    }

    @Test
    void a_selection_that_is_not_tenant_slash_profile_is_refused() {
        for (String wrong : new String[] {"btcl", "btcl/lab/x", "../etc/passwd", "btcl/ lab", "/lab"}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> ProfileYamlLoader.parseSelection(wrong), wrong);
            assertTrue(refused.getMessage().contains("must be <tenant>/<profile>"), refused.getMessage());
        }
        assertEquals(new ProfileYamlLoader.ActiveTenant("btcl", "lab"), ProfileYamlLoader.parseSelection(" btcl/lab "));
    }

    @Test
    void the_wifi_tenants_store_is_postgresql_and_the_voice_tenants_is_mysql() {
        Map<String, String> voice = ProfileYamlLoader.loadProfile(new ProfileYamlLoader.ActiveTenant("tcbl", "dev"));

        assertEquals("postgresql", PROFILE.get("summary.store.kind"));
        assertEquals("summary_service", PROFILE.get("summary.store.username"), "the role prime-context's provisioning lets into each tier schema");
        assertEquals("mysql", voice.get("summary.store.kind"));
        assertTrue(voice.get("summary.store.url").contains("allowMultiQueries=true"), "the ;-joined UPDATE segments need it on MySQL");
        assertTrue(voice.keySet().stream().noneMatch(key -> key.startsWith("quarkus.datasource")), "no key that Quarkus fixes at build time is left");
    }

    @Test
    void the_wifi_tenant_is_registered_and_not_the_active_one() {
        // tenants.yml selects the build's tenant: tcbl/dev stays the first enabled; btcl/lab is chosen at a start
        assertEquals(new ProfileYamlLoader.ActiveTenant("tcbl", "dev"), ProfileYamlLoader.activeTenant("config/tenants.yml").orElseThrow());
        assertFalse(PROFILE.isEmpty(), "config/tenants/btcl/lab/profile-lab.yml is on the class path");
        assertEquals("cdr", PROFILE.get("summary.outbox.entity-type"));
    }
}
