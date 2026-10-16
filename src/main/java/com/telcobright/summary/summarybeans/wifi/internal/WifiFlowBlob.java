package com.telcobright.summary.summarybeans.wifi.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.summarybeans.wifi.model.WifiFlowRow;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The {@code wifi_flow} outbox blob's own small reader (SS-0006 item 3): the decoded {@code data} is ONE JSON ARRAY of
 * {@link WifiFlowRow} — what billing-core's {@code WifiFlowOutbox.Decode} reads back ({@code mediation/cdr/WifiFlowOutbox
 * .java:38-40}): base64 → gunzip (the engine's {@code OutboxCodec}) → this. The mapper is the call category's
 * ({@code CdrBlobMapper}: case-insensitive, an unknown key ignored). A blob that is not such an array is a POISON row
 * (deterministic on the data): the drain quarantines it, as it does a malformed {@code cdr} blob.
 */
final class WifiFlowBlob {

    private WifiFlowBlob() {
    }

    static List<WifiFlowRow> decode(ObjectMapper mapper, byte[] json, String beanName) {
        try {
            return mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, WifiFlowRow.class));
        } catch (IOException e) {
            throw new IllegalArgumentException("malformed wifi_flow outbox blob for " + beanName, e);
        }
    }

    /** The window's start as an instant ({@code 2026-10-16T09:41:00Z}; an offset form is taken too); null when it is none. */
    static Instant windowStartOf(WifiFlowRow row) {
        if (row == null || row.window_start() == null || row.window_start().isBlank()) {
            return null;
        }
        String text = row.window_start().trim();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException notAnInstant) {
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException notAnOffsetTimeEither) {
                return null;
            }
        }
    }
}
