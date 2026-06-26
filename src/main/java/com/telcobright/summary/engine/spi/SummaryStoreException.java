package com.telcobright.summary.engine.spi;

/** Wraps a database failure from the store so the batch runner can roll the whole batch back uniformly. */
public class SummaryStoreException extends RuntimeException {

    public SummaryStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
