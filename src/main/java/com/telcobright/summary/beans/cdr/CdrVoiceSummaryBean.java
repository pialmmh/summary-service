package com.telcobright.summary.beans.cdr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.ColumnType;
import com.telcobright.summary.bean.spi.CounterDef;
import com.telcobright.summary.bean.spi.DimensionDef;
import com.telcobright.summary.bean.spi.Granularity;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.WindowDef;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Function;

/**
 * The reference summary bean: CDR voice counters per DAY and per HOUR, ported from billing-core's
 * sum_voice_* summary. It declares the group-by dimensions (switch / partner / route / ip / prefix / rate /
 * currency) and the additive counters (calls / durations / cost / tax); the engine does the load-merge-write.
 *
 * <p>Tables, topic and batch size come from the active profile yml ({@code summary.beans.cdr-voice.*}). The
 * event mapping is PROVISIONAL — see {@link RatedCdrEvent}.
 */
@ApplicationScoped
public class CdrVoiceSummaryBean implements SummaryBean<RatedCdrEvent> {

    private final ObjectMapper mapper;
    private final String topic;
    private final String correctionTopic;
    private final int batchSize;
    private final ZoneId zone;
    private final List<WindowDef> windows;
    private final List<DimensionDef<RatedCdrEvent>> dimensions;
    private final List<CounterDef<RatedCdrEvent>> counters;

    @Inject
    public CdrVoiceSummaryBean(
            ObjectMapper mapper,
            @ConfigProperty(name = "summary.beans.cdr-voice.topic", defaultValue = "rated-cdr") String topic,
            @ConfigProperty(name = "summary.beans.cdr-voice.correction-topic", defaultValue = "rated-cdr-correction") String correctionTopic,
            @ConfigProperty(name = "summary.beans.cdr-voice.batch-size", defaultValue = "1000") int batchSize,
            @ConfigProperty(name = "summary.beans.cdr-voice.day-table", defaultValue = "sum_voice_day_03") String dayTable,
            @ConfigProperty(name = "summary.beans.cdr-voice.hour-table", defaultValue = "sum_voice_hr_03") String hourTable,
            @ConfigProperty(name = "summary.beans.cdr-voice.zone", defaultValue = "Asia/Dhaka") String zone) {
        this.mapper = mapper;
        this.topic = topic;
        this.correctionTopic = correctionTopic;
        this.batchSize = batchSize;
        this.zone = ZoneId.of(zone);
        this.windows = List.of(
                new WindowDef("day", Granularity.DAY, dayTable, "tup_starttime"),
                new WindowDef("hour", Granularity.HOUR, hourTable, "tup_starttime"));
        this.dimensions = buildDimensions();
        this.counters = buildCounters();
    }

    @Override
    public String name() {
        return "cdr-voice";
    }

    @Override
    public String topic() {
        return topic;
    }

    @Override
    public String correctionTopic() {
        return correctionTopic;
    }

    @Override
    public int batchSize() {
        return batchSize;
    }

    @Override
    public ZoneId zone() {
        return zone;
    }

    @Override
    public Instant eventTime(RatedCdrEvent event) {
        return Instant.ofEpochMilli(event.startEpochMillis());
    }

    @Override
    public List<WindowDef> windows() {
        return windows;
    }

    @Override
    public List<DimensionDef<RatedCdrEvent>> dimensions() {
        return dimensions;
    }

    @Override
    public List<CounterDef<RatedCdrEvent>> counters() {
        return counters;
    }

    @Override
    public RatedCdrEvent deserialize(byte[] payload) {
        try {
            return mapper.readValue(payload, RatedCdrEvent.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("malformed rated-cdr payload", e);
        }
    }

    private static List<DimensionDef<RatedCdrEvent>> buildDimensions() {
        return List.of(
                dim("tup_switchid", ColumnType.INT, RatedCdrEvent::switchId),
                dim("tup_inpartnerid", ColumnType.INT, RatedCdrEvent::inPartnerId),
                dim("tup_outpartnerid", ColumnType.INT, RatedCdrEvent::outPartnerId),
                dim("tup_incomingroute", ColumnType.STRING, RatedCdrEvent::incomingRoute),
                dim("tup_outgoingroute", ColumnType.STRING, RatedCdrEvent::outgoingRoute),
                dim("tup_customerrate", ColumnType.DECIMAL, RatedCdrEvent::customerRate),
                dim("tup_supplierrate", ColumnType.DECIMAL, RatedCdrEvent::supplierRate),
                dim("tup_incomingip", ColumnType.STRING, RatedCdrEvent::incomingIp),
                dim("tup_outgoingip", ColumnType.STRING, RatedCdrEvent::outgoingIp),
                dim("tup_countryorareacode", ColumnType.STRING, RatedCdrEvent::countryOrAreaCode),
                dim("tup_matchedprefixcustomer", ColumnType.STRING, RatedCdrEvent::matchedPrefixCustomer),
                dim("tup_matchedprefixsupplier", ColumnType.STRING, RatedCdrEvent::matchedPrefixSupplier),
                dim("tup_sourceId", ColumnType.STRING, RatedCdrEvent::sourceId),
                dim("tup_destinationId", ColumnType.STRING, RatedCdrEvent::destinationId),
                dim("tup_customercurrency", ColumnType.STRING, RatedCdrEvent::customerCurrency),
                dim("tup_suppliercurrency", ColumnType.STRING, RatedCdrEvent::supplierCurrency));
    }

    private static List<CounterDef<RatedCdrEvent>> buildCounters() {
        return List.of(
                count("totalcalls", ColumnType.LONG, e -> 1),
                count("connectedcalls", ColumnType.LONG, e -> e.connected() ? 1 : 0),
                count("successfulcalls", ColumnType.LONG, e -> isSuccessful(e) ? 1 : 0),
                count("actualduration", ColumnType.DECIMAL, RatedCdrEvent::durationSec),
                count("roundedduration", ColumnType.DECIMAL, RatedCdrEvent::roundedDuration),
                count("duration1", ColumnType.DECIMAL, RatedCdrEvent::duration1),
                count("customercost", ColumnType.DECIMAL, RatedCdrEvent::customerCost),
                count("suppliercost", ColumnType.DECIMAL, RatedCdrEvent::supplierCost),
                count("tax1", ColumnType.DECIMAL, RatedCdrEvent::tax1),
                count("tax2", ColumnType.DECIMAL, RatedCdrEvent::tax2));
    }

    private static boolean isSuccessful(RatedCdrEvent event) {
        return event.chargingStatus() != null && event.chargingStatus() == 1;
    }

    private static DimensionDef<RatedCdrEvent> dim(String column, ColumnType type, Function<RatedCdrEvent, Object> extractor) {
        return new DimensionDef<>(column, type, extractor);
    }

    private static CounterDef<RatedCdrEvent> count(String column, ColumnType type, Function<RatedCdrEvent, Number> delta) {
        return new CounterDef<>(column, type, delta);
    }
}
