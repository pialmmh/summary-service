package com.telcobright.summary.bean.spi;

/**
 * A bean that keeps the {@code cdr} rows of ONE service group — the call-shaped bean ({@code sum_voice_<window>_<suffix>},
 * its group from the profile). Its {@link SummaryKind kind} is the kind of that group: the call's for 10 and 11, the ad's
 * for 30, the WiFi's for 101.
 */
public interface ServiceGroupReader {

    /** The service group whose records this bean keeps. */
    int serviceGroup();
}
