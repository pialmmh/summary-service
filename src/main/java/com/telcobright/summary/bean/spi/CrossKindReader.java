package com.telcobright.summary.bean.spi;

import java.util.function.IntPredicate;

/**
 * A bean whose input crosses the kinds (SS-0008) — the call kind's chargeable roll-up reads every leg of every record,
 * whatever its service group. The start tells it which service groups the tenant's kinds read
 * ({@code summary.kinds}); a leg of any other group is not summed. Untold — a bean built by a test or an embedder — it
 * reads every group, as before kinds existed.
 */
public interface CrossKindReader {

    /** From now on, sum only the legs whose service group {@code serviceGroups} admits. */
    void readOnly(IntPredicate serviceGroups);
}
