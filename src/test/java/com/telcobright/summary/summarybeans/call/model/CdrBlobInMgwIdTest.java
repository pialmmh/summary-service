package com.telcobright.summary.summarybeans.call.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.outbox.internal.OutboxCodec;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.summarybeans.call.internal.CdrBlobMapper;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.WifiSessionTestSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SS-0007 item 1: the blob's {@link Cdr} reads {@code InMgwId} — the BRAS id billing-core writes there from the meta
 * {@code gw} ({@code WifiColumns.java:84}, a BC-0008 cell) — as billing-core writes it; and {@code ''}, never null, when
 * the blob carries none (billing-core sets the column only when the meta names a {@code gw}, and its outbox mapper leaves
 * a null out — {@code SummaryOutboxWriter}: NON_NULL).
 */
class CdrBlobInMgwIdTest {

    private static final ObjectMapper MAPPER = CdrBlobMapper.create();
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 17, 9, 5);

    private static Cdr decode(byte[] batch) {
        try {
            List<CdrBlobEntry> entries = MAPPER.readValue(batch, MAPPER.getTypeFactory().constructCollectionType(List.class, CdrBlobEntry.class));
            return entries.get(0).cdr();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void a_101_outbox_row_of_billing_cores_shape_carries_its_bras_in_InMgwId() {
        // the outbox row's `data` as billing-core writes it: base64(gzip([{Cdr, Chargeables}])), the cdr's fields by their C# names
        String data = OutboxCodec.encode(WifiSessionTestSupport.batchOf(WifiSessionTestSupport.measuredSession(START).gateway("bras-7")));

        Cdr c = decode(OutboxCodec.decode(data));

        assertEquals("bras-7", c.inMgwId(), "the meta's gw, as billing-core wrote it into InMgwId");
        assertEquals("mirpur-10", c.inTrunkAdditionalInfo(), "and the session's other columns as before");
    }

    @Test
    void the_bras_id_is_kept_as_billing_core_wrote_it() {
        Cdr c = decode(WifiSessionTestSupport.batchOf(WifiSessionTestSupport.measuredSession(START).gateway("Bras-2 ")));

        assertEquals("Bras-2 ", c.inMgwId(), "the record keeps the text whole; the key's cut and trim are the summary's");
    }

    @Test
    void a_blob_that_carries_no_InMgwId_reads_the_empty_bras_never_null() {
        Cdr voice = decode(CdrTestSupport.batchJson(List.of(CdrTestSupport.sg10Entry(START))));
        Cdr view = decode(AdTestSupport.batchOf(AdTestSupport.leafView(START)));
        Cdr bc0007 = decode(WifiSessionTestSupport.batchOf(WifiSessionTestSupport.session(START)));
        Cdr noGwInTheMeta = decode(WifiSessionTestSupport.batchOf(WifiSessionTestSupport.measuredSession(START).gateway(null)));

        for (Cdr c : List.of(voice, view, bc0007, noGwInTheMeta)) {
            assertEquals("", c.inMgwId());
        }
        assertEquals("mirpur-10", noGwInTheMeta.inTrunkAdditionalInfo(), "a BC-0008 row whose meta named no gw: every other column read");
    }

    @Test
    void an_explicit_null_reads_the_empty_bras_too() {
        String withANull = "[{\"Cdr\":{\"SwitchId\":0,\"StartTime\":[2026,10,17,9,5],\"InMgwId\":null},\"Chargeables\":[]}]";

        assertEquals("", decode(withANull.getBytes(StandardCharsets.UTF_8)).inMgwId());
    }

    @Test
    void InMgwId_is_read_case_insensitively_as_every_other_field_of_the_blob() {
        String lowerCase = "[{\"cdr\":{\"switchid\":0,\"starttime\":[2026,10,17,9,5],\"inmgwid\":\"bras-3\"},\"chargeables\":[]}]";

        assertEquals("bras-3", decode(lowerCase.getBytes(StandardCharsets.UTF_8)).inMgwId());
    }
}
