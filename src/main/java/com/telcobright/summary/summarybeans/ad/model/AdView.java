package com.telcobright.summary.summarybeans.ad.model;

import com.telcobright.summary.summarybeans.call.model.Chargeable;

/**
 * The generator's input: ONE ad view as ONE tier recorded it — the tier's {@code cdr} row, the view's facts from
 * its meta data, the customer chargeable billing-core built for it (null when the entry carries none), and the
 * tier itself: the name of the schema whose outbox the row was read from.
 */
public record AdView(AdCallCdr cdr, AdViewFacts facts, Chargeable customerLeg, String tier) {
}
