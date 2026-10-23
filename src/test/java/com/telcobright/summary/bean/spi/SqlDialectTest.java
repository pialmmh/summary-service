package com.telcobright.summary.bean.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The engine is the profile's word ({@code summary.store.kind}) — two words, nothing else. */
class SqlDialectTest {

    @Test
    void the_two_kinds_are_read_whatever_their_case() {
        assertEquals(SqlDialect.MYSQL, SqlDialect.ofKind("mysql"));
        assertEquals(SqlDialect.POSTGRESQL, SqlDialect.ofKind("postgresql"));
        assertEquals(SqlDialect.POSTGRESQL, SqlDialect.ofKind(" PostgreSQL "));
        assertEquals("postgresql", SqlDialect.POSTGRESQL.kind());
        assertEquals("mysql", SqlDialect.MYSQL.kind());
    }

    @Test
    void any_other_word_is_refused_in_words() {
        for (String word : new String[] {"postgres", "pg", "oracle", "", null}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> SqlDialect.ofKind(word));
            assertTrue(refused.getMessage().contains("must be mysql or postgresql"), refused.getMessage());
        }
    }

    @Test
    void a_jdbc_url_names_its_engine() {
        assertEquals(SqlDialect.MYSQL, SqlDialect.ofUrl("jdbc:mysql://103.95.96.77:3306/telcobright?useSSL=false"));
        assertEquals(SqlDialect.POSTGRESQL, SqlDialect.ofUrl("jdbc:postgresql://127.0.0.1:7643/routesphere"));
        assertNull(SqlDialect.ofUrl("jdbc:h2:mem:x"));
        assertNull(SqlDialect.ofUrl(null));
    }
}
