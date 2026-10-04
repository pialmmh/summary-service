package com.telcobright.summary.it;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The BUILT jar carries no tenant's addresses outside its profile files. Quarkus records what a config source
 * lists at build time as run-time defaults in generated classes ({@code quarkus/generated-bytecode.jar}); when the
 * profile source listed its keys, the build tenant's config-manager and Kafka broker were baked there and became
 * the defaults of every start that served another tenant. This reads the packaged application (it runs after
 * {@code package}) and looks for every address any profile names. Skipped when the application was not packaged.
 */
class BuildBakesNoTenantIT {

    private static final Path GENERATED = Path.of("target", "quarkus-app", "quarkus", "generated-bytecode.jar");
    private static final Path APPLICATION = Path.of("target", "quarkus-app", "app");
    private static final Path PROFILES = Path.of("src", "main", "resources", "config", "tenants");
    private static final Pattern ADDRESS = Pattern.compile("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})(:\\d+)?");

    @Test
    void no_address_of_any_tenants_profile_is_in_the_generated_classes() throws IOException {
        assumeTrue(Files.exists(GENERATED), "the application is not packaged (target/quarkus-app) — nothing to read");
        Set<String> addresses = addressesOfEveryProfile();
        assertFalse(addresses.isEmpty(), "the profiles name addresses (else this test reads nothing)");

        List<String> baked = new ArrayList<>();
        try (ZipFile jar = new ZipFile(GENERATED.toFile())) {
            for (Enumeration<? extends ZipEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                try (InputStream in = jar.getInputStream(entry)) {
                    String text = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                    for (String address : addresses) {
                        if (text.contains(address)) baked.add(address + " in " + entry.getName());
                    }
                }
            }
        }

        assertTrue(baked.isEmpty(), "a tenant's address is baked into the jar as every start's default: " + baked);
    }

    @Test
    void the_packaged_jars_own_registry_enables_no_tenant() throws IOException {
        // S15: what SHIPS. A jar started with no configuration of its own is nobody's deployment
        assumeTrue(Files.isDirectory(APPLICATION), "the application is not packaged (target/quarkus-app) — nothing to read");
        int registries = 0;
        try (Stream<Path> jars = Files.list(APPLICATION)) {
            for (Path applicationJar : jars.filter(p -> p.toString().endsWith(".jar")).toList()) {
                try (ZipFile jar = new ZipFile(applicationJar.toFile())) {
                    ZipEntry registry = jar.getEntry("config/tenants.yml");
                    if (registry == null) continue;
                    registries++;
                    String text = new String(jar.getInputStream(registry).readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?m)#.*$", "");
                    assertFalse(Pattern.compile("enabled:\\s*true").matcher(text).find(), applicationJar + " enables a tenant: " + text);
                    assertTrue(text.contains("enabled: false"), "it lists the profiles the jar carries, not enabled");
                }
            }
        }
        assertTrue(registries == 1, "the packaged application holds ONE registry file: " + registries);
    }

    /** Every IP address a profile file names, without the loopback ones (a lab's own). */
    private static Set<String> addressesOfEveryProfile() throws IOException {
        Set<String> addresses = new TreeSet<>();
        try (Stream<Path> files = Files.walk(PROFILES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                Matcher found = ADDRESS.matcher(Files.readString(file));
                while (found.find()) {
                    if (!found.group(1).startsWith("127.")) addresses.add(found.group(1));
                }
            }
        }
        return addresses;
    }
}
