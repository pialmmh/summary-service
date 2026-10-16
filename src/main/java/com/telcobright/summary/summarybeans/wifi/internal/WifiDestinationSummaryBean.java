package com.telcobright.summary.summarybeans.wifi.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryMode;
import com.telcobright.summary.bean.spi.SummaryTableSpec;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.call.internal.CdrBlobMapper;
import com.telcobright.summary.summarybeans.wifi.internal.WifiDestinationSummaryGenerator.Timed;
import com.telcobright.summary.summarybeans.wifi.model.WifiDestinationSummary;
import com.telcobright.summary.summarybeans.wifi.model.WifiFlowRow;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The shared WiFi <b>destination</b> summary machinery over the {@link WifiDestinationSummary} entity (SS-0006 item 3;
 * the WiFi architect's spec §3). It reads the {@code wifi_flow} entity — the SECOND stream in the same outbox table:
 * billing-core's flow ingest writes, per message part it inserted into {@code wifi_flow_window}, ONE
 * {@code summary_affected} row ({@code entity_type = 'wifi_flow'}, {@code op = 'add'}, the rows inserted as a JSON
 * array, split at 5 000 rows so a blob stays far under the engine's cap; {@code mediation/cdr/WifiFlowOutbox.java}).
 * Every row of a blob is one delta; the engine merges them into the hour's / the day's rows. The bean never re-reads
 * {@code wifi_flow_window}.
 *
 * <p>A NEW entity type: its bookmark is per ({@code wifi_flow}, bean name), and a schema that has {@code cdr}
 * bookmarks but none of {@code wifi_flow} starts these beans at 0 ({@code OutboxReader.seedBookmarks}: "no bookmark
 * at all for the entity"), so every flow row billing-core wrote before the beans existed is summed. One pair of
 * tables per tier schema, {@code sum_wifi_dst_<window token>}; a per-window instance adds only {@link #window()} and
 * is made from the profile ({@code summary.beans.<name>.kind: wifi-dst}, {@code WifiSummaries}).
 *
 * <p>A row with no readable {@code window_start} has no window to count it in: it is skipped and said once per batch
 * (the ad bean's rule for a view with no start); a blob that is not a JSON array of rows is a poison row.
 */
public abstract class WifiDestinationSummaryBean implements SummaryBean<WifiDestinationSummary> {

    private static final Logger LOG = Logger.getLogger(WifiDestinationSummaryBean.class);

    /** The outbox {@code entity_type} the destination beans consume: billing-core's flow ingest ({@code WifiFlowOutbox.EntityType}). */
    public static final String ENTITY_TYPE = "wifi_flow";

    private final ObjectMapper blobMapper;
    private final String name;
    private final String context;
    private final SummaryMode mode;
    private final ZoneId tenantZone;
    private final WifiDestinationSummaryGenerator generator;

    /** The settings passed in; the mode from config (default incremental); the root tenant's zone for the bucket. */
    protected WifiDestinationSummaryBean(ObjectMapper blobMapper, String name, String context, ZoneId tenantZone) {
        this.blobMapper = CdrBlobMapper.from(blobMapper);
        this.name = name;
        this.context = context;
        this.mode = SummaryMode.parse(optString(name, "mode"));
        this.tenantZone = tenantZone;
        this.generator = new WifiDestinationSummaryGenerator(tenantZone);
    }

    @Override
    public SummaryMode mode() {
        return mode;
    }

    @Override
    public abstract WindowSize window();

    @Override
    public String name() {
        return name;
    }

    @Override
    public String entityType() {
        return ENTITY_TYPE;
    }

    @Override
    public String contextName() {
        return context;
    }

    /** The zone the windows' starts are put on before they are cut: the root tenant's wall clock. */
    public ZoneId tenantZone() {
        return tenantZone;
    }

    /** {@code sum_wifi_dst_day} / {@code sum_wifi_dst_hr}. */
    @Override
    public String table() {
        return "sum_wifi_dst_" + window().tableToken();
    }

    /** The table, described once for every engine; MySQL partitions it by day, the full set inside the CREATE. */
    @Override
    public SummaryTableSpec tableSpec() {
        return SumWifiDestinationDdl.table(table());
    }

    @Override
    public String insertColumnsCsv() {
        return WifiDestinationSummary.INSERT_COLUMNS;
    }

    @Override
    public String bucketColumn() {
        return WifiDestinationSummary.BUCKET_COLUMN;
    }

    /** The blob's rows, each with its window's start read; a row with none is skipped and said; then one delta per row. */
    @Override
    public List<WifiDestinationSummary> buildBatch(byte[] decompressedRowJson, String tier) {
        List<WifiFlowRow> rows = WifiFlowBlob.decode(blobMapper, decompressedRowJson, name);
        List<Timed> kept = new ArrayList<>(rows.size());
        int skippedNoWindow = 0;
        for (WifiFlowRow row : rows) {
            Instant windowStart = WifiFlowBlob.windowStartOf(row);
            if (windowStart == null) {
                skippedNoWindow++;                           // no start = no window to count it in; never NPE the drain
                continue;
            }
            kept.add(new Timed(row, windowStart));
        }
        if (skippedNoWindow > 0) {
            LOG.warnf("bean=%s tier=%s skipped %d flow row%s with no readable window_start", name, tier, skippedNoWindow,
                    skippedNoWindow == 1 ? "" : "s");
        }
        return generator.generate(kept, window());
    }

    @Override
    public LocalDateTime bucketOf(WifiDestinationSummary entity) {
        return entity.tup_starttime;
    }

    @Override
    public WifiDestinationSummary mapRow(ResultSet rs) throws SQLException {
        WifiDestinationSummary s = new WifiDestinationSummary();
        s.setId(rs.getLong("id"));
        s.tup_gw = str(rs, "tup_gw");
        s.tup_site = str(rs, "tup_site");
        s.tup_zone = str(rs, "tup_zone");
        s.tup_remote = str(rs, "tup_remote");
        s.tup_proto = rs.getInt("tup_proto");
        s.tup_port = rs.getInt("tup_port");
        s.tup_starttime = rs.getObject("tup_starttime", LocalDateTime.class);
        s.bytes_up = rs.getLong("bytes_up");
        s.bytes_dn = rs.getLong("bytes_dn");
        s.packets_up = rs.getLong("packets_up");
        s.packets_dn = rs.getLong("packets_dn");
        s.flows = rs.getLong("flows");
        return s;
    }

    private static String str(ResultSet rs, String column) throws SQLException {
        String v = rs.getString(column);
        return v == null ? "" : v;
    }

    private static String optString(String name, String key) {
        return ConfigProvider.getConfig()
                .getOptionalValue("summary.beans." + name + "." + key, String.class).orElse(null);
    }
}
