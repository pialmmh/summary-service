package com.telcobright.summary.testkit;

import com.telcobright.summary.outbox.internal.OutboxCodec;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.testkit.WifiFlowTestSupport.Flow;
import com.telcobright.summary.testkit.WifiSessionTestSupport.Session;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A LAB tool: prints ONE outbox {@code data} value — one record as billing-core writes it
 * ({@code base64(gzip([{Cdr, Chargeables}]))}, or for the {@code wifi_flow} entity {@code base64(gzip([row…]))}) — for a
 * lab script that inserts the row itself, as the role {@code billing_core}. base64 has no quote and no backslash, so
 * the value goes into an SQL literal as it is.
 *
 * <p>The first argument names the record:
 * <ul>
 *   <li>{@code ad} (the default — one ad view, service group 30);</li>
 *   <li>{@code wifi-minutes} / {@code wifi-money} / {@code wifi-zero} — one domestic WiFi session of service group 101 as
 *       BC-0007 wrote it (ARCH-0077-S): 30 {@code TF_min} from the bucket, 3.00 BDT, 0 seconds;</li>
 *   <li>{@code wifi-bc0008 <start> <site> <zone> <tier> <category> <packageCode> <seconds> <bytesUp> <bytesDn> <unit> <amount> <cause> <validFlag> [<gw>]}
 *       — one session as BC-0008 writes it, with the WiFi columns (SS-0006): {@code start} = {@code yyyy-MM-ddTHH:mm}
 *       (the tenant's wall clock), {@code unit} = {@code TF_min} (then {@code amount} = the minutes) | {@code BDT}
 *       ({@code amount} = the money); {@code seconds} 0 = a session of zero seconds (counted, not costed); {@code gw}
 *       (SS-0007) = the BRAS id in {@code InMgwId}, {@code -} = the record names none (the key left out), absent =
 *       {@code bras-2} as before;</li>
 *   <li>{@code wifi-flow <window_start> <rows>} — one {@code wifi_flow} outbox row of gateway {@code bras-2}, zone
 *       {@code dhaka-north}: {@code window_start} an ISO instant in UTC ({@code 2026-10-16T09:41:00Z}), {@code rows} =
 *       {@code site,remote,port,proto,packets_up,packets_dn,bytes_up,bytes_dn;…} (a MAC per row, {@code aa:bb:cc:dd:ee:0N}).</li>
 * </ul>
 */
public final class LabOutboxRow {

    private static final LocalDateTime A_SESSIONS_START = WifiSessionTestSupport.at(2026, 10, 10, 9, 0);

    private LabOutboxRow() {
    }

    public static void main(String[] args) {
        System.out.println(OutboxCodec.encode(recordOf(args.length == 0 ? "ad" : args[0], args)));
    }

    private static byte[] recordOf(String kind, String[] args) {
        return switch (kind) {
            case "ad" -> AdTestSupport.batchOf(AdTestSupport.leafView(AdTestSupport.at(2026, 10, 2, 21, 14)));
            case "wifi-minutes" -> WifiSessionTestSupport.batchOf(WifiSessionTestSupport.session(A_SESSIONS_START));
            case "wifi-money" -> WifiSessionTestSupport.batchOf(WifiSessionTestSupport.sessionPaidInMoney(A_SESSIONS_START));
            case "wifi-zero" -> WifiSessionTestSupport.batchOf(WifiSessionTestSupport.sessionOfZeroSeconds(A_SESSIONS_START));
            case "wifi-bc0008" -> WifiSessionTestSupport.batchOf(bc0008Session(args));
            case "wifi-flow" -> WifiFlowTestSupport.batchOf(flowRows(args));
            default -> throw new IllegalArgumentException("ad | wifi-minutes | wifi-money | wifi-zero | wifi-bc0008 … | wifi-flow … — not '" + kind + "'");
        };
    }

    /** {@code wifi-bc0008 start site zone tier category packageCode seconds bytesUp bytesDn unit amount cause validFlag [gw]}. */
    private static Session bc0008Session(String[] a) {
        if (a.length != 14 && a.length != 15) {
            throw new IllegalArgumentException("wifi-bc0008 <start> <site> <zone> <tier> <category> <packageCode> <seconds> <bytesUp> <bytesDn> <unit> <amount> <cause> <validFlag> [<gw> | -]");
        }
        Session s = WifiSessionTestSupport.measuredSession(LocalDateTime.parse(a[1]))
                .site(a[2]).zone(a[3]).grantTier(a[4], Integer.parseInt(a[5])).packageCode(a[6])
                .seconds(a[7]).bytes(Long.parseLong(a[8]), Long.parseLong(a[9]))
                .cause(a[12]).measured(Integer.parseInt(a[13]));
        if ("BDT".equals(a[10])) {
            s.paidInMoney(a[11]);
        } else {
            s.uom(a[10]).minutes(a[11]);
        }
        if ("0".equals(a[7])) {
            s.ofZeroSeconds();
        }
        if (a.length == 15) {
            s.gateway("-".equals(a[14]) ? null : a[14]);   // the BRAS the record names (SS-0007), or none
        }
        return s;
    }

    /** {@code wifi-flow window_start site,remote,port,proto,pu,pd,bu,bd;…}. */
    private static List<Flow> flowRows(String[] a) {
        if (a.length != 3) {
            throw new IllegalArgumentException("wifi-flow <window_start> <site,remote,port,proto,packets_up,packets_dn,bytes_up,bytes_dn;…>");
        }
        List<Flow> rows = new ArrayList<>();
        int n = 0;
        for (String spec : a[2].split(";")) {
            String[] f = spec.split(",");
            rows.add(WifiFlowTestSupport.flow(a[1]).mac(String.format("aa:bb:cc:dd:ee:%02d", ++n)).site(f[0])
                    .to(f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]))
                    .packets(Long.parseLong(f[4]), Long.parseLong(f[5])).bytes(Long.parseLong(f[6]), Long.parseLong(f[7])));
        }
        return rows;
    }
}
