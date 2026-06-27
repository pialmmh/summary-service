package com.telcobright.summary.outbox.internal;

import com.telcobright.summary.testkit.CdrTestSupport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The PINNED outbox codec: base64(gzip(UTF-8 JSON)) round-trips back to the exact JSON. */
class OutboxCodecTest {

    @Test
    void round_trips_a_json_string() {
        String json = "[{\"Cdr\":{\"SwitchId\":1},\"Customer\":{\"servicegroup\":10}}]";
        assertEquals(json, new String(OutboxCodec.decode(OutboxCodec.encode(json)), StandardCharsets.UTF_8));
    }

    @Test
    void round_trips_a_real_batch_blob() {
        byte[] original = CdrTestSupport.batchJson(List.of(
                CdrTestSupport.sg10Entry(CdrTestSupport.at(2026, 6, 19, 14, 30))));
        assertArrayEquals(original, OutboxCodec.decode(OutboxCodec.encode(original)));
    }
}
