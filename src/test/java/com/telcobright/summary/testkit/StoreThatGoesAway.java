package com.telcobright.summary.testkit;

import com.telcobright.summary.engine.spi.SummaryStoreException;
import com.telcobright.summary.runtime.spi.UnitOfWork;
import com.telcobright.summary.runtime.spi.UnitOfWorkFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A store that goes AWAY for a while and comes back (brief S16): while it is away every unit of work fails to begin,
 * as a pool fails when its database is down; when it is back, the fakes behind it answer again. Counts the tries.
 */
public final class StoreThatGoesAway implements UnitOfWorkFactory {

    private final FakeUnitOfWorkFactory database;
    private volatile boolean away;
    private final AtomicInteger triedWhileAway = new AtomicInteger();

    public StoreThatGoesAway(FakeUnitOfWorkFactory database) {
        this.database = database;
    }

    public void goAway() {
        away = true;
    }

    public void comeBack() {
        away = false;
    }

    /** How many units of work were tried while the store was away. */
    public int triedWhileAway() {
        return triedWhileAway.get();
    }

    @Override
    public UnitOfWork begin(String schema) {
        if (away) {
            triedWhileAway.incrementAndGet();
            throw new SummaryStoreException("could not begin summary unit of work (the store does not answer: test)",
                    new java.sql.SQLException("Connection refused", "08001"));
        }
        return database.begin(schema);
    }
}
