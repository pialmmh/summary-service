package com.telcobright.summary.summarybeans.wifi;

import java.util.Locale;

/**
 * The KIND of a config-instantiated WiFi bean — the one optional key {@code summary.beans.<name>.kind} (SS-0006 item 4):
 * <ul>
 *   <li>{@code wifi-session} — the WiFi session per window: the {@code cdr} entity, service group 101 and the table
 *       suffix 101 FIXED by the kind (no {@code service-group}, no {@code table-suffix} is read) → {@code sum_wifi_<window>_101};</li>
 *   <li>{@code wifi-dst} — the volume per destination per window: the {@code wifi_flow} entity → {@code sum_wifi_dst_<window>}.</li>
 * </ul>
 * The key ABSENT = a call bean, exactly as before this round. Any other word refuses the bean at the start, in words.
 */
public enum WifiBeanKind {

    SESSION("wifi-session"),
    DESTINATION("wifi-dst");

    private final String word;

    WifiBeanKind(String word) {
        this.word = word;
    }

    /** The profile's word for this kind. */
    public String word() {
        return word;
    }

    /** The kind a profile names for {@code beanName}; a word that is neither is refused in words that name both. */
    public static WifiBeanKind parse(String beanName, String word) {
        String wanted = word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
        for (WifiBeanKind kind : values()) {
            if (kind.word.equals(wanted)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("summary.beans." + beanName + ".kind is '" + word + "' — it must be " + SESSION.word + " or "
                + DESTINATION.word + " (or absent: a call bean, as before)");
    }
}
