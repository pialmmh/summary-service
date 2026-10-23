package com.telcobright.summary.runtime.internal;

import com.telcobright.summary.bean.spi.SqlDialect;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Brief S5: the store is chosen per PROFILE, at run time — {@code summary.store.kind}, {@code .url}. One jar
 * serves either engine; a profile that says one engine and reaches another is refused in words.
 */
class StoreConfigTest {

    private static Config profile(String... keysAndValues) {
        Map<String, String> properties = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) properties.put("summary.store." + keysAndValues[i], keysAndValues[i + 1]);
        return new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(properties, "test-profile", 300)).build();
    }

    @Test
    void a_postgresql_profile_gives_the_postgresql_store() {
        StoreConfig store = StoreConfig.from(profile("kind", "postgresql", "url", "jdbc:postgresql://127.0.0.1:7643/routesphere",
                "username", "summary_service", "max-size", "8"));

        assertEquals(SqlDialect.POSTGRESQL, store.dialect());
        assertEquals("org.postgresql.Driver", store.driverClassName());
        assertEquals("summary_service", store.username());
        assertEquals("", store.password(), "the lab trusts: no password");
        assertEquals(8, store.maxSize());
        assertEquals(0, store.minSize(), "nothing is dialled until a worker needs it");
        assertEquals(30, store.acquisitionTimeoutSeconds());
    }

    @Test
    void a_mysql_profile_gives_the_mysql_store_with_its_inline_password() {
        StoreConfig store = StoreConfig.from(profile("kind", "mysql", "url", "jdbc:mysql://103.95.96.77:3306/telcobright?allowMultiQueries=true",
                "username", "billing", "password", "inline-form"));

        assertEquals(SqlDialect.MYSQL, store.dialect());
        assertEquals("com.mysql.cj.jdbc.Driver", store.driverClassName());
        assertEquals("inline-form", store.password(), "the inline form stays for the other deployments");
    }

    // ---- brief S9: the password by the NAME of its environment variable ----

    private static final String PG = "jdbc:postgresql://127.0.0.1:7643/routesphere";
    private static final String VARIABLE = "TENANT_BTCL_SWITCH_SUMMARY_SERVICE_PASSWORD";

    @Test
    void the_password_is_taken_from_the_environment_variable_the_profile_names() {
        StoreConfig store = StoreConfig.from(profile("url", PG, "username", "summary_service", "password-ref", "env:" + VARIABLE),
                Map.of(VARIABLE, "kept-in-the-units-environment")::get);

        assertEquals("kept-in-the-units-environment", store.password(), "the value is the environment's; the profile holds only the NAME");
        assertEquals(new StoreSecret.Source(StoreSecret.Kind.ENVIRONMENT, VARIABLE), StoreSecret.sourceOf(profile("url", PG, "password-ref", "env:" + VARIABLE)));
    }

    @Test
    void a_named_variable_that_is_not_set_refuses_the_start_and_names_the_variable() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> StoreConfig.from(profile("url", PG, "password-ref", "env:" + VARIABLE), Map.<String, String>of()::get));

        assertTrue(refused.getMessage().startsWith("REFUSING TO START"), refused.getMessage());
        assertTrue(refused.getMessage().contains("the environment variable " + VARIABLE + " is not set"), refused.getMessage());
        assertTrue(refused.getMessage().contains("summary.store.password-ref"), "it says which key named it: " + refused.getMessage());
    }

    @Test
    void a_named_variable_that_is_empty_is_missing_the_service_never_starts_with_an_empty_password_by_mistake() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> StoreConfig.from(profile("url", PG, "password-ref", "env:" + VARIABLE), Map.of(VARIABLE, "")::get));

        assertTrue(refused.getMessage().contains("the environment variable " + VARIABLE + " is empty"), refused.getMessage());
    }

    @Test
    void the_inline_form_stays_and_asks_the_environment_nothing() {
        StoreConfig store = StoreConfig.from(profile("url", "jdbc:mysql://127.0.0.1:3306/telcobright", "password", "inline-form"), name -> {
            throw new AssertionError("the environment was asked for " + name + " though the profile gives the password inline");
        });

        assertEquals("inline-form", store.password());
        assertEquals(StoreSecret.Kind.INLINE, StoreSecret.sourceOf(profile("url", PG, "password", "x")).kind());
        assertEquals(StoreSecret.Kind.NONE, StoreSecret.sourceOf(profile("url", PG)).kind(), "a lab that trusts: no password at all");
        assertEquals(StoreSecret.Kind.NONE, StoreSecret.sourceOf(profile("url", PG, "password", "")).kind(), "an empty inline value is none");
    }

    @Test
    void the_two_forms_together_are_refused() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> StoreConfig.from(profile("url", PG, "password", "inline-form", "password-ref", "env:" + VARIABLE), Map.of(VARIABLE, "v")::get));

        assertTrue(refused.getMessage().contains("BOTH set"), refused.getMessage());
        assertFalse(refused.getMessage().contains("inline-form"), "the inline value is not shown");
    }

    @Test
    void a_reference_that_is_not_env_colon_name_is_refused_and_never_shown() {
        // the most likely mistake: the password itself pasted into password-ref. It must not reach a log.
        for (String wrong : new String[] {"hunter2-the-real-password", "env:", "env:NOT A NAME", "env:9STARTS_WITH_A_DIGIT", "vault:kv/summary", "ENV:" + VARIABLE,
                "${" + VARIABLE + "}", "env:" + VARIABLE + " "}) {
            if (wrong.trim().equals("env:" + VARIABLE)) continue;      // surrounding blanks are not a mistake
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> StoreConfig.from(profile("url", PG, "password-ref", wrong), Map.of(VARIABLE, "v")::get), wrong);
            assertTrue(refused.getMessage().contains("must be env:<the NAME of an environment variable>"), refused.getMessage());
            assertFalse(refused.getMessage().contains(wrong.replace("env:", "")) && !wrong.replace("env:", "").isBlank(), "what was there is not shown: " + refused.getMessage());
        }
    }

    @Test
    void a_value_is_never_printed_not_in_the_line_that_says_where_it_came_from() {
        String said = StoreSecret.sourceOf(profile("url", PG, "password-ref", "env:" + VARIABLE)).said();

        assertEquals("the store's password: from the environment variable " + VARIABLE + " (summary.store.password-ref)", said);
        assertEquals("the store's password: inline in the profile (summary.store.password)", StoreSecret.sourceOf(profile("url", PG, "password", "s3cret")).said());
        assertEquals("the store's password: none is configured", StoreSecret.sourceOf(profile("url", PG)).said());
    }

    @Test
    void a_password_in_the_url_is_refused_and_the_url_is_not_shown() {
        for (String url : new String[] {PG + "?user=summary_service&password=s3cret-in-a-url", PG + "?PASSWORD=s3cret-in-a-url", "jdbc:mysql://h:3306/db?useSSL=false&pwd=s3cret-in-a-url",
                PG + "?sslpassword=s3cret-in-a-url",                                           // the key file's password is a secret too
                "jdbc:mysql://summary:s3cret-in-a-url@127.0.0.1:3306/telcobright",            // user:password@ before the host
                "jdbc:mysql://127.0.0.1:3306,summary:s3cret-in-a-url@127.0.0.2:3306/telcobright",
                "jdbc:mysql://(host=127.0.0.1,port=3306,user=summary,password=s3cret-in-a-url)/telcobright"}) {
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> StoreConfig.from(profile("url", url), Map.<String, String>of()::get), url);
            assertTrue(refused.getMessage().contains("a secret is never in a URL"), refused.getMessage());
            assertFalse(refused.getMessage().contains("s3cret-in-a-url"), refused.getMessage());
        }
        assertEquals(SqlDialect.POSTGRESQL, StoreConfig.from(profile("url", PG + "?currentSchema=btcl&sslmode=require")).dialect(), "other parameters are the URL's own");
        assertEquals(SqlDialect.MYSQL, StoreConfig.from(profile("url", "jdbc:mysql://summary@127.0.0.1:3306/telcobright")).dialect(), "a user alone is no secret");
    }

    @Test
    void the_start_says_where_the_password_comes_from_and_refuses_when_its_variable_is_missing() {
        StoreDataSource inPlace = TestPools.store(Map.of("summary.store.url", PG, "summary.store.password-ref", "env:" + VARIABLE), Map.of(VARIABLE, "the-value"));
        StoreDataSource missing = TestPools.store(Map.of("summary.store.url", PG, "summary.store.password-ref", "env:" + VARIABLE), Map.of());

        assertEquals("the store's password: from the environment variable " + VARIABLE + " (summary.store.password-ref)", inPlace.checkAtStart(true));
        assertTrue(assertThrows(IllegalStateException.class, () -> missing.checkAtStart(true)).getMessage().contains(VARIABLE + " is not set"));
        assertTrue(assertThrows(IllegalStateException.class, () -> missing.checkAtStart(false)).getMessage().contains(VARIABLE + " is not set"),
                "a named secret that is missing refuses a start whether the workers start or not");
    }

    @Test
    void a_fault_of_the_stores_configuration_refuses_the_start_it_is_not_left_to_be_tried_again() {
        // only a store that does not ANSWER is tried again; a profile that cannot be right is refused at once
        StoreDataSource wrongEngine = TestPools.store(Map.of("summary.store.kind", "postgresql", "summary.store.url", "jdbc:mysql://127.0.0.1:3306/telcobright"), Map.of());
        StoreDataSource unknownKind = TestPools.store(Map.of("summary.store.kind", "oracle", "summary.store.url", PG), Map.of());
        StoreDataSource passwordInUrl = TestPools.store(Map.of("summary.store.url", PG + "?password=s3cret-in-a-url"), Map.of());

        StoreDataSource neitherEngine = TestPools.store(Map.of("summary.store.url", "jdbc:oracle:thin:@127.0.0.1:1521/x"), Map.of());

        assertTrue(assertThrows(IllegalStateException.class, () -> wrongEngine.checkAtStart(true)).getMessage().contains("kind is postgresql but summary.store.url is a mysql URL"));
        assertTrue(assertThrows(IllegalStateException.class, () -> passwordInUrl.checkAtStart(false)).getMessage().contains("a secret is never in a URL"));
        // every fault is said the same way — ONE kind of refusal, which print-only reads and the lab script acts on
        for (StoreDataSource faulty : new StoreDataSource[] {wrongEngine, unknownKind, passwordInUrl, neitherEngine}) {
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> faulty.checkAtStart(true));
            assertTrue(refused.getMessage().startsWith("REFUSING TO START"), refused.getMessage());
        }
        assertTrue(assertThrows(IllegalStateException.class, () -> unknownKind.checkAtStart(true)).getMessage().contains("it must be mysql or postgresql"));
        assertTrue(assertThrows(IllegalStateException.class, () -> neitherEngine.checkAtStart(true)).getMessage().contains("neither a jdbc:mysql: nor a jdbc:postgresql: URL"));
    }

    @Test
    void workers_that_are_to_start_with_no_store_refuse_the_start_and_ask_whether_the_tenant_was_named() {
        StoreDataSource noStore = TestPools.store(Map.of(), Map.of());

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> noStore.checkAtStart(true));
        assertTrue(refused.getMessage().contains("names no store") && refused.getMessage().contains("SUMMARY_ACTIVE_TENANT=<tenant>/<profile>"), refused.getMessage());
        assertEquals("the store: none is configured (nothing is to be served: summary.autostart is off)", noStore.checkAtStart(false),
                "with the workers off, a profile with no store still boots");
    }

    @Test
    void the_kind_may_be_left_out_when_the_url_names_the_engine() {
        assertEquals(SqlDialect.POSTGRESQL, StoreConfig.from(profile("url", "jdbc:postgresql://h/db")).dialect());
        assertEquals(SqlDialect.MYSQL, StoreConfig.from(profile("url", "jdbc:mysql://h/db")).dialect());
    }

    @Test
    void a_kind_that_contradicts_the_url_refuses_the_start_in_words() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> StoreConfig.from(profile("kind", "postgresql", "url", "jdbc:mysql://103.95.96.77:3306/telcobright")));

        assertTrue(refused.getMessage().contains("REFUSING TO START"), refused.getMessage());
        assertTrue(refused.getMessage().contains("kind is postgresql") && refused.getMessage().contains("is a mysql URL"), refused.getMessage());
    }

    @Test
    void a_profile_without_a_store_says_which_key_is_missing() {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> StoreConfig.from(profile("kind", "postgresql")));

        assertTrue(refused.getMessage().contains("summary.store.url is not set"), refused.getMessage());
    }

    @Test
    void an_unknown_kind_or_an_unknown_url_is_refused() {
        assertThrows(IllegalArgumentException.class, () -> StoreConfig.from(profile("kind", "oracle", "url", "jdbc:postgresql://h/db")));
        assertThrows(IllegalStateException.class, () -> StoreConfig.from(profile("url", "jdbc:h2:mem:x")));
    }
}
