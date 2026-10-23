package com.telcobright.summary.registry.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryKind;
import com.telcobright.summary.outbox.spi.RowCensus;
import com.telcobright.summary.summarybeans.call.internal.CdrBlobMapper;
import com.telcobright.summary.summarybeans.call.model.CdrBlobEntry;
import com.telcobright.summary.summarybeans.call.model.Chargeable;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The rows of the kinds a tenant did NOT enable (SS-0008 item 2): a {@code cdr} row in the blob whose service group belongs
 * to such a kind is summed by no bean — the kind's beans are not activated, the call kind's chargeable roll-up is told to
 * read only the enabled kinds' groups — and it is COUNTED here, then said: ONE WARN per tenant, kind and day,
 * {@code KIND_NOT_ENABLED <kind> <n> rows …} at the first such row of the day; the day's total in an INFO when the day turns
 * and at stop. The outbox row is consumed as every other: nothing stuck, nothing lost without a word.
 *
 * <p>Counted ONCE per outbox row: only the drains of ONE bean per tier count (the first {@code cdr} bean the profile
 * enables), after their commit ({@link RowCensus}) — that bean's drain is exactly once per row, across restarts too. A
 * record's kind is the kind of its service group: its customer chargeable's {@code servicegroup} (the call beans' own rule),
 * else the record's {@code Cdr.ServiceGroup}, else 0 (the call's). The day is the tenant's ({@code summary.zone}).
 */
@ApplicationScoped
public class KindGate implements RowCensus {

    /** The word every line of this gate starts with. */
    public static final String MARK = "KIND_NOT_ENABLED";

    private static final Logger LOG = Logger.getLogger(KindGate.class);

    private final ObjectMapper probeMapper = CdrBlobMapper.create();   // the beans' decoder: case-insensitive, lenient
    private final Clock clock;
    private final Map<String, DayCount> days = new ConcurrentHashMap<>();   // "<tier>|<kind>" -> the day's count
    private volatile TenantKinds kinds = TenantKinds.everyKind();
    private volatile String countingBean;
    private volatile ZoneId zone = ZoneId.of("Asia/Dhaka");

    public KindGate() {
        this(Clock.systemUTC());
    }

    /** A test's clock (the ITs too). */
    public KindGate(Clock clock) {
        this.clock = clock;
    }

    /** At the start: the tenant's kinds, the ONE bean whose drains count (null: none), the tenant's zone (its day). */
    public void countFor(TenantKinds tenantKinds, String bean, ZoneId tenantZone) {
        this.kinds = tenantKinds;
        this.zone = tenantZone;
        this.countingBean = tenantKinds.enablesEveryKind() ? null : bean;
    }

    @Override
    public boolean counts(String tier, SummaryBean<?> bean) {
        String counting = countingBean;
        return counting != null && counting.equals(bean.name());
    }

    @Override
    public void committed(String tier, List<byte[]> decodedRows) {
        try {
            tally(decodedRows).forEach((kind, rows) -> count(tier, kind, rows));
        } catch (RuntimeException unreadable) {
            LOG.warnf("schema=%s the kinds of a committed outbox row could not be read — its rows are not counted: %s", tier, unreadable.toString());
        }
    }

    /** The rows of {@code kind} counted as not summed in {@code tier} on {@code day} (0 when none). */
    public long rowsNotSummed(String tier, String kind, LocalDate day) {
        DayCount count = days.get(tier + "|" + kind);
        return count != null && count.day.equals(day) ? count.rows : 0;
    }

    /** At stop: the running day's total of every tenant and kind. */
    @PreDestroy
    void sayTheTotals() {
        days.forEach((key, count) -> sayTheDaysTotal(key, count));
    }

    /** Per kind not enabled: how many records of these decoded outbox rows are its. */
    Map<SummaryKind, Integer> tally(List<byte[]> decodedRows) {
        Map<SummaryKind, Integer> tally = new LinkedHashMap<>();
        for (byte[] row : decodedRows) {
            for (KindProbe record : decode(row)) {
                SummaryKind kind = KnownKinds.ofServiceGroup(record == null ? 0 : record.serviceGroup());
                if (!kinds.enables(kind)) {
                    tally.merge(kind, 1, Integer::sum);
                }
            }
        }
        return tally;
    }

    private void count(String tier, SummaryKind kind, int rows) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        DayCount[] closed = new DayCount[1];
        boolean[] firstOfTheDay = new boolean[1];
        days.compute(tier + "|" + kind.word(), (key, count) -> {
            if (count != null && count.day.equals(today)) {
                count.rows += rows;
                return count;
            }
            closed[0] = count;
            firstOfTheDay[0] = true;
            return new DayCount(today, rows);
        });
        if (closed[0] != null) {
            sayTheDaysTotal(tier + "|" + kind.word(), closed[0]);
        }
        if (firstOfTheDay[0]) {
            LOG.warnf("%s %s %d rows — tenant %s, day %s: the cdr rows of the %s kind (%s) are not summed here, %s; their outbox "
                    + "rows are consumed as every other. Said once per tenant, kind and day; the day's total is said when the day "
                    + "turns and at stop", MARK, kind.word(), rows, tier, today, kind.word(), TenantKinds.readsOf(kind), kinds.said());
        }
    }

    private static void sayTheDaysTotal(String key, DayCount count) {
        String[] tierAndKind = key.split("\\|", 2);
        LOG.infof("%s %s %d rows in all — tenant %s, day %s (the day's total; not summed)", MARK, tierAndKind[1], count.rows, tierAndKind[0], count.day);
    }

    private List<KindProbe> decode(byte[] json) {
        try {
            return probeMapper.readValue(json, probeMapper.getTypeFactory().constructCollectionType(List.class, KindProbe.class));
        } catch (IOException e) {
            throw new IllegalArgumentException("an outbox row that is not an array of {Cdr, Chargeables}", e);
        }
    }

    /** One record of the blob as far as its kind goes: the customer leg's group, else the cdr's own. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record KindProbe(ProbeCdr cdr, Chargeable customer, List<Chargeable> chargeables) {
        int serviceGroup() {
            Chargeable leg = CdrBlobEntry.customerLegOf(chargeables, customer);
            if (leg != null) {
                return leg.servicegroup();
            }
            return cdr != null && cdr.serviceGroup() != null ? cdr.serviceGroup() : 0;
        }
    }

    /** The cdr's {@code ServiceGroup}, the only column the kind needs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProbeCdr(Integer serviceGroup) {
    }

    /** The rows of one tenant and kind on one day. */
    private static final class DayCount {
        private final LocalDate day;
        private long rows;

        private DayCount(LocalDate day, long rows) {
            this.day = day;
            this.rows = rows;
        }
    }
}
