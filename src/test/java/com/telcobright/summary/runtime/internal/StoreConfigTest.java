package com.telcobright.summary.runtime.internal;

import com.telcobright.summary.bean.spi.SqlDialect;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
