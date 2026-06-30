package com.telcobright.summary.summarybeans.call.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One element of the outbox blob's JSON array: a rated cdr paired with its customer-leg chargeable —
 * {@code {"Cdr": {…}, "Customer": {…}}}. The pair is fed to the {@link CallSummaryBuilder} (the legacy
 * (cdr, acc_chargeable) signature).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CdrBlobEntry(Cdr cdr, Customer customer) {
}
