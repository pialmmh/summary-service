package com.telcobright.summary.summarybeans.wifi.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryMode;
import com.telcobright.summary.bean.spi.SummaryTableSpec;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.call.internal.CdrBlobMapper;
import com.telcobright.summary.summarybeans.call.model.CdrBlobEntry;
import com.telcobright.summary.summarybeans.call.model.Chargeable;
import com.telcobright.summary.summarybeans.call.model.RatedCall;
import com.telcobright.summary.summarybeans.wifi.model.WifiSessionSummary;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The shared WiFi <b>session</b> summary machinery over the {@link WifiSessionSummary} entity (SS-0006 item 2; the WiFi
 * architect's spec §3). A WiFi session is a Call of service group 101: billing-core writes each tier's record as a
 * {@code cdr} row in the tier's own schema, and that schema's outbox entry ({@code entity_type = 'cdr'}, blob v2
 * {@code {Cdr, Chargeables}}) is what this bean reads — the SAME stream the call, chargeable and ad categories read.
 * It keeps the entries whose CUSTOMER chargeable is of group 101 — the call bean's filter, copied
 * ({@code CallSummaryBean.buildBatch:135-156}) — and builds one delta each; every other group is not its business.
 *
 * <p>One pair of tables per tier schema, {@code sum_wifi_<window token>_101} ({@code 101} = the WiFi session's service
 * group, fixed), made by the service itself on both engines. A per-window instance adds only {@link #window()}; the
 * instances are made from the profile ({@code summary.beans.<name>.kind: wifi-session}, {@code WifiSummaries}).
 */
public abstract class WifiSessionSummaryBean implements SummaryBean<WifiSessionSummary> {

    private static final Logger LOG = Logger.getLogger(WifiSessionSummaryBean.class);
    private static final WifiSessionSummaryGenerator GENERATOR = new WifiSessionSummaryGenerator();   // stateless, shared

    /** The outbox {@code entity_type} the session beans consume: the call's own stream. */
    public static final String ENTITY_TYPE = "cdr";
    /** The WiFi session's service group (billing-core's {@code SgWifiDomestic}; stated, pre-rated, never detected). */
    public static final int SERVICE_GROUP = 101;
    /** The fixed suffix of the table: the service group. */
    public static final String TABLE_SUFFIX = "101";

    private final ObjectMapper blobMapper;
    private final String name;
    private final String context;
    private final SummaryMode mode;

    /** The settings passed in; the mode from config (default incremental), as every bean reads it. */
    protected WifiSessionSummaryBean(ObjectMapper blobMapper, String name, String context) {
        this.blobMapper = CdrBlobMapper.from(blobMapper);
        this.name = name;
        this.context = context;
        this.mode = SummaryMode.parse(optString(name, "mode"));
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

    /** {@code sum_wifi_day_101} / {@code sum_wifi_hr_101} — the service group is the fixed suffix. */
    @Override
    public String table() {
        return "sum_wifi_" + window().tableToken() + "_" + TABLE_SUFFIX;
    }

    /** The table, described once for every engine; MySQL partitions it by day, the full set inside the CREATE. */
    @Override
    public SummaryTableSpec tableSpec() {
        return SumWifiSessionDdl.table(table());
    }

    @Override
    public String insertColumnsCsv() {
        return WifiSessionSummary.INSERT_COLUMNS;
    }

    @Override
    public String bucketColumn() {
        return WifiSessionSummary.BUCKET_COLUMN;
    }

    /** The call bean's filter ({@code CallSummaryBean.buildBatch}): the customer leg, group 101, a start time; then one delta per session. */
    @Override
    public List<WifiSessionSummary> buildBatch(byte[] decompressedRowJson, String tier) {
        List<CdrBlobEntry> entries = decode(decompressedRowJson);
        List<RatedCall> kept = new ArrayList<>();
        int skippedMalformed = 0;
        for (CdrBlobEntry entry : entries) {
            Chargeable customerLeg = entry.customerLeg();   // v2 legs list, v1 single leg — dual-decode
            if (entry.cdr() == null || customerLeg == null || entry.cdr().startTime() == null) {
                skippedMalformed++;                          // never NPE the whole drain on one bad entry
                continue;
            }
            if (customerLeg.servicegroup() != SERVICE_GROUP) {
                continue;                                    // a view, a voice call: not this bean's
            }
            kept.add(new RatedCall(entry.cdr(), customerLeg));
        }
        if (skippedMalformed > 0) {
            LOG.warnf("bean=%s skipped %d malformed blob entr%s (null cdr/leg/StartTime)", name,
                    skippedMalformed, skippedMalformed == 1 ? "y" : "ies");
        }
        return GENERATOR.generate(kept, window());
    }

    @Override
    public LocalDateTime bucketOf(WifiSessionSummary entity) {
        return entity.tup_starttime;
    }

    @Override
    public WifiSessionSummary mapRow(ResultSet rs) throws SQLException {
        WifiSessionSummary s = new WifiSessionSummary();
        s.setId(rs.getLong("id"));
        s.tup_switchid = rs.getInt("tup_switchid");
        s.tup_inpartnerid = rs.getInt("tup_inpartnerid");
        s.tup_site = str(rs, "tup_site");
        s.tup_zone = str(rs, "tup_zone");
        s.tup_granttier = str(rs, "tup_granttier");
        s.tup_category = rs.getInt("tup_category");
        s.tup_packagecode = str(rs, "tup_packagecode");
        s.tup_customercurrency = str(rs, "tup_customercurrency");
        s.tup_causefamily = str(rs, "tup_causefamily");
        s.tup_starttime = rs.getObject("tup_starttime", LocalDateTime.class);
        s.sessions = rs.getLong("sessions");
        s.sessions_connected = rs.getLong("sessions_connected");
        s.total_duration_sec = rs.getLong("total_duration_sec");
        s.avg_session_sec = dec(rs, "avg_session_sec");
        s.bytes_up = rs.getLong("bytes_up");
        s.bytes_dn = rs.getLong("bytes_dn");
        s.customercost = dec(rs, "customercost");
        return s;
    }

    private List<CdrBlobEntry> decode(byte[] json) {
        try {
            return blobMapper.readValue(json,
                    blobMapper.getTypeFactory().constructCollectionType(List.class, CdrBlobEntry.class));
        } catch (IOException e) {
            throw new IllegalArgumentException("malformed cdr outbox blob for " + name, e);
        }
    }

    private static String str(ResultSet rs, String column) throws SQLException {
        String v = rs.getString(column);
        return v == null ? "" : v;
    }

    private static BigDecimal dec(ResultSet rs, String column) throws SQLException {
        BigDecimal v = rs.getBigDecimal(column);
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String optString(String name, String key) {
        return ConfigProvider.getConfig()
                .getOptionalValue("summary.beans." + name + "." + key, String.class).orElse(null);
    }
}
