package com.telcobright.summary.runtime.internal;

import com.telcobright.summary.bean.spi.SqlDialect;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant schema's name goes into {@code USE} / {@code SET search_path} unquoted, so a name that is not a plain
 * identifier is refused BEFORE a connection is asked for — on both engines. (The schemas themselves are entered on
 * real databases in the integration tests.)
 */
class JdbcUnitOfWorkFactoryTest {

    private final AtomicInteger connectionsAsked = new AtomicInteger();
    private final DataSource neverAsked = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
                connectionsAsked.incrementAndGet();
                throw new AssertionError("no connection may be asked for a refused schema name");
            });

    @Test
    void a_name_that_is_not_a_plain_identifier_is_refused_before_any_connection() {
        for (SqlDialect engine : SqlDialect.values()) {
            JdbcUnitOfWorkFactory factory = new JdbcUnitOfWorkFactory(neverAsked, engine);
            for (String name : new String[] {"res_44; drop schema btcl cascade", "btcl,public", "res-44", "\"btcl\"", "", "44res", "a".repeat(64)}) {
                IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> factory.begin(name), name);
                assertTrue(refused.getMessage().contains("is not a tenant schema's name"), refused.getMessage());
            }
        }
        assertEquals(0, connectionsAsked.get());
    }
}
