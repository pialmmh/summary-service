package com.telcobright.summary.summarybeans.wifi.model;

import com.telcobright.summary.bean.spi.SqlLiterals;
import com.telcobright.summary.bean.spi.SummaryEntity;
import com.telcobright.summary.bean.spi.SummaryKey;

import java.time.LocalDateTime;

/**
 * One {@code sum_wifi_dst_hr} / {@code sum_wifi_dst_day} row — the volume per DESTINATION (SS-0006 item 3; the WiFi
 * architect's spec §3). The flow windows the gateway counted per device and remote endpoint, which billing-core
 * ingested into {@code wifi_flow_window} and handed on as the {@code wifi_flow} entity, summed per key in one window:
 * the gateway ({@code tup_gw} — where the voice beans' switch id stands; a flow row has no switch, the architect's
 * ruling 5), the site, the zone, the remote address, the protocol, the service port (a report rolls ports up when it
 * wants an address only), and the bucket.
 *
 * <p>The sums all {@code +=}; {@code multiply} scales every one: the bytes and the packets up and down, and
 * {@code flows} = the rows summed. NO money column (money stays on the CDR, group 101) and NO count of distinct
 * devices (not additive: an hour merges 60 windows and a device seen in k windows would count k times — a report
 * query on {@code wifi_flow_window}; the architect's ruling 8).
 */
public final class WifiDestinationSummary implements SummaryEntity<WifiDestinationSummary> {

    /** INSERT column list (CSV, in {@link #insertValues()} order, WITHOUT id). */
    public static final String INSERT_COLUMNS =
            "tup_gw,tup_site,tup_zone,tup_remote,tup_proto,tup_port,tup_starttime,bytes_up,bytes_dn,packets_up,packets_dn,flows";

    public static final String BUCKET_COLUMN = "tup_starttime";

    private Long id;

    // -- key dimensions --
    public String tup_gw = "";
    public String tup_site = "";
    public String tup_zone = "";
    public String tup_remote = "";
    public int tup_proto;
    public int tup_port;
    public LocalDateTime tup_starttime;

    // -- sums (all +=; multiply scales ALL) --
    public long bytes_up;
    public long bytes_dn;
    public long packets_up;
    public long packets_dn;
    /** The flow rows summed into this row. */
    public long flows;

    @Override
    public Long id() {
        return id;
    }

    @Override
    public void setId(Long id) {
        this.id = id;
    }

    /** The 7-token dimension + bucket tuple, as canonical tokens. */
    @Override
    public SummaryKey tupleKey() {
        return SummaryKey.of(
                tup_gw,
                tup_site,
                tup_zone,
                tup_remote,
                Integer.toString(tup_proto),
                Integer.toString(tup_port),
                SqlLiterals.datetimeKey(tup_starttime));
    }

    @Override
    public void merge(WifiDestinationSummary o) {
        bytes_up += o.bytes_up;
        bytes_dn += o.bytes_dn;
        packets_up += o.packets_up;
        packets_dn += o.packets_dn;
        flows += o.flows;
    }

    /** Scales EVERY sum, {@code flows} included (the SUBTRACT path negates a copy). */
    @Override
    public void multiply(int factor) {
        bytes_up *= factor;
        bytes_dn *= factor;
        packets_up *= factor;
        packets_dn *= factor;
        flows *= factor;
    }

    @Override
    public WifiDestinationSummary cloneWithFakeId() {
        WifiDestinationSummary c = new WifiDestinationSummary();
        c.tup_gw = tup_gw;
        c.tup_site = tup_site;
        c.tup_zone = tup_zone;
        c.tup_remote = tup_remote;
        c.tup_proto = tup_proto;
        c.tup_port = tup_port;
        c.tup_starttime = tup_starttime;
        c.bytes_up = bytes_up;
        c.bytes_dn = bytes_dn;
        c.packets_up = packets_up;
        c.packets_dn = packets_dn;
        c.flows = flows;
        return c;
    }

    @Override
    public String insertValues() {
        return "(" + SqlLiterals.str(tup_gw)
                + "," + SqlLiterals.str(tup_site)
                + "," + SqlLiterals.str(tup_zone)
                + "," + SqlLiterals.str(tup_remote)
                + "," + SqlLiterals.num(tup_proto)
                + "," + SqlLiterals.num(tup_port)
                + "," + SqlLiterals.datetime(tup_starttime)
                + "," + SqlLiterals.num(bytes_up)
                + "," + SqlLiterals.num(bytes_dn)
                + "," + SqlLiterals.num(packets_up)
                + "," + SqlLiterals.num(packets_dn)
                + "," + SqlLiterals.num(flows)
                + ")";
    }

    @Override
    public String updateAssignments() {
        return "bytes_up=" + SqlLiterals.num(bytes_up)
                + ",bytes_dn=" + SqlLiterals.num(bytes_dn)
                + ",packets_up=" + SqlLiterals.num(packets_up)
                + ",packets_dn=" + SqlLiterals.num(packets_dn)
                + ",flows=" + SqlLiterals.num(flows);
    }

    /** The date-partition key for this row: {@code tup_starttime}. */
    @Override
    public String bucketLiteral() {
        return SqlLiterals.datetime(tup_starttime);
    }
}
