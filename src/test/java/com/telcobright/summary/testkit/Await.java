package com.telcobright.summary.testkit;

import java.util.function.BooleanSupplier;

/** Waits for what a worker thread does — never a fixed sleep where a condition can be asked. */
public final class Await {

    private Await() {
    }

    /** True as soon as {@code condition} holds; false when {@code millis} passed without it. */
    public static boolean until(BooleanSupplier condition, long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            pause(20);
        }
        return condition.getAsBoolean();
    }

    /** True when {@code condition} stayed FALSE for all of {@code millis} (something must NOT happen). */
    public static boolean never(BooleanSupplier condition, long millis) {
        return !until(condition, millis);
    }

    public static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
