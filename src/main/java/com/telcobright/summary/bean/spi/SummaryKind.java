package com.telcobright.summary.bean.spi;

import java.util.Set;

/**
 * A KIND of traffic this service sums — the call, the ad view, the WiFi session; later the SMS — as ONE processor: its
 * bean set and the service groups it reads (the owner's R13 of 2026-10-23, routesphere
 * {@code docs/architecture/routesphere-seed-flow-requirements.md}: "billing and summary get new logic beans close to the
 * current call processing, extended to the new services"; SS-0003 §5 S17: "the kinds as config-activated processors").
 * A tenant switches its kinds on in its profile ({@code summary.kinds: [call, ad, wifi]}); none named = the call alone,
 * which is what the service did before kinds existed.
 *
 * <p>What a kind declares:
 * <ul>
 *   <li>its {@link #word() word} in {@code summary.kinds};</li>
 *   <li>the {@link #serviceGroups() service groups} whose {@code cdr} rows are its. A cdr row's kind is the kind of its
 *       service group; ONE kind — the call — also {@link #takesEveryUnnamedServiceGroup() takes every group no kind
 *       names}, as the voice deployment summed every group before kinds existed;</li>
 *   <li>the outbox {@link #entityTypes() entities} its beans read ({@code cdr}; the WiFi kind also {@code wifi_flow});</li>
 *   <li>its BEAN SET: the {@link #catalogBeans() catalog} window classes (CDI singletons, by name), the
 *       {@link #shapes() shapes} it config-instantiates from {@code summary.beans.<name>.kind} (its fine-tuning), and
 *       the beans it {@link #owns owns}. Each bean carries its own generator, builder and table description
 *       ({@link SummaryBean#buildBatch}, {@link SummaryBean#tableSpec}). A call-shaped bean ({@code sum_voice_*}, a
 *       {@link ServiceGroupReader}) is the kind of the group it reads — the call's code is the model the others reuse:
 *       {@code sum_voice_day_30} is the ad's view of a view as a call, {@code sum_voice_day_101} the WiFi's.</li>
 * </ul>
 *
 * <p>A kind lives in ONE package with its beans ({@code summarybeans/<kind>}, sub-packages for its machinery and model).
 * Adding a kind = a package with its beans and its kind class, plus one line in the list of the known kinds.
 */
public interface SummaryKind {

    /** The kind's word in {@code summary.kinds}: {@code call}, {@code ad}, {@code wifi}. */
    String word();

    /** The service groups whose {@code cdr} rows are this kind's. */
    Set<Integer> serviceGroups();

    /** True for the ONE kind that also takes every service group no kind names (the call: before kinds, every group was summed). */
    default boolean takesEveryUnnamedServiceGroup() {
        return false;
    }

    /** The outbox {@code entity_type}s this kind's beans read. */
    Set<String> entityTypes();

    /** The catalog beans of this kind — window classes discovered by CDI — by their {@link SummaryBean#name() name}. */
    Set<String> catalogBeans();

    /** The words of {@code summary.beans.<name>.kind} this kind config-instantiates (its fine-tuning); none by default. */
    default Set<String> shapes() {
        return Set.of();
    }

    /**
     * The bean of {@code shape} (one of {@link #shapes()}, as the profile wrote it) for {@code window} under {@code name}
     * — its own bookmark, worker and table.
     */
    default SummaryBean<?> shaped(String name, String shape, String window, String context) {
        throw new IllegalArgumentException("kind " + word() + " makes no bean of shape '" + shape + "'");
    }

    /**
     * Is {@code bean} one of this kind's OWN shapes (an ad bean, a WiFi bean, the call's chargeable roll-up)? A call-shaped
     * bean ({@link ServiceGroupReader}) is not decided here: it is the kind of the group it reads.
     */
    boolean owns(SummaryBean<?> bean);
}
