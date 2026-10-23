package com.telcobright.summary.ping.internal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** billing-core's ping ({@code SummaryChangeNotificationPublisher}): {@code {"tenant", "entity", "rows"}}, the tenant = its schema. */
class PingPayloadTest {

    private static PingPayload parse(String json) {
        return PingPayload.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void billing_cores_ping_names_the_tenant_schema_and_the_entity() {
        PingPayload ping = parse("{\"tenant\":\"res_44\",\"entity\":\"cdr\",\"rows\":1}");

        assertEquals("res_44", ping.tenant());
        assertEquals("cdr", ping.entity());
        assertEquals(new PingPayload("btcl", "cdr"), parse("{\"rows\":1000,\"entity\":\"cdr\",\"tenant\":\" btcl \"}"));
    }

    @Test
    void a_ping_that_names_no_tenant_says_so() {
        assertNull(parse("{\"tenant\":\"\",\"entity\":\"cdr\",\"rows\":1}").tenant(), "billing-core writes \"\" when it has none");
        assertNull(parse("{\"rows\":3}").tenant());
        assertNull(parse("{\"tenant\":44}").tenant(), "not a name");
        assertNull(parse("{\"tenant\":\"btcl\"}").entity());
    }

    @Test
    void what_is_not_a_json_object_is_no_ping_at_all() {
        assertNull(parse("not json"));
        assertNull(parse("[1]"));
        assertNull(parse("\"btcl\""));
        assertNull(PingPayload.parse(null));
        assertNull(PingPayload.parse(new byte[0]));
    }
}
