package com.telcobright.summary.summarybeans.call.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.call.model.CallSummary;
import com.telcobright.summary.summarybeans.call.model.CdrBlobEntry;
import com.telcobright.summary.summarybeans.call.model.Chargeable;

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
 * The shared <b>call</b> summary machinery over the {@link CallSummary} entity (the 47-col {@code sum_voice}
 * row). It decodes an outbox row's batch of {@code {Cdr, Chargeables[]}} entries (v1 {@code {Cdr, Customer}}
 * tolerated permanently), picks each entry's CUSTOMER leg, keeps the ones for its configured service group,
 * and builds a bucketed {@link CallSummary} per entry via {@link CallSummaryBuilder}; the engine does the
 * load-merge-write. Everything here is window-independent — the concrete per-window beans
 * ({@code HourlySummary}, {@code DailySummary}, …) add ONE thing: {@link #window()}.
 *
 * <p>This is the extension point for the {@code call} category: a new window = a new tiny subclass in the parent
 * {@code call} package. Each subclass is a CDI bean discovered + activated by name from {@code summary.enabledSummary};
 * its {@code table} / {@code service-group} / {@code context} come from {@code summary.beans.<name>} in the
 * active profile yml.
 *
 * <p>The bean declares {@link #contextName()} so the registry loads that context, but the call build reads only
 * the blob — the MediationContext is not load-bearing here (per the pinned contract).
 */
public abstract class CallSummaryBean implements SummaryBean<CallSummary> {

    private static final Logger LOG = Logger.getLogger(CallSummaryBean.class);

    private final ObjectMapper blobMapper;
    private final String name;
    private final String tableSuffix;   // selects the pre-provisioned set, e.g. "3" -> sum_voice_<window>_3
    private final int serviceGroup;
    private final String context;

    /** CDI path: read {@code table-suffix}/{@code service-group}/{@code context} from {@code summary.beans.<name>}. */
    protected CallSummaryBean(ObjectMapper blobMapper, String name) {
        this(blobMapper, name,
                optString(name, "table-suffix"),
                optInt(name, "service-group", 10),
                optString(name, "context"));
    }

    /** Explicit path (tests / non-CDI wiring): the settings are passed in directly. */
    protected CallSummaryBean(ObjectMapper blobMapper, String name, String tableSuffix, int serviceGroup, String context) {
        // a case-insensitive + JavaTime copy for the C# PascalCase outbox blob
        this.blobMapper = CdrBlobMapper.from(blobMapper);
        this.name = name;
        this.tableSuffix = tableSuffix;
        this.serviceGroup = serviceGroup;
        this.context = context;
    }

    /** The one thing a per-window subclass fixes — its time bucket (hourly / daily / 5min / weekly / …). */
    @Override
    public abstract WindowSize window();

    @Override
    public String name() {
        return name;
    }

    /** The outbox {@code entity_type} the call category consumes — PINNED to {@code "cdr"} by billing-core. */
    @Override
    public String entityType() {
        return "cdr";
    }

    @Override
    public String contextName() {
        return context;
    }

    /**
     * The target table — DERIVED as {@code sum_voice_<window token>_<table-suffix>} (e.g.
     * {@code sum_voice_day_3}). The suffix selects one of the pre-provisioned table sets (config maps each
     * service group to a suffix); the window token is {@code day}/{@code hr}/{@code 5min}/…. The service group
     * only filters records — it does NOT name the table.
     */
    @Override
    public String table() {
        if (tableSuffix == null || tableSuffix.isBlank()) {
            throw new IllegalStateException(
                    "bean '" + name + "' needs a table-suffix (summary.beans." + name + ".table-suffix)");
        }
        if (!tableSuffix.matches("[A-Za-z0-9_]+")) {
            // the derived name is interpolated into Statement-built SQL (with allowMultiQueries on), so a
            // stray yml value must die HERE at activation, not reach the database
            throw new IllegalStateException("bean '" + name + "' has an invalid table-suffix '" + tableSuffix
                    + "' — only letters, digits and _ are allowed");
        }
        return "sum_voice_" + window().tableToken() + "_" + tableSuffix;
    }

    /** Self-provisioning DDL: the canonical sum_voice shape with the full daily partition set in the CREATE. */
    @Override
    public String tableDdl() {
        return SumVoiceDdl.createTableIfNotExists(table());
    }

    @Override
    public String insertColumnsCsv() {
        return CallSummary.INSERT_COLUMNS;
    }

    @Override
    public String bucketColumn() {
        return CallSummary.BUCKET_COLUMN;
    }

    @Override
    public List<CallSummary> buildBatch(byte[] decompressedRowJson) {
        List<CdrBlobEntry> entries = decode(decompressedRowJson);
        List<CallSummary> built = new ArrayList<>(entries.size());
        int skippedMalformed = 0;
        for (CdrBlobEntry entry : entries) {
            Chargeable customerLeg = entry.customerLeg();   // v2 legs list, v1 single leg — dual-decode
            if (entry.cdr() == null || customerLeg == null || entry.cdr().startTime() == null) {
                skippedMalformed++;                          // never NPE the whole drain on one bad entry
                continue;
            }
            if (customerLeg.servicegroup() != serviceGroup) {
                continue;   // not this bean's service group
            }
            built.add(CallSummaryBuilder.build(entry.cdr(), customerLeg, window()));
        }
        if (skippedMalformed > 0) {
            LOG.warnf("bean=%s skipped %d malformed blob entr%s (null cdr/leg/StartTime)", name,
                    skippedMalformed, skippedMalformed == 1 ? "y" : "ies");
        }
        return built;
    }

    @Override
    public LocalDateTime bucketOf(CallSummary entity) {
        return entity.tup_starttime;
    }

    @Override
    public CallSummary mapRow(ResultSet rs) throws SQLException {
        CallSummary s = new CallSummary();
        s.setId(rs.getLong("id"));
        s.tup_switchid = rs.getInt("tup_switchid");
        s.tup_inpartnerid = rs.getInt("tup_inpartnerid");
        s.tup_outpartnerid = rs.getInt("tup_outpartnerid");
        s.tup_incomingroute = str(rs, "tup_incomingroute");
        s.tup_outgoingroute = str(rs, "tup_outgoingroute");
        s.tup_customerrate = dec(rs, "tup_customerrate");
        s.tup_supplierrate = dec(rs, "tup_supplierrate");
        s.tup_incomingip = str(rs, "tup_incomingip");
        s.tup_outgoingip = str(rs, "tup_outgoingip");
        s.tup_countryorareacode = str(rs, "tup_countryorareacode");
        s.tup_matchedprefixcustomer = str(rs, "tup_matchedprefixcustomer");
        s.tup_matchedprefixsupplier = str(rs, "tup_matchedprefixsupplier");
        s.tup_sourceId = str(rs, "tup_sourceId");
        s.tup_destinationId = str(rs, "tup_destinationId");
        s.tup_customercurrency = str(rs, "tup_customercurrency");
        s.tup_suppliercurrency = str(rs, "tup_suppliercurrency");
        s.tup_tax1currency = str(rs, "tup_tax1currency");
        s.tup_tax2currency = str(rs, "tup_tax2currency");
        s.tup_vatcurrency = str(rs, "tup_vatcurrency");
        s.tup_starttime = rs.getObject("tup_starttime", LocalDateTime.class);
        s.totalcalls = rs.getLong("totalcalls");
        s.connectedcalls = rs.getLong("connectedcalls");
        s.connectedcallsCC = rs.getLong("connectedcallsCC");
        s.successfulcalls = rs.getLong("successfulcalls");
        s.actualduration = dec(rs, "actualduration");
        s.roundedduration = dec(rs, "roundedduration");
        s.duration1 = dec(rs, "duration1");
        s.duration2 = dec(rs, "duration2");
        s.duration3 = dec(rs, "duration3");
        s.PDD = dec(rs, "PDD");
        s.customercost = dec(rs, "customercost");
        s.suppliercost = dec(rs, "suppliercost");
        s.tax1 = dec(rs, "tax1");
        s.tax2 = dec(rs, "tax2");
        s.vat = dec(rs, "vat");
        s.intAmount1 = rs.getInt("intAmount1");
        s.intAmount2 = rs.getInt("intAmount2");
        s.intAmount3 = rs.getInt("intAmount3");
        s.longAmount1 = rs.getLong("longAmount1");
        s.longAmount2 = rs.getLong("longAmount2");
        s.longAmount3 = rs.getLong("longAmount3");
        s.longDecimalAmount1 = dec(rs, "longDecimalAmount1");
        s.longDecimalAmount2 = dec(rs, "longDecimalAmount2");
        s.longDecimalAmount3 = dec(rs, "longDecimalAmount3");
        s.decimalAmount1 = dec(rs, "decimalAmount1");
        s.decimalAmount2 = dec(rs, "decimalAmount2");
        s.decimalAmount3 = dec(rs, "decimalAmount3");
        return s;
    }

    private List<CdrBlobEntry> decode(byte[] json) {
        try {
            return blobMapper.readValue(json,
                    blobMapper.getTypeFactory().constructCollectionType(List.class, CdrBlobEntry.class));
        } catch (IOException e) {
            throw new IllegalArgumentException("malformed outbox blob for " + name, e);
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

    private static int optInt(String name, String key, int defaultValue) {
        return ConfigProvider.getConfig()
                .getOptionalValue("summary.beans." + name + "." + key, Integer.class).orElse(defaultValue);
    }
}
