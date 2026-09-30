package com.telcobright.summary.summarybeans.ad.model;

/**
 * The generator's input: ONE tier of ONE ad call — the call's facts with the tier's leg, or with no leg for a
 * call nothing admitted (the failed row on the entry tenant).
 */
public record AdTier(AdCdr cdr, AdLeg leg) {
}
