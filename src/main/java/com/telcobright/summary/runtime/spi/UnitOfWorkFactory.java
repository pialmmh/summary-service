package com.telcobright.summary.runtime.spi;

/** Begins a fresh {@link UnitOfWork} (a new transaction-bound connection) for each batch. */
public interface UnitOfWorkFactory {

    UnitOfWork begin();
}
