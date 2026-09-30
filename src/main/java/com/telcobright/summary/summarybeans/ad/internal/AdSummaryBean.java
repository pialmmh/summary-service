package com.telcobright.summary.summarybeans.ad.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryMode;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.ad.model.AdCdrEntry;
import com.telcobright.summary.summarybeans.ad.model.AdLeg;
import com.telcobright.summary.summarybeans.ad.model.AdSummary;
import com.telcobright.summary.summarybeans.ad.model.AdTier;
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
 * The shared <b>ad</b> summary machinery over the {@link AdSummary} entity (design AD-AS-CALL §2.9). It consumes
 * the {@code ad_cdr} outbox stream ad-sphere's terminal write fills (seed-callflow's {@code LevelCdrWriter}: one
 * {@code summary_affected} row per ended ad call, the v2 envelope {@code {Cdr, Chargeables:[every tier]}}) and
 * rolls up EVERY tier of every call into one row each — a reseller's summary is the rows where {@code tup_tenant}
 * = its database. A call nothing admitted (no tier) still counts once, on the entry tenant. The target table is
 * {@code sum_ad_<window token>_30} ({@code 30} = the ad service group, fixed). A per-window subclass adds only
 * {@link #window()}.
 */
public abstract class AdSummaryBean implements SummaryBean<AdSummary> {

    private static final Logger LOG = Logger.getLogger(AdSummaryBean.class);
    private static final AdSummaryGenerator GENERATOR = new AdSummaryGenerator();   // stateless, shared

    /** The outbox {@code entity_type} the ad category consumes — PINNED (design §3 item 7). */
    public static final String ENTITY_TYPE = "ad_cdr";
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
    public List<AdSummary> buildBatch(byte[] decompressedRowJson) {
        List<AdCdrEntry> entries = decode(decompressedRowJson);
        List<AdTier> kept = new ArrayList<>();
        int skippedMalformed = 0;
        for (AdCdrEntry entry : entries) {
            if (entry == null || entry.cdr() == null || entry.cdr().startTime() == null) {
                skippedMalformed++;                          // never NPE the whole drain on one bad entry
                continue;
            }
            List<AdLeg> tiers = entry.tiers();
            if (tiers.isEmpty()) {
                kept.add(new AdTier(entry.cdr(), null));     // nothing admitted: one failed row on the entry tenant
                continue;
            }
            for (AdLeg tier : tiers) {                       // EVERY tier — the advertiser's row and the resellers' rows above it
                if (tier == null) { skippedMalformed++; continue; }
                kept.add(new AdTier(entry.cdr(), tier));
            }
        }
        if (skippedMalformed > 0) {
            LOG.warnf("bean=%s skipped %d malformed ad blob entr%s (null cdr/StartTime/tier)", name,
                    skippedMalformed, skippedMalformed == 1 ? "y" : "ies");
        }
        return GENERATOR.generate(kept, window());
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
        return s;
    }

    private List<AdCdrEntry> decode(byte[] json) {
        try {
            return blobMapper.readValue(json,
                    blobMapper.getTypeFactory().constructCollectionType(List.class, AdCdrEntry.class));
        } catch (IOException e) {
            throw new IllegalArgumentException("malformed ad_cdr outbox blob for " + name, e);
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
