package com.telcobright.summary.summarybeans.ad.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryMode;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.ad.model.AdCallEntry;
import com.telcobright.summary.summarybeans.ad.model.AdSummary;
import com.telcobright.summary.summarybeans.ad.model.AdView;
import com.telcobright.summary.summarybeans.ad.model.AdViewFacts;
import com.telcobright.summary.summarybeans.call.internal.CdrBlobMapper;
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
 * The shared <b>ad</b> summary machinery over the {@link AdSummary} entity (ad-is-a-call §4.1, §5). An ad view is
 * a Call: billing-core writes each tier's record as a {@code cdr} row of service group 30 in the tier's own
 * schema, and that schema's outbox entry ({@code entity_type = 'cdr'}, blob v2 {@code {Cdr, Chargeables}}) is
 * what this bean reads — the SAME stream the call and chargeable categories read. It keeps the entries whose
 * {@code Cdr.ServiceGroup} is 30 and builds one row each; every other service group is not its business.
 *
 * <p>One pair of tables per tier schema: {@code sum_ad_<window token>_30} ({@code 30} = the ad service group,
 * fixed). The row's {@code tup_tenant} is the tier the drain serves — the schema's own name — never read from the
 * blob. A per-window subclass adds only {@link #window()}.
 */
public abstract class AdSummaryBean implements SummaryBean<AdSummary> {

    private static final Logger LOG = Logger.getLogger(AdSummaryBean.class);
    private static final AdSummaryGenerator GENERATOR = new AdSummaryGenerator();   // stateless, shared

    /** The outbox {@code entity_type} the ad category consumes: the call's own stream (ad-is-a-call §5). */
    public static final String ENTITY_TYPE = "cdr";
    /** The ad service group, the fixed suffix of the table. */
    public static final String TABLE_SUFFIX = "30";

    private final ObjectMapper blobMapper;
    private final String name;
    private final String context;
    private final SummaryMode mode;

    /** CDI path: only {@code context} / {@code mode} come from {@code summary.beans.<name>}. */
    protected AdSummaryBean(ObjectMapper blobMapper, String name) {
        this(blobMapper, name, optString(name, "context"));
    }

    /** Explicit path (tests / non-CDI wiring); mode from config (default incremental). */
    protected AdSummaryBean(ObjectMapper blobMapper, String name, String context) {
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

    /** {@code sum_ad_day_30} / {@code sum_ad_hr_30} / … — the ad service group is the fixed suffix. */
    @Override
    public String table() {
        return "sum_ad_" + window().tableToken() + "_" + TABLE_SUFFIX;
    }

    /** Self-provisioning DDL: the canonical sum_ad shape with the full daily partition set in the CREATE. */
    @Override
    public String tableDdl() {
        return SumAdDdl.createTableIfNotExists(table());
    }

    @Override
    public String insertColumnsCsv() {
        return AdSummary.INSERT_COLUMNS;
    }

    @Override
    public String bucketColumn() {
        return AdSummary.BUCKET_COLUMN;
    }

    @Override
    public List<AdSummary> buildBatch(byte[] decompressedRowJson, String tier) {
        requireTier(tier);
        List<AdCallEntry> entries = decode(decompressedRowJson);
        List<AdView> kept = new ArrayList<>();
        int skippedMalformed = 0;
        int unreadableMeta = 0;
        for (AdCallEntry entry : entries) {
            if (entry == null || entry.cdr() == null) {
                skippedMalformed++;                          // never NPE the whole drain on one bad entry
                continue;
            }
            if (!entry.cdr().isAdView()) {
                continue;                                    // a call of another service group: not this bean's
            }
            if (entry.cdr().startTime() == null) {
                skippedMalformed++;                          // no start = no window to count it in
                continue;
            }
            AdViewFacts facts = AdMetaData.parse(blobMapper, entry.cdr().additionalMetaData());
            if (facts == null) {
                unreadableMeta++;                            // the view still counts, without its facts
                facts = AdViewFacts.NONE;
            }
            kept.add(new AdView(entry.cdr(), facts, entry.customerLeg(), tier));
        }
        if (skippedMalformed > 0) {
            LOG.warnf("bean=%s tier=%s skipped %d malformed blob entr%s (null cdr / an ad view with no StartTime)", name,
                    tier, skippedMalformed, skippedMalformed == 1 ? "y" : "ies");
        }
        if (unreadableMeta > 0) {
            LOG.warnf("bean=%s tier=%s %d ad view(s) carry an AdditionalMetaData that is not a JSON object — counted "
                    + "without campaign, zone, site, app, completed, credited", name, tier, unreadableMeta);
        }
        return GENERATOR.generate(kept, window());
    }

    /**
     * The row's {@code tup_tenant} is the tier, so a build without one is refused. That is a wiring fault, not a
     * data fault — the drain checks the same before it reads a row, so this can never dead-letter a batch.
     */
    private void requireTier(String tier) {
        if (tier == null || tier.isBlank()) {
            throw new IllegalStateException("bean '" + name + "' needs the tier (the schema it is drained for): "
                    + "every sum_ad row carries it as tup_tenant");
        }
    }

    @Override
    public LocalDateTime bucketOf(AdSummary entity) {
        return entity.tup_starttime;
    }

    @Override
    public AdSummary mapRow(ResultSet rs) throws SQLException {
        AdSummary s = new AdSummary();
        s.setId(rs.getLong("id"));
        s.tup_tenant = str(rs, "tup_tenant");
        s.tup_partnerid = rs.getInt("tup_partnerid");
        s.tup_campaignid = rs.getInt("tup_campaignid");
        s.tup_rulecode = str(rs, "tup_rulecode");
        s.tup_zone = str(rs, "tup_zone");
        s.tup_site = str(rs, "tup_site");
        s.tup_app = str(rs, "tup_app");
        s.tup_mediakind = str(rs, "tup_mediakind");
        s.tup_outcome = str(rs, "tup_outcome");
        s.tup_starttime = rs.getObject("tup_starttime", LocalDateTime.class);
        s.views = rs.getLong("views");
        s.shown = rs.getLong("shown");
        s.completed = rs.getLong("completed");
        s.credited = rs.getLong("credited");
        s.failed = rs.getLong("failed");
        s.watchedsec = rs.getLong("watchedsec");
        s.chargedamount = dec(rs, "chargedamount");
        s.chargedunits = dec(rs, "chargedunits");
        return s;
    }

    private List<AdCallEntry> decode(byte[] json) {
        try {
            return blobMapper.readValue(json,
                    blobMapper.getTypeFactory().constructCollectionType(List.class, AdCallEntry.class));
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
