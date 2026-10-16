package com.telcobright.summary.testkit;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.engine.internal.SummaryCache;
import com.telcobright.summary.engine.spi.MergeMode;
import com.telcobright.summary.summarybeans.wifi.WifiSummaries;
import com.telcobright.summary.summarybeans.wifi.model.WifiSessionSummary;
import com.telcobright.summary.testkit.WifiSessionTestSupport.Session;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * The WiFi BEANS' bed (SS-0006): the two session beans as the profiles name them, with no CDI, a {@link #rollup} that
 * runs the real build → bucket → merge path, and {@link #knownHour}: the replayed hour the proofs count by hand.
 *
 * <p>The known hour (2026-10-16 09:xx, the tenant's wall clock) — six records of BC-0008's shape
 * ({@link WifiSessionTestSupport#measuredSession}):
 * <ul>
 *   <li>A 09:05 — mirpur-10 / dhaka-north / paid 2 / wifi-30 / TF_min 30 / NORMAL_CLEARING: 1 800 s, 1 234 567 up, 12 345 678 down;</li>
 *   <li>B 09:20 — the same key, 10 TF_min: 600 s, 1 000 up, 2 000 down;</li>
 *   <li>E 09:40 — the same key, 5 TF_min, {@code ValidFlag} 0 (unmeasured: zero bytes): 300 s, 0 up, 0 down — STILL summed (rule b);</li>
 *   <li>C 09:30 — the same place, paid in MONEY (3.00 BDT), MEDIA_TIMEOUT: 900 s, 500 up, 7 000 down — its own row (BDT, idle);</li>
 *   <li>D 09:50 — the same place, 0 seconds (counted, not costed, no unit): its own row ('' , other);</li>
 *   <li>F 09:55 — uttara-7 / free 1 / free-15 / 0 TF_min / VOLUME_EXHAUSTED: 900 s, 100 up, 200 down — its own row.</li>
 * </ul>
 * So the hour (and the day) holds FOUR rows: the paid TF_min row of mirpur-10 = sessions 3, connected 3, total 2 700,
 * avg 900, bytes 1 235 567 / 12 347 678, cost 45; the BDT row = 1, 1, 900, 900, 500 / 7 000, 3.00; the no-unit row
 * = 1, 0, 0, 0, 0 / 0, 0; the free row of uttara-7 = 1, 1, 900, 900, 100 / 200, 0.
 */
public final class WifiBeansTestSupport {

    public static final String DAILY_SESSION_BEAN = "dailyWifiSessionSummary";
    public static final String HOURLY_SESSION_BEAN = "hourlyWifiSessionSummary";
    public static final String SESSION_DAY_TABLE = "sum_wifi_day_101";
    public static final String SESSION_HOUR_TABLE = "sum_wifi_hr_101";

    public static final LocalDateTime THE_HOUR = LocalDateTime.of(2026, 10, 16, 9, 0);

    private WifiBeansTestSupport() {
    }

    public static SummaryBean<WifiSessionSummary> dailySession() {
        return WifiSummaries.sessionForWindow(DAILY_SESSION_BEAN, "daily", null);
    }

    public static SummaryBean<WifiSessionSummary> hourlySession() {
        return WifiSummaries.sessionForWindow(HOURLY_SESSION_BEAN, "hourly", null);
    }

    // ---- the known hour ----

    public static Session a() {
        return WifiSessionTestSupport.measuredSession(THE_HOUR.plusMinutes(5));
    }

    public static Session b() {
        return WifiSessionTestSupport.measuredSession(THE_HOUR.plusMinutes(20)).seconds("600").minutes("10").bytes(1_000, 2_000);
    }

    /** {@code ValidFlag} 0: the unmeasured fault — zero bytes while the plane counted traffic (A-0099). Summed all the same. */
    public static Session e() {
        return WifiSessionTestSupport.measuredSession(THE_HOUR.plusMinutes(40)).seconds("300").minutes("5").bytes(0, 0).measured(0);
    }

    public static Session c() {
        return WifiSessionTestSupport.measuredSession(THE_HOUR.plusMinutes(30)).paidInMoney("3.00").seconds("900").bytes(500, 7_000).cause("MEDIA_TIMEOUT");
    }

    public static Session d() {
        return WifiSessionTestSupport.measuredSession(THE_HOUR.plusMinutes(50)).ofZeroSeconds().bytes(0, 0);
    }

    public static Session f() {
        return WifiSessionTestSupport.measuredSession(THE_HOUR.plusMinutes(55)).site("uttara-7").grantTier("free", 1).packageCode("free-15")
                .minutes("0").seconds("900").bytes(100, 200).cause("VOLUME_EXHAUSTED");
    }

    /** The six records of the known hour, in the order they ended. */
    public static List<Session> knownHour() {
        return List.of(a(), b(), c(), e(), d(), f());
    }

    // ---- the rollup ----

    /** Build {@code sessions} through {@code bean}, fold them into a cache, and return the window rows. */
    public static Collection<WifiSessionSummary> rollup(SummaryBean<WifiSessionSummary> bean, List<Session> sessions) {
        SummaryCache<WifiSessionSummary> cache = new SummaryCache<>(bean.table(), WifiSessionSummary.INSERT_COLUMNS, WifiSessionSummary.BUCKET_COLUMN);
        for (WifiSessionSummary built : bean.buildBatch(WifiSessionTestSupport.batchOf(sessions.toArray(Session[]::new)))) {
            cache.merge(built, MergeMode.ADD);
        }
        return cache.rows();
    }

    /** The one row of {@code rows} at {@code site} in {@code unit} with {@code family}. */
    public static WifiSessionSummary row(Collection<WifiSessionSummary> rows, String site, String unit, String family) {
        List<WifiSessionSummary> found = rows.stream()
                .filter(r -> r.tup_site.equals(site) && r.tup_customercurrency.equals(unit) && r.tup_causefamily.equals(family)).toList();
        if (found.size() != 1) {
            throw new AssertionError(site + " / " + unit + " / " + family + " has " + found.size() + " row(s), not one: " + rows.size() + " row(s) in all");
        }
        return found.get(0);
    }

    /** {@code sessions | connected | total | avg | up | dn | cost} of a row — the shape the proofs compare. */
    public static String sums(WifiSessionSummary r) {
        return r.sessions + " | " + r.sessions_connected + " | " + r.total_duration_sec + " | " + r.avg_session_sec.toPlainString()
                + " | " + r.bytes_up + " | " + r.bytes_dn + " | " + r.customercost.toPlainString();
    }
}
