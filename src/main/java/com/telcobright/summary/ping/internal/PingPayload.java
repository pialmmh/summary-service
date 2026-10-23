package com.telcobright.summary.ping.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * What a ping says — billing-core's {@code cdr_summary_ping}: {@code {"tenant": "<schema>", "entity": "cdr",
 * "rows": n}}, sent after a tenant batch committed. Only {@code tenant} (the schema that has a new outbox row) and
 * {@code entity} are used; {@code rows} is information. The ping carries NO data — the outbox row is the truth.
 *
 * @param tenant the tenant schema the batch was written in; null when the ping names none
 * @param entity the outbox entity ({@code cdr}); null when the ping names none
 */
public record PingPayload(String tenant, String entity) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The ping's tenant and entity; null when the bytes are not a JSON object (an older producer, a stray record). */
    public static PingPayload parse(byte[] value) {
        if (value == null || value.length == 0) {
            return null;
        }
        try {
            JsonNode ping = JSON.readTree(value);
            if (ping == null || !ping.isObject()) {
                return null;
            }
            return new PingPayload(textOf(ping.get("tenant")), textOf(ping.get("entity")));
        } catch (IOException notJson) {
            return null;
        }
    }

    private static String textOf(JsonNode node) {
        return node == null || !node.isTextual() || node.textValue().isBlank() ? null : node.textValue().trim();
    }
}
