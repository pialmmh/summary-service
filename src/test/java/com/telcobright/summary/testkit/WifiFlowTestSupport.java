package com.telcobright.summary.testkit;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.engine.internal.SummaryCache;
import com.telcobright.summary.engine.spi.MergeMode;
import com.telcobright.summary.summarybeans.wifi.WifiSummaries;
import com.telcobright.summary.summarybeans.wifi.model.WifiDestinationSummary;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The WiFi DESTINATION tests' bed (SS-0006 item 3): the two destination beans as the profiles name them (on the Dhaka
 * clock, no CDI), flow rows written the way billing-core's {@code WifiFlowOutbox} carries them (BC-0008 §2: a JSON
 * array of {@code WifiFlowOutboxRow} — the {@code wifi_flow_window} columns by name, the instants ISO UTC, a null left
 * out), a {@link #rollup}, and {@link #knownHour}: the replayed hour the proofs count by hand.
 *
 * <p>The known hour — three windows of gateway {@code bras-2}, zone {@code dhaka-north}, UTC 09:41 / 09:42 / 09:43 on
 * 2026-10-16 = 15:41 / 15:42 / 15:43 in Dhaka (the hour bucket {@code 2026-10-16 15:00:00}):
 * <ul>
 *   <li>W1 09:41Z — r1: mac …01 → 142.250.74.78:443/6, 120/340 packets, 15 200/410 300 bytes, site mirpur-10;
 *       r2: mac …02 → the same endpoint, 10/20, 1 000/2 000, mirpur-10; r3: mac …01 → 1.1.1.1:53/17, 5/5, 300/700, mirpur-10;</li>
 *   <li>W2 09:42Z — r4: mac …01 → 142.250.74.78:443/6, 30/60, 3 000/90 000, mirpur-10; r5: mac …03 → 1.1.1.1:53/17, 2/2, 100/200, uttara-7;</li>
 *   <li>W3 09:43Z — r6: mac …02 → 142.250.74.78:443/6, 1/1, 100/100, mirpur-10.</li>
 * </ul>
 * So the hour holds THREE rows: mirpur-10 → 142.250.74.78:443/6 = flows 4, bytes 19 300 / 502 400, packets 161 / 421
 * (two devices, merged — the MAC is no key); mirpur-10 → 1.1.1.1:53/17 = 1, 300 / 700, 5 / 5; uttara-7 → 1.1.1.1:53/17
 * = 1, 100 / 200, 2 / 2.
 */
public final class WifiFlowTestSupport {

    public static final String DAILY_BEAN = "dailyWifiDstSummary";
    public static final String HOURLY_BEAN = "hourlyWifiDstSummary";
    public static final String DAY_TABLE = "sum_wifi_dst_day";
    public static final String HOUR_TABLE = "sum_wifi_dst_hr";
    public static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");

    /** The hour bucket of the known windows on the Dhaka clock. */
    public static final LocalDateTime THE_HOUR_IN_DHAKA = LocalDateTime.of(2026, 10, 16, 15, 0);

    private WifiFlowTestSupport() {
    }

    public static SummaryBean<WifiDestinationSummary> daily() {
        return WifiSummaries.destinationForWindow(DAILY_BEAN, "daily", null, DHAKA);
    }

    public static SummaryBean<WifiDestinationSummary> hourly() {
        return WifiSummaries.destinationForWindow(HOURLY_BEAN, "hourly", null, DHAKA);
    }

    /** One flow row as billing-core carries it. Every setter returns {@code this}. */
    public static final class Flow {
        private String windowStart;
        private Integer windowSec = 60;
        private String gw = "bras-2";
        private String zone = "dhaka-north";
        private String site = "mirpur-10";
        private Integer seg = 1;
        private String mac = "aa:bb:cc:dd:ee:01";
        private String remote = "142.250.74.78";
        private Integer port = 443;
        private Integer proto = 6;
        private long packetsUp = 120, packetsDn = 340, bytesUp = 15_200, bytesDn = 410_300;
        private String producedAt;
        private String extraKeys = "";

        private Flow(String windowStart) {
            this.windowStart = windowStart;
            this.producedAt = windowStart == null ? null : windowStart.replace(":00Z", ":31Z");
        }

        public Flow gw(String id) { this.gw = id; return this; }
        public Flow zone(String name) { this.zone = name; return this; }
        public Flow site(String name) { this.site = name; return this; }
        public Flow seg(Integer segment) { this.seg = segment; return this; }
        public Flow mac(String address) { this.mac = address; return this; }
        public Flow to(String remoteAddress, int servicePort, int protocol) { this.remote = remoteAddress; this.port = servicePort; this.proto = protocol; return this; }
        public Flow packets(long up, long down) { this.packetsUp = up; this.packetsDn = down; return this; }
        public Flow bytes(long up, long down) { this.bytesUp = up; this.bytesDn = down; return this; }
        public Flow windowSec(Integer seconds) { this.windowSec = seconds; return this; }
        public Flow producedAt(String instant) { this.producedAt = instant; return this; }
        /** Keys the table does not hold and the gateway's message carries ({@code sVlan, cVlan, accessType, …}): read past. */
        public Flow withExtraKeys(String jsonFields) { this.extraKeys = jsonFields; return this; }

        public String json() {
            StringBuilder j = new StringBuilder("{");
            if (windowStart != null) j.append("\"window_start\":").append(q(windowStart)).append(',');
            if (windowSec != null) j.append("\"window_sec\":").append(windowSec).append(',');
            j.append("\"gw\":").append(q(gw));
            if (zone != null) j.append(",\"zone\":").append(q(zone));
            if (site != null) j.append(",\"site\":").append(q(site));
            if (seg != null) j.append(",\"seg\":").append(seg);
            j.append(",\"mac\":").append(q(mac)).append(",\"remote\":").append(q(remote)).append(",\"port\":").append(port).append(",\"proto\":").append(proto);
            j.append(",\"packets_up\":").append(packetsUp).append(",\"packets_dn\":").append(packetsDn);
            j.append(",\"bytes_up\":").append(bytesUp).append(",\"bytes_dn\":").append(bytesDn);
            if (producedAt != null) j.append(",\"produced_at\":").append(q(producedAt));
            if (!extraKeys.isEmpty()) j.append(',').append(extraKeys);
            return j.append('}').toString();
        }

        private static String q(String text) {
            return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    /** A flow row of the window that starts at {@code windowStartUtc} ({@code 2026-10-16T09:41:00Z}); the defaults are r1's. */
    public static Flow flow(String windowStartUtc) {
        return new Flow(windowStartUtc);
    }

    // ---- the known hour ----

    public static final String W1 = "2026-10-16T09:41:00Z", W2 = "2026-10-16T09:42:00Z", W3 = "2026-10-16T09:43:00Z";

    public static List<Flow> window1() {
        return List.of(flow(W1), flow(W1).mac("aa:bb:cc:dd:ee:02").packets(10, 20).bytes(1_000, 2_000),
                flow(W1).to("1.1.1.1", 53, 17).packets(5, 5).bytes(300, 700));
    }

    public static List<Flow> window2() {
        return List.of(flow(W2).packets(30, 60).bytes(3_000, 90_000),
                flow(W2).mac("aa:bb:cc:dd:ee:03").site("uttara-7").to("1.1.1.1", 53, 17).packets(2, 2).bytes(100, 200));
    }

    public static List<Flow> window3() {
        return List.of(flow(W3).mac("aa:bb:cc:dd:ee:02").packets(1, 1).bytes(100, 100));
    }

    /** The six rows of the three windows. */
    public static List<Flow> knownHour() {
        List<Flow> all = new ArrayList<>(window1());
        all.addAll(window2());
        all.addAll(window3());
        return all;
    }

    // ---- the blob and the rollup ----

    /** The decompressed JSON of one outbox row: the array of rows (what {@code WifiFlowOutbox.Write} encodes per chunk). */
    public static byte[] batchOf(List<Flow> flows) {
        List<String> rows = new ArrayList<>(flows.size());
        for (Flow flow : flows) rows.add(flow.json());
        return ("[" + String.join(",", rows) + "]").getBytes(StandardCharsets.UTF_8);
    }

    /** Build the rows of every blob through {@code bean}, fold them into one cache, and return the window rows. */
    @SafeVarargs
    public static Collection<WifiDestinationSummary> rollup(SummaryBean<WifiDestinationSummary> bean, List<Flow>... blobs) {
        SummaryCache<WifiDestinationSummary> cache = new SummaryCache<>(bean.table(), WifiDestinationSummary.INSERT_COLUMNS, WifiDestinationSummary.BUCKET_COLUMN);
        for (List<Flow> blob : blobs) {
            for (WifiDestinationSummary built : bean.buildBatch(batchOf(blob), "btcl")) {
                cache.merge(built, MergeMode.ADD);
            }
        }
        return cache.rows();
    }

    /** The one row of {@code rows} at {@code site} to {@code remote:port/proto}. */
    public static WifiDestinationSummary row(Collection<WifiDestinationSummary> rows, String site, String remote, int port, int proto) {
        List<WifiDestinationSummary> found = rows.stream()
                .filter(r -> r.tup_site.equals(site) && r.tup_remote.equals(remote) && r.tup_port == port && r.tup_proto == proto).toList();
        if (found.size() != 1) {
            throw new AssertionError(site + " -> " + remote + ":" + port + "/" + proto + " has " + found.size() + " row(s), not one: " + rows.size() + " in all");
        }
        return found.get(0);
    }

    /** {@code flows | up | dn | packets up | packets dn} of a row — the shape the proofs compare. */
    public static String sums(WifiDestinationSummary r) {
        return r.flows + " | " + r.bytes_up + " | " + r.bytes_dn + " | " + r.packets_up + " | " + r.packets_dn;
    }
}
