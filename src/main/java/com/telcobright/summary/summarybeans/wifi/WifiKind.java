package com.telcobright.summary.summarybeans.wifi;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryKind;
import com.telcobright.summary.summarybeans.wifi.internal.WifiDestinationSummaryBean;
import com.telcobright.summary.summarybeans.wifi.internal.WifiSessionSummaryBean;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The <b>wifi</b> kind — the domestic WiFi session is a Call of service group 101 (ARCH-0077-S, SS-0005/0006/0007; SS-0008).
 * Its word is {@code wifi}; its service group 101; its entities {@code cdr} (the session) and {@code wifi_flow} (the gateway's
 * flow windows billing-core ingests).
 *
 * <p>Its bean set, all in this package, config-instantiated by their SHAPE — the one key {@code summary.beans.<name>.kind}, the
 * kind's fine-tuning ({@link WifiBeanKind}, {@link WifiSummaries}); there is no catalog class:
 * <ul>
 *   <li>{@code wifi-session} — the session per window, {@code sum_wifi_<window>_101}: {@code internal/WifiSessionSummaryBean},
 *       generator {@code WifiSessionSummaryGenerator}, builder {@code WifiSessionSummaryBuilder}, table {@code SumWifiSessionDdl};</li>
 *   <li>{@code wifi-dst} — the volume per destination, {@code sum_wifi_dst_<window>} (entity {@code wifi_flow}):
 *       {@code internal/WifiDestinationSummaryBean}, generator {@code WifiDestinationSummaryGenerator}, builder
 *       {@code WifiDestinationSummaryBuilder}, table {@code SumWifiDestinationDdl};</li>
 *   <li>the session as a call {@code sum_voice_<window>_101}: a call-shaped bean of group 101, the call kind's code — this
 *       kind's because it reads this kind's group.</li>
 * </ul>
 */
public final class WifiKind implements SummaryKind {

    public static final String WORD = "wifi";

    private static final Set<String> SHAPES = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.stream(WifiBeanKind.values()).map(WifiBeanKind::word).toList()));

    @Override
    public String word() {
        return WORD;
    }

    @Override
    public Set<Integer> serviceGroups() {
        return Set.of(WifiSessionSummaryBean.SERVICE_GROUP);
    }

    @Override
    public Set<String> entityTypes() {
        return Set.of(WifiSessionSummaryBean.ENTITY_TYPE, WifiDestinationSummaryBean.ENTITY_TYPE);
    }

    @Override
    public Set<String> catalogBeans() {
        return Set.of();
    }

    /** {@code wifi-session}, {@code wifi-dst} — in this order. */
    @Override
    public Set<String> shapes() {
        return SHAPES;
    }

    @Override
    public SummaryBean<?> shaped(String name, String shape, String window, String context) {
        return WifiSummaries.forWindow(name, WifiBeanKind.parse(name, shape), window, context);
    }

    @Override
    public boolean owns(SummaryBean<?> bean) {
        return bean instanceof WifiSessionSummaryBean || bean instanceof WifiDestinationSummaryBean;
    }
}
