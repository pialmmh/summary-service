package com.telcobright.summary.beans.cdr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.WindowSize;
import com.telcobright.summary.registry.spi.BeanConfig;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The CDR summary bean over the {@link CdrSummary} entity — one configured instance per enabled summary
 * (daily, hourly, 5-minute, …) differing only by {@link #window()} + {@link #table()}. It decodes an outbox
 * row's batch of {@code {Cdr, Customer}} entries, keeps the ones for its configured service group, and builds
 * a bucketed {@link CdrSummary} per entry via {@link CdrSummaryBuilder}. The engine does the load-merge-write.
 *
 * <p>The bean declares {@link #contextName()} so the registry loads that context, but the CDR build reads only
 * the blob — the MediationContext is not load-bearing here (per the pinned contract).
 */
public final class CdrSummaryBean implements SummaryBean<CdrSummary> {

    private final ObjectMapper blobMapper;
    private final BeanConfig config;
    private final int serviceGroup;

    public CdrSummaryBean(ObjectMapper blobMapper, BeanConfig config) {
        this.blobMapper = blobMapper;
        this.config = config;
        this.serviceGroup = config.serviceGroup() == null ? 10 : config.serviceGroup();
    }

    @Override
    public String name() {
        return config.name();
    }

    @Override
    public String entityType() {
        return config.entity();
    }

    @Override
    public String contextName() {
        return config.context();
    }

    @Override
    public String table() {
        return config.table();
    }

    @Override
    public String insertColumnsCsv() {
        return CdrSummary.INSERT_COLUMNS;
    }

    @Override
    public String bucketColumn() {
        return CdrSummary.BUCKET_COLUMN;
    }

    @Override
    public WindowSize window() {
        return config.window();
    }

    @Override
    public List<CdrSummary> buildBatch(byte[] decompressedRowJson) {
        List<CdrBlobEntry> entries = decode(decompressedRowJson);
        List<CdrSummary> built = new ArrayList<>(entries.size());
        for (CdrBlobEntry entry : entries) {
            if (entry.cdr() == null || entry.customer() == null) {
                continue;
            }
            if (entry.customer().servicegroup() != serviceGroup) {
                continue;   // not this bean's service group
            }
            built.add(CdrSummaryBuilder.build(entry.cdr(), entry.customer(), config.window()));
        }
        return built;
    }

    @Override
    public LocalDateTime bucketOf(CdrSummary entity) {
        return entity.tup_starttime;
    }

    @Override
    public CdrSummary mapRow(ResultSet rs) throws SQLException {
        CdrSummary s = new CdrSummary();
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
            throw new IllegalArgumentException("malformed outbox blob for " + config.name(), e);
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
}
