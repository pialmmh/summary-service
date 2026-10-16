package com.telcobright.summary.registry.internal;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.summarybeans.call.CallSummaries;
import com.telcobright.summary.summarybeans.wifi.WifiBeanKind;
import com.telcobright.summary.summarybeans.wifi.WifiSummaries;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

/**
 * The CONFIG-INSTANTIATED path of the bootstrap (§12g; SS-0006 item 4): an enabled name with no catalog class but a
 * {@code summary.beans.<name>.window} key becomes an EXTRA bean instance under its own name — its own worker, offset
 * and table. WHICH bean is the one optional key {@code summary.beans.<name>.kind}:
 * <ul>
 *   <li>absent — a CALL bean, exactly as before this round ({@code service-group} required, {@code table-suffix},
 *       {@code context}; how a second service group — legacy covered SG10 AND SG11 — gets its own worker without a
 *       per-SG class);</li>
 *   <li>{@code wifi-session} | {@code wifi-dst} — a WiFi bean ({@link WifiSummaries}); {@code wifi-session} fixes the
 *       service group 101 and the table suffix 101 itself, {@code wifi-dst} has neither;</li>
 *   <li>any other word — refused, in words that name the two kinds ({@link WifiBeanKind#parse}); the bootstrap says it
 *       and activates no bean under that name.</li>
 * </ul>
 */
public final class ConfigInstantiatedBeans {

    private static final Logger LOG = Logger.getLogger(ConfigInstantiatedBeans.class);

    private ConfigInstantiatedBeans() {
    }

    /** The bean the profile describes under {@code name}, or null when it describes none (no {@code window} key, or a call bean with no group). */
    public static SummaryBean<?> from(Config config, String name) {
        String window = key(config, name, "window");
        if (window == null) {
            return null;
        }
        String kind = key(config, name, "kind");
        String context = key(config, name, "context");
        if (kind != null) {
            return WifiSummaries.forWindow(name, WifiBeanKind.parse(name, kind), window, context);
        }
        return callBean(config, name, window, context);
    }

    /** The call bean of before: its service group is required, its table suffix names the pre-provisioned set. */
    private static SummaryBean<?> callBean(Config config, String name, String window, String context) {
        Integer serviceGroup = config.getOptionalValue("summary.beans." + name + ".service-group", Integer.class).orElse(null);
        if (serviceGroup == null) {
            LOG.errorf("config-instantiated bean '%s' needs summary.beans.%s.service-group", name, name);
            return null;
        }
        return CallSummaries.forWindow(name, window, key(config, name, "table-suffix"), serviceGroup, context);
    }

    private static String key(Config config, String name, String key) {
        return config.getOptionalValue("summary.beans." + name + "." + key, String.class).orElse(null);
    }
}
