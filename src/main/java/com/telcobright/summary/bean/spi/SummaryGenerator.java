package com.telcobright.summary.bean.spi;

import java.util.ArrayList;
import java.util.List;

/**
 * The generic, input-typed summary-generation contract — the reusable core for ANY summary kind, present and
 * future (user directive 2026-07-03): {@code I} is the INPUT record type (a rated call, a chargeable leg, an
 * SMS, a packet-flow record …), {@code T} the summary entity it rolls into. The base holds the METHOD
 * PROTOTYPES and the shared batch loop; implementations supply only the per-input generation logic.
 *
 * <p>The reusable pieces an implementation inherits for free:
 * <ul>
 *   <li><b>generation</b> — {@link #generate} maps every input through {@link #generateOne} (the one
 *       prototype an implementation writes);</li>
 *   <li><b>append</b> (add / subtract) — carried by the entity contract ({@link SummaryEntity#merge} /
 *       {@link SummaryEntity#multiply}) and applied by the engine's INCREMENTAL api
 *       ({@code SummaryEngine.runBatch} with {@code MergeMode.ADD}/{@code SUBTRACT}): generate a delta per
 *       input and amend the window;</li>
 *   <li><b>replace</b> — the engine's REPLACE api ({@code SummaryEngine.replaceWindows}): the caller supplies
 *       ALL inputs of the window(s), generated entities drop-and-recreate those windows wholesale (naturally
 *       idempotent — the correction path).</li>
 * </ul>
 *
 * @param <I> the input record type (e.g. {@code RatedCall}, {@code Chargeable})
 * @param <T> the summary entity the inputs generate
 */
public abstract class SummaryGenerator<I, T extends SummaryEntity<T>> {

    /** PROTOTYPE — one input becomes one summary delta, bucketed to {@code window}. Implementations differ here. */
    protected abstract T generateOne(I input, WindowSize window);

    /** Shared batch loop: one delta per input, in input order (order matters when the engine appends them). */
    public final List<T> generate(List<I> inputs, WindowSize window) {
        List<T> out = new ArrayList<>(inputs.size());
        for (I input : inputs) {
            out.add(generateOne(input, window));
        }
        return out;
    }
}
