package com.telcobright.summary.testkit;

import com.telcobright.summary.outbox.internal.OutboxCodec;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;

/**
 * A LAB tool: prints ONE outbox {@code data} value — one ad view as billing-core writes it
 * ({@code base64(gzip([{Cdr, Chargeables}]))}) — for a lab script that inserts the row itself, as the role
 * {@code billing_core}. base64 has no quote and no backslash, so the value goes into an SQL literal as it is.
 */
public final class LabOutboxRow {

    private LabOutboxRow() {
    }

    public static void main(String[] args) {
        System.out.println(OutboxCodec.encode(AdTestSupport.batchOf(AdTestSupport.leafView(AdTestSupport.at(2026, 10, 2, 21, 14)))));
    }
}
