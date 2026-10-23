package com.telcobright.summary.testkit;

import com.telcobright.summary.outbox.internal.OutboxCodec;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.summarybeans.call.model.CdrBlobEntry;
import com.telcobright.summary.summarybeans.call.model.Chargeable;
import com.telcobright.summary.testkit.WifiFlowTestSupport.Flow;
import com.telcobright.summary.testkit.WifiSessionTestSupport.Session;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A LAB tool (SS-0008 item 3): prints the PIN CORPUS — one line per outbox row, {@code <entity_type> <op> <data>}, the data
 * as billing-core writes it ({@code base64(gzip(json))}) — for {@code tools/lab/kinds-pin-e2e.sh}, which inserts the rows,
 * runs a packaged app over them and digests every summary table. The corpus holds every record shape the existing scenarios
 * use, so one digest pins the sums of every bean of the service: voice calls of groups 10 and 11 (v2 and the v1 shape, two
 * destinations, a rate, across midnight), ad views of group 30 (leaf, root, refused, never shown, a content, units, the S18
 * route), WiFi sessions of group 101 as BC-0008 writes them (two BRAS, none, money, zero seconds), a record of a group no
 * kind names (its chargeable is summed as every group's), ONE row mixing every kind, a correction ({@code subtract}), a
 * second visit of the same windows, and two {@code wifi_flow} rows. Deterministic: the same lines on every run.
 */
public final class LabPinCorpus {

    private LabPinCorpus() {
    }

    /** One outbox row of the corpus. */
    public record Row(String entityType, String op, byte[] json) {
    }

    public static void main(String[] args) {
        for (Row row : corpus()) {
            System.out.println(row.entityType() + " " + row.op() + " " + OutboxCodec.encode(row.json()));
        }
    }

    /** The corpus, in the order the rows are written. */
    public static List<Row> corpus() {
        List<Row> rows = new ArrayList<>();
        rows.add(cdr("add", voiceOfGroup10()));
        rows.add(cdr("add", voiceOfGroup11()));
        rows.add(cdr("add", adViews()));
        rows.add(cdr("add", wifiSessions()));
        rows.add(cdr("add", everyKindInOneRow()));
        rows.add(cdr("subtract", List.of(voice(CdrTestSupport.sg10Entry(at(20, 9, 5), 42)))));
        rows.add(new Row("wifi_flow", "add", WifiFlowTestSupport.batchOf(flows("2026-10-20T03:41:00Z"))));
        rows.add(new Row("wifi_flow", "add", WifiFlowTestSupport.batchOf(flows("2026-10-20T03:42:00Z"))));
        rows.add(cdr("add", theSameWindowsAgain()));
        return rows;
    }

    private static List<String> voiceOfGroup10() {
        return List.of(voice(CdrTestSupport.sg10Entry(at(20, 9, 5), 42)), voice(CdrTestSupport.sg10Entry(at(20, 9, 40), 42)),
                voice(CdrTestSupport.sg10Entry(at(20, 10, 15), 43, bd("120"), bd("2.5"))), voice(CdrTestSupport.sg10Entry(at(20, 23, 59), 44)),
                voice(CdrTestSupport.sg10Entry(at(21, 0, 1), 42)), voice(CdrTestSupport.sg10EntryV1(at(20, 11, 0))),
                voice(CdrTestSupport.sg10EntryWithRate(at(20, 11, 30), bd("0.75"))));
    }

    private static List<String> voiceOfGroup11() {
        return List.of(voice(CdrTestSupport.sg11Entry(at(20, 9, 10))), voice(CdrTestSupport.sg11Entry(at(20, 12, 0))),
                voice(CdrTestSupport.sg11Entry(at(21, 8, 0))));
    }

    private static List<String> adViews() {
        return List.of(AdTestSupport.leafView(at(20, 21, 14)).json(), AdTestSupport.leafView(at(20, 21, 20)).content("c-7").json(),
                AdTestSupport.leafView(at(20, 21, 25)).neverShown().json(), AdTestSupport.rootView(at(20, 21, 14)).json(),
                AdTestSupport.refusedView(at(20, 21, 40)).json(), AdTestSupport.leafView(at(20, 22, 5)).uom("TF_s").charge("12").json(),
                AdTestSupport.leafView(at(20, 22, 10)).route("r-9/wifi/dhaka-north/mirpur-10/dhaka/bras-2/8801711000002/aa:bb:cc:dd:ee:01").json());
    }

    private static List<String> wifiSessions() {
        return List.of(session(at(20, 9, 2), "mirpur-10", "bras-1").json(), session(at(20, 9, 8), "mirpur-10", "bras-2").json(),
                session(at(20, 9, 14), "uttara-7", null).json(), session(at(20, 9, 20), "mirpur-10", "bras-1").paidInMoney("3.00").json(),
                session(at(20, 9, 26), "mirpur-10", "bras-1").ofZeroSeconds().json());
    }

    /** ONE outbox row holding a record of every kind — and of a group no kind names — as a billing batch mixes them. */
    private static List<String> everyKindInOneRow() {
        return List.of(voice(CdrTestSupport.sg10Entry(at(20, 9, 50), 45)), voice(CdrTestSupport.sg11Entry(at(20, 13, 0))),
                AdTestSupport.leafView(at(20, 21, 30)).json(), session(at(20, 9, 32), "uttara-7", "bras-2").json(),
                voice(ofAGroupNoKindNames(at(20, 14, 0))));
    }

    private static List<String> theSameWindowsAgain() {
        return List.of(AdTestSupport.leafView(at(20, 21, 15)).json(), voice(CdrTestSupport.sg10Entry(at(20, 9, 41), 42)),
                session(at(20, 9, 44), "mirpur-10", "bras-1").json(), voice(CdrTestSupport.sg11Entry(at(20, 9, 11))));
    }

    /** A voice-shaped record whose two legs are of service group 4: no kind names 4, so it is the call kind's (every group was summed before kinds). */
    private static CdrBlobEntry ofAGroupNoKindNames(LocalDateTime start) {
        CdrBlobEntry voice = CdrTestSupport.sg10Entry(start, 46);
        return new CdrBlobEntry(voice.cdr(), List.of(
                new Chargeable(4, 4, 1, 7004L, "BDT", "1714", start, bd("1.0"), bd("1.5"), bd("1"), bd("0.5"),
                        null, null, null, null, null, null, null, null, null, null, null),
                new Chargeable(4, 4, 2, 7005L, "BDT", "1714S", start, bd("0.8"), bd("0.8"), bd("1"), null,
                        null, null, null, null, null, null, null, null, null, null, null)));
    }

    private static Session session(LocalDateTime start, String site, String bras) {
        return WifiSessionTestSupport.measuredSession(start).site(site).gateway(bras);
    }

    private static List<Flow> flows(String windowStart) {
        return List.of(WifiFlowTestSupport.flow(windowStart).mac("aa:bb:cc:dd:ee:01").site("mirpur-10").to("142.250.74.78", 443, 6).packets(120, 340).bytes(15200, 410300),
                WifiFlowTestSupport.flow(windowStart).mac("aa:bb:cc:dd:ee:02").site("uttara-7").to("1.1.1.1", 53, 17).packets(5, 5).bytes(300, 700));
    }

    private static Row cdr(String op, List<String> entries) {
        return new Row("cdr", op, ("[" + String.join(",", entries) + "]").getBytes(StandardCharsets.UTF_8));
    }

    private static String voice(CdrBlobEntry entry) {
        return CdrTestSupport.entryJson(entry);
    }

    private static LocalDateTime at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 10, day, hour, minute, 0);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
