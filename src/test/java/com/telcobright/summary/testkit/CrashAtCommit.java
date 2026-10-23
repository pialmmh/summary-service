package com.telcobright.summary.testkit;

import com.telcobright.summary.bean.spi.SqlDialect;
import com.telcobright.summary.engine.spi.SummaryStore;
import com.telcobright.summary.engine.spi.SummaryStoreException;
import com.telcobright.summary.outbox.spi.OutboxStore;
import com.telcobright.summary.runtime.spi.UnitOfWork;
import com.telcobright.summary.runtime.spi.UnitOfWorkFactory;

/**
 * A REAL unit of work that dies where a crash hurts most: after the summaries and the bookmark were written,
 * before the commit. At the chosen commit the connection is closed without one — what the database sees when the
 * process is killed or the link is lost — and the drain gets the failure. Every other commit goes through.
 */
public final class CrashAtCommit implements UnitOfWorkFactory {

    private final UnitOfWorkFactory real;
    private int commitsUntilCrash;
    public int crashes;

    /** Crash at the {@code nth} commit that follows (1 = the next one). */
    public CrashAtCommit(UnitOfWorkFactory real, int nth) {
        this.real = real;
        this.commitsUntilCrash = nth;
    }

    @Override
    public UnitOfWork begin(String schema) {
        UnitOfWork work = real.begin(schema);
        return new UnitOfWork() {
            @Override
            public String schema() {
                return work.schema();
            }

            @Override
            public SqlDialect dialect() {
                return work.dialect();
            }

            @Override
            public SummaryStore store() {
                return work.store();
            }

            @Override
            public OutboxStore outbox() {
                return work.outbox();
            }

            @Override
            public void commit() {
                if (--commitsUntilCrash == 0) {
                    crashes++;
                    work.close();                                   // the connection is gone; nothing was committed
                    throw new SummaryStoreException("the process died before the commit (test)", null);
                }
                work.commit();
            }

            @Override
            public void rollback() {
                work.rollback();
            }

            @Override
            public void close() {
                work.close();
            }
        };
    }
}
