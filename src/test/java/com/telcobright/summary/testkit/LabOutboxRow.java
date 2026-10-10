package com.telcobright.summary.testkit;

import com.telcobright.summary.outbox.internal.OutboxCodec;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;

import java.time.LocalDateTime;

/**
 * A LAB tool: prints ONE outbox {@code data} value — one record as billing-core writes it
 * ({@code base64(gzip([{Cdr, Chargeables}]))}) — for a lab script that inserts the row itself, as the role
 * {@code billing_core}. base64 has no quote and no backslash, so the value goes into an SQL literal as it is.
 *
 * <p>The argument names the record: {@code ad} (the default — one ad view, service group 30), or one domestic WiFi
 * session of service group 101 (ARCH-0077-S): {@code wifi-minutes} (30 {@code TF_min} from the bucket),
 * {@code wifi-money} (3.00 BDT), {@code wifi-zero} (0 seconds: counted, not costed).
 */
public final class LabOutboxRow {

    private static final LocalDateTime A_SESSIONS_START = WifiSessionTestSupport.at(2026, 10, 10, 9, 0);

    private LabOutboxRow() {
    }

    public static void main(String[] args) {
        System.out.println(OutboxCodec.encode(recordOf(args.length == 0 ? "ad" : args[0])));
    }

    private static byte[] recordOf(String kind) {
        return switch (kind) {
            case "ad" -> AdTestSupport.batchOf(AdTestSupport.leafView(AdTestSupport.at(2026, 10, 2, 21, 14)));
            case "wifi-minutes" -> WifiSessionTestSupport.batchOf(WifiSessionTestSupport.session(A_SESSIONS_START));
            case "wifi-money" -> WifiSessionTestSupport.batchOf(WifiSessionTestSupport.sessionPaidInMoney(A_SESSIONS_START));
            case "wifi-zero" -> WifiSessionTestSupport.batchOf(WifiSessionTestSupport.sessionOfZeroSeconds(A_SESSIONS_START));
            default -> throw new IllegalArgumentException("ad | wifi-minutes | wifi-money | wifi-zero — not '" + kind + "'");
        };
    }
}
