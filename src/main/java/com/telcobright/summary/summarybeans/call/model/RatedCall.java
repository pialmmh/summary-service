package com.telcobright.summary.summarybeans.call.model;

/**
 * The VOICE summary's input record — one rated call: the cdr plus its already-picked CUSTOMER-direction leg
 * (the legacy {@code (cdr, acc_chargeable)} signature). This is the {@code I} of the call category's
 * {@code SummaryGenerator}; the bean assembles it from a blob entry (leg pick + service-group filter) before
 * generation.
 */
public record RatedCall(Cdr cdr, Chargeable customerLeg) {
}
