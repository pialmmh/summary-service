package com.telcobright.summary.config.internal;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.summarybeans.call.CallSummaries;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
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

    // ---- brief S10: a profile from OUTSIDE the jar ----

    private static final String BED_PROFILE = "summary:\n  store:\n    kind: postgresql\n    url: jdbc:postgresql://127.0.0.1:5432/routesphere\n"
            + "    username: summary_service\n    password-ref: env:TENANT_BTCL_SWITCH_SUMMARY_SERVICE_PASSWORD\n  enabledSummary:\n    - dailyAdSummary\n";

    private static Path write(Path directory, String relative, String content) throws IOException {
        Path file = directory.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static ProfileYamlLoader.ConfigDir named(Path directory) {
        return ProfileYamlLoader.configDir(directory.toString(), null);
    }

    @Test
    void a_deployments_profile_is_a_file_in_its_config_directory_and_wins_over_the_one_in_the_jar(@TempDir Path directory) throws IOException {
        // the bed's profile is put on the box by the deploy tool: the jar is not rebuilt for it, and a root no jar names has its profile
        Path bed = write(directory, "config/tenants/btcl/bed/profile-bed.yml", BED_PROFILE);

        ProfileYamlLoader.Loaded fromTheFile = ProfileYamlLoader.loadActive(named(directory), "btcl/bed", null);

        assertEquals("env:TENANT_BTCL_SWITCH_SUMMARY_SERVICE_PASSWORD", fromTheFile.properties().get("summary.store.password-ref"), "a profile no jar holds");
        assertEquals("dailyAdSummary", fromTheFile.properties().get("summary.enabledSummary"));
        assertEquals(null, fromTheFile.fault());
        assertEquals("tenant btcl, profile bed (named by -Dsummary.active-tenant): the file " + bed.toAbsolutePath().normalize(), fromTheFile.readFrom(),
                "the start's first line says WHICH file was read");

        // no file for btcl/lab there: the jar's is read, and it is said that it is the jar's
        ProfileYamlLoader.Loaded fromTheJar = ProfileYamlLoader.loadActive(named(directory), null, "btcl/lab");
        assertEquals(PROFILE, fromTheJar.properties());
        assertEquals("tenant btcl, profile lab (named by SUMMARY_ACTIVE_TENANT): THE JAR'S OWN config/tenants/btcl/lab/profile-lab.yml"
                + " (no such file under -Dsummary.config.dir = " + directory.toAbsolutePath().normalize() + ")", fromTheJar.readFrom());

        // a file for btcl/lab there: it wins over the jar's, whole — it is not merged with it
        write(directory, "config/tenants/btcl/lab/profile-lab.yml", "summary:\n  zone: Asia/Kathmandu\n");
        assertEquals(Map.of("summary.zone", "Asia/Kathmandu"), ProfileYamlLoader.loadActive(named(directory), "btcl/lab", null).properties());
    }

    @Test
    void the_directory_is_named_by_one_key_the_option_before_the_environment_and_is_the_working_directory_when_not_named() {
        assertEquals(new ProfileYamlLoader.ConfigDir(Path.of("/etc/summary-service"), "-Dsummary.config.dir"),
                ProfileYamlLoader.configDir(" /etc/summary-service ", "/opt/other"));
        assertEquals(new ProfileYamlLoader.ConfigDir(Path.of("/opt/other"), "SUMMARY_CONFIG_DIR"), ProfileYamlLoader.configDir(" ", "/opt/other"));
        assertEquals(new ProfileYamlLoader.ConfigDir(Path.of(""), null), ProfileYamlLoader.configDir(null, null),
                "not named: the working directory, where Quarkus also reads config/application.properties");
        assertEquals("summary.config.dir", ProfileYamlLoader.CONFIG_DIR_PROPERTY);
        assertEquals("SUMMARY_CONFIG_DIR", ProfileYamlLoader.CONFIG_DIR_ENV);
    }

    @Test
    void a_file_in_the_working_directory_is_read_when_no_key_names_a_directory(@TempDir Path workingDirectory) throws IOException {
        Path bed = write(workingDirectory, "config/tenants/btcl/bed/profile-bed.yml", BED_PROFILE);

        ProfileYamlLoader.Loaded read = ProfileYamlLoader.loadActive(new ProfileYamlLoader.ConfigDir(workingDirectory, null), "btcl/bed", null);

        assertEquals("dailyAdSummary", read.properties().get("summary.enabledSummary"));
        assertTrue(read.readFrom().endsWith("the file " + bed.toAbsolutePath().normalize()), read.readFrom());
    }

    @Test
    void a_named_directory_that_is_none_refuses_the_start_nothing_falls_back_to_the_jar(@TempDir Path directory) {
        Path missing = directory.resolve("not-there");

        ProfileYamlLoader.Loaded refused = ProfileYamlLoader.loadActive(ProfileYamlLoader.configDir(null, missing.toString()), "btcl/lab", null);

        assertTrue(refused.properties().isEmpty(), "the jar's btcl/lab is NOT read behind a wrong directory");
        assertEquals("SUMMARY_CONFIG_DIR names " + missing.toAbsolutePath().normalize() + ", which is not a directory. It must be the directory that holds "
                + "config/tenants/<tenant>/<profile>/profile-<profile>.yml", refused.fault());
        // the working directory is always one: not being named is no fault
        assertEquals(null, ProfileYamlLoader.loadActive(new ProfileYamlLoader.ConfigDir(directory, null), "btcl/lab", null).fault());
    }

    @Test
    void a_start_that_names_a_tenant_with_no_profile_anywhere_is_refused(@TempDir Path directory) {
        // a slip in the name must not come up green with nothing to serve
        ProfileYamlLoader.Loaded refused = ProfileYamlLoader.loadActive(named(directory), "btcl/bedd", null);

        assertTrue(refused.properties().isEmpty());
        assertEquals("the start serves btcl/bedd and no profile file is there: looked for "
                + directory.toAbsolutePath().normalize().resolve("config/tenants/btcl/bedd/profile-bedd.yml") + ", then for the jar's config/tenants/btcl/bedd/profile-bedd.yml",
                refused.fault());
        assertEquals("tenant btcl, profile bedd (named by -Dsummary.active-tenant): NO profile file", refused.readFrom());
    }

    @Test
    void a_profile_file_that_cannot_be_parsed_is_no_configuration_and_refuses_the_start_never_a_crash(@TempDir Path directory) throws IOException {
        Path broken = write(directory, "config/tenants/btcl/lab/profile-lab.yml", "summary:\n\tstore: [unclosed");

        ProfileYamlLoader.Loaded refused = ProfileYamlLoader.loadActive(named(directory), "btcl/lab", null);

        assertTrue(refused.properties().isEmpty(), "and the jar's btcl/lab is NOT read in its place");
        assertTrue(refused.fault().startsWith("the profile the file " + broken.toAbsolutePath().normalize() + " could not be read: "), refused.fault());
        assertTrue(ProfileYamlLoader.loadProfile(new ProfileYamlLoader.ActiveTenant("btcl", "lab"), directory).isEmpty());

        write(directory, "config/tenants/btcl/lab/profile-lab.yml", "- a list\n- not keys\n");
        assertTrue(ProfileYamlLoader.loadActive(named(directory), "btcl/lab", null).fault().endsWith("could not be read: its root is not a map of keys"));
    }

    @Test
    void a_selection_that_is_not_tenant_slash_profile_refuses_the_start_in_words(@TempDir Path directory) {
        ProfileYamlLoader.Loaded refused = ProfileYamlLoader.loadActive(named(directory), "../etc/passwd", null);

        assertTrue(refused.properties().isEmpty());
        assertEquals("the active tenant is '../etc/passwd' — it must be <tenant>/<profile>, e.g. btcl/lab", refused.fault());
    }

    @Test
    void the_directorys_own_registry_says_which_tenant_it_serves_and_the_start_may_still_name_another(@TempDir Path directory) throws IOException {
        // the house rule: a file says which tenant a deployment serves; -D and the environment are a start's override
        write(directory, "config/tenants/btcl/bed/profile-bed.yml", BED_PROFILE);
        Path registry = write(directory, "config/tenants.yml", "tenants:\n  - name: tcbl\n    enabled: false\n    profile: dev\n  - name: btcl\n    enabled: true\n    profile: bed\n");

        ProfileYamlLoader.Loaded byTheFile = ProfileYamlLoader.loadActive(named(directory), null, null);
        assertEquals("dailyAdSummary", byTheFile.properties().get("summary.enabledSummary"));
        assertTrue(byTheFile.readFrom().startsWith("tenant btcl, profile bed (the first enabled entry of " + registry.toAbsolutePath().normalize() + "): the file "),
                byTheFile.readFrom());

        assertEquals("mysql", ProfileYamlLoader.loadActive(named(directory), "tcbl/dev", null).properties().get("summary.store.kind"), "the start named another");

        // a deployment's registry that enables nothing serves nothing: the jar's registry (tcbl/dev) is not asked behind its back
        write(directory, "config/tenants.yml", "tenants:\n  - name: btcl\n    enabled: false\n    profile: bed\n");
        ProfileYamlLoader.Loaded nothing = ProfileYamlLoader.loadActive(named(directory), null, null);
        assertTrue(nothing.properties().isEmpty());
        assertEquals(null, nothing.fault(), "no tenant is not a fault: the service boots with nothing to serve (and refuses to start workers)");
        assertTrue(nothing.readFrom().startsWith("no tenant is named"), nothing.readFrom());
    }

    @Test
    void with_no_directory_named_and_no_tenant_named_the_jars_registry_and_the_jars_profile_are_read(@TempDir Path emptyWorkingDirectory) {
        ProfileYamlLoader.Loaded jars = ProfileYamlLoader.loadActive(new ProfileYamlLoader.ConfigDir(emptyWorkingDirectory, null), null, null);

        assertEquals("mysql", jars.properties().get("summary.store.kind"));
        assertEquals("tenant tcbl, profile dev (the first enabled entry of the jar's config/tenants.yml): THE JAR'S OWN config/tenants/tcbl/dev/profile-dev.yml"
                + " (no such file under the working directory " + emptyWorkingDirectory.toAbsolutePath().normalize() + ")", jars.readFrom(),
                "a start that fell back to a profile the jar carries is seen for what it is");
    }

    @Test
    void the_source_says_which_file_was_read_and_why_a_start_is_refused_and_a_profile_can_set_neither(@TempDir Path directory) throws IOException {
        write(directory, "config/tenants/btcl/bed/profile-bed.yml", BED_PROFILE + "  profile:\n    read-from: a lie\n    fault: a lie\n");

        TenantProfileConfigSource read = new TenantProfileConfigSource(ProfileYamlLoader.loadActive(named(directory), "btcl/bed", null));
        assertTrue(read.getValue(TenantProfileConfigSource.READ_FROM_KEY).startsWith("tenant btcl, profile bed (named by -Dsummary.active-tenant): the file "));
        assertEquals(null, read.getValue(TenantProfileConfigSource.FAULT_KEY), "no fault: the key is not set, whatever the file says");
        assertEquals("dailyAdSummary", read.getValue("summary.enabledSummary"));

        TenantProfileConfigSource refused = new TenantProfileConfigSource(ProfileYamlLoader.loadActive(named(directory), "btcl/bedd", null));
        assertTrue(refused.getValue(TenantProfileConfigSource.FAULT_KEY).startsWith("the start serves btcl/bedd and no profile file is there"));
        assertEquals(null, refused.getValue("summary.store.url"));
        assertTrue(refused.getPropertyNames().isEmpty(), "and still nothing is listed");
    }

    @Test
    void the_lab_profile_refuses_by_itself_a_start_that_would_leave_this_machine() {
        assertEquals("true", PROFILE.get("summary.endpoints.loopback-only"), "the guard is in the profile: it does not depend on who starts it");
        assertEquals(null, ProfileYamlLoader.loadProfile(new ProfileYamlLoader.ActiveTenant("tcbl", "dev")).get("summary.endpoints.loopback-only"),
                "a deployment's profile does not carry it");
    }

    @Test
    void a_profile_may_switch_the_workers_on_over_the_jars_default() {
        // application.properties in the jar says summary.autostart=false (ordinal 250); a deployment's profile says true
        TenantProfileConfigSource profile = new TenantProfileConfigSource(Map.of("summary.autostart", "true"));
        Config config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(Map.of("summary.autostart", "false"), "application.properties", 250), profile).build();

        assertEquals(true, config.getValue("summary.autostart", Boolean.class));
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
