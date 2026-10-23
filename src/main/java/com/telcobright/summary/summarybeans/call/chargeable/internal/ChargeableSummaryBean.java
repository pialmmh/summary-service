package com.telcobright.summary.summarybeans.call.chargeable.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.CrossKindReader;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryMode;
import com.telcobright.summary.bean.spi.SummaryTableSpec;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.summarybeans.call.internal.CdrBlobMapper;
import com.telcobright.summary.summarybeans.call.model.CdrBlobEntry;
import com.telcobright.summary.summarybeans.call.model.Chargeable;
import com.telcobright.summary.summarybeans.call.chargeable.model.ChargeableSummary;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * The shared <b>chargeable</b> summary machinery over the {@link ChargeableSummary} entity. It consumes the
 * SAME cdr outbox stream as the call category ({@code entityType() = "cdr"} — the blob model + mapper are
 * reused from {@code summarybeans/call/model}, the one pinned blob contract) but rolls up EVERY chargeable
 * leg of every entry: no service-group filter, no direction filter. The target table is FIXED per window —
 * {@code sum_chargeable_<window token>} — no suffix concept. A per-window subclass adds only {@link #window()}.
 *
 * <p>SS-0008: the call kind's — and the one bean whose input crosses the kinds ({@link CrossKindReader}). The start tells it
 * the service groups of the kinds the tenant enabled: a record of any other group (its customer leg's, the call beans' rule)
 * is summed by no leg here — the kinds' gate counts it. Untold, every record is read, as before kinds.
 */
public abstract class ChargeableSummaryBean implements SummaryBean<ChargeableSummary>, CrossKindReader {

    private static final Logger LOG = Logger.getLogger(ChargeableSummaryBean.class);
    private static final ChargeableSummaryGenerator GENERATOR = new ChargeableSummaryGenerator();   // stateless, shared

    private final ObjectMapper blobMapper;
    private final String name;
    private final String context;
    private final SummaryMode mode;
    /** The service groups whose records this bean sums: every group until the start says otherwise (SS-0008). */
    private volatile IntPredicate serviceGroupsRead = serviceGroup -> true;

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

    /** SS-0008: sum only the records whose service group {@code serviceGroups} admits — the start passes the enabled kinds'. */
    @Override
    public void readOnly(IntPredicate serviceGroups) {
        this.serviceGroupsRead = serviceGroups;
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

    /** The table, described once for every engine; MySQL partitions it by day, the full set inside the CREATE. */
    @Override
    public SummaryTableSpec tableSpec() {
        return SumChargeableDdl.table(table());
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
    public List<ChargeableSummary> buildBatch(byte[] decompressedRowJson, String tier) {
        List<CdrBlobEntry> entries = decode(decompressedRowJson);
        List<Chargeable> kept = new ArrayList<>();
        int skippedMalformed = 0;
        for (CdrBlobEntry entry : entries) {
            if (!readsTheKindOf(entry)) {
                continue;                                   // a record of a kind the tenant did not enable: counted by the gate, summed by none
            }
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

    /** A record is of its customer leg's group (the call beans' rule); one with no leg has nothing to sum either way. */
    private boolean readsTheKindOf(CdrBlobEntry entry) {
        Chargeable customerLeg = entry.customerLeg();
        return customerLeg == null || serviceGroupsRead.test(customerLeg.servicegroup());
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
