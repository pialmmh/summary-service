package com.telcobright.summary.summarybeans.chargeable.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryMode;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.call.internal.CdrBlobMapper;
import com.telcobright.summary.summarybeans.call.model.CdrBlobEntry;
import com.telcobright.summary.summarybeans.call.model.Chargeable;
import com.telcobright.summary.summarybeans.chargeable.model.ChargeableSummary;

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
 * The shared <b>chargeable</b> summary machinery over the {@link ChargeableSummary} entity. It consumes the
 * SAME cdr outbox stream as the call category ({@code entityType() = "cdr"} — the blob model + mapper are
 * reused from {@code summarybeans/call/model}, the one pinned blob contract) but rolls up EVERY chargeable
 * leg of every entry: no service-group filter, no direction filter. The target table is FIXED per window —
 * {@code sum_chargeable_<window token>} — no suffix concept. A per-window subclass adds only {@link #window()}.
 */
public abstract class ChargeableSummaryBean implements SummaryBean<ChargeableSummary> {

    private static final Logger LOG = Logger.getLogger(ChargeableSummaryBean.class);
    private static final ChargeableSummaryGenerator GENERATOR = new ChargeableSummaryGenerator();   // stateless, shared

    private final ObjectMapper blobMapper;
    private final String name;
    private final String context;
    private final SummaryMode mode;

    /** CDI path: only {@code context}/{@code mode} come from {@code summary.beans.<name>} (no service-group / table-suffix). */
    protected ChargeableSummaryBean(ObjectMapper blobMapper, String name) {
        this(blobMapper, name, optString(name, "context"));
    }

    /** Explicit path (tests / non-CDI wiring); mode from config (default incremental). */
    protected ChargeableSummaryBean(ObjectMapper blobMapper, String name, String context) {
        this.blobMapper = CdrBlobMapper.from(blobMapper);
        this.name = name;
        this.context = context;
        this.mode = SummaryMode.parse(optString(name, "mode"));
    }

    /** The per-bean fold setting — all outbox polls run INCREMENTAL. */
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

    /** Same outbox stream as the call category — the chargeable rollup reads the same {@code cdr} rows. */
    @Override
    public String entityType() {
        return "cdr";
    }

    @Override
    public String contextName() {
        return context;
    }

    /** FIXED per window: {@code sum_chargeable_day} / {@code sum_chargeable_hr} / … — no suffix sets. */
    @Override
    public String table() {
        return "sum_chargeable_" + window().tableToken();
    }

    /** Self-provisioning DDL: the canonical sum_chargeable shape with the full daily partition set in the CREATE. */
    @Override
    public String tableDdl() {
        return SumChargeableDdl.createTableIfNotExists(table());
    }

    @Override
    public String insertColumnsCsv() {
        return ChargeableSummary.INSERT_COLUMNS;
    }

    @Override
    public String bucketColumn() {
        return ChargeableSummary.BUCKET_COLUMN;
    }

    @Override
    public List<ChargeableSummary> buildBatch(byte[] decompressedRowJson) {
        List<CdrBlobEntry> entries = decode(decompressedRowJson);
        List<Chargeable> kept = new ArrayList<>();
        int skippedMalformed = 0;
        for (CdrBlobEntry entry : entries) {
            for (Chargeable leg : entry.legs()) {           // EVERY leg — customer and supplier rows both roll up
                if (leg == null || leg.transactionTime() == null) {
                    skippedMalformed++;                     // billing always stamps it; guard, never NPE the drain
                    continue;
                }
                kept.add(leg);
            }
        }
        if (skippedMalformed > 0) {
            LOG.warnf("bean=%s skipped %d malformed chargeable leg(s) (null leg/transactionTime)", name, skippedMalformed);
        }
        return GENERATOR.generate(kept, window());
    }

    @Override
    public LocalDateTime bucketOf(ChargeableSummary entity) {
        return entity.tup_transactiontime;
    }

    @Override
    public ChargeableSummary mapRow(ResultSet rs) throws SQLException {
        ChargeableSummary s = new ChargeableSummary();
        s.setId(rs.getLong("id"));
        s.tup_servicegroup = rs.getInt("tup_servicegroup");
        s.tup_servicefamily = rs.getInt("tup_servicefamily");
        s.tup_assigneddirection = rs.getInt("tup_assigneddirection");
        s.tup_productid = rs.getLong("tup_productid");
        s.tup_billeduom = str(rs, "tup_billeduom");
        s.tup_prefix = str(rs, "tup_prefix");
        s.tup_transactiontime = rs.getObject("tup_transactiontime", LocalDateTime.class);
        s.totalcount = rs.getLong("totalcount");
        s.BilledAmount = dec(rs, "BilledAmount");
        s.Quantity = dec(rs, "Quantity");
        s.TaxAmount1 = dec(rs, "TaxAmount1");
        s.TaxAmount2 = dec(rs, "TaxAmount2");
        s.TaxAmount3 = dec(rs, "TaxAmount3");
        s.VatAmount1 = dec(rs, "VatAmount1");
        s.VatAmount2 = dec(rs, "VatAmount2");
        s.VatAmount3 = dec(rs, "VatAmount3");
        s.OtherAmount1 = dec(rs, "OtherAmount1");
        s.OtherAmount2 = dec(rs, "OtherAmount2");
        s.OtherAmount3 = dec(rs, "OtherAmount3");
        s.OtherDecAmount1 = dec(rs, "OtherDecAmount1");
        s.OtherDecAmount2 = dec(rs, "OtherDecAmount2");
        s.OtherDecAmount3 = dec(rs, "OtherDecAmount3");
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
}
