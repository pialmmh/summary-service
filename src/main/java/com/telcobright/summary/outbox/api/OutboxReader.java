package com.telcobright.summary.outbox.api;

import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryEntity;
import com.telcobright.summary.bean.spi.SummaryMode;
import com.telcobright.summary.engine.api.SummaryEngine;
import com.telcobright.summary.engine.spi.MissingWindowException;
import com.telcobright.summary.outbox.internal.OutboxCodec;
import com.telcobright.summary.outbox.internal.OutboxInfraDdl;
import com.telcobright.summary.outbox.spi.OutboxRow;
import com.telcobright.summary.runtime.spi.UnitOfWork;
import com.telcobright.summary.runtime.spi.UnitOfWorkFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drains one bean's outbox in ONE transaction per step — the exactly-once core. Each {@link #drainOnce} reads
 * the bean's {@code last_offset}, reads the next outbox rows, builds + merges their entities (the ratified
 * engine), then advances the offset and commits — summaries + offset together. Any failure rolls the whole step
 * back, so the offset never moves past un-written summaries (crash → reprocessed clean, no double-count).
 *
 * <p>Every operation is done IN a tenant schema (brief S6: one process serves every schema of its root's tree —
 * a worker and a bookmark per (schema, bean)). The schema is named per call; {@code null}, and the forms without
 * one, mean the connection's own schema (the one-tenant deployments, as before).
 *
 * <p>POISON rows (a blob that fails to decode/build — deterministic on the data, unlike a transient SQL error)
 * would otherwise wedge the bean forever AND block the reaper for the whole entity. After
 * {@code quarantine-after} consecutive failures on the same head row, the row is copied to
 * {@code summary_affected_dlq} (per bean) and the offset advances past it — in one transaction, loudly logged.
 * SQL-layer failures are NEVER quarantined: they may be transient (or a config fault), and retrying loses nothing.
 */
@ApplicationScoped
public class OutboxReader {

    private static final Logger LOG = Logger.getLogger(OutboxReader.class);

    private final UnitOfWorkFactory unitOfWorkFactory;
    private final SummaryEngine engine;
    private final int segmentSize;
    private final int maxRowsPerTx;
    private final int quarantineAfter;
    /** (schema, bean) → {failing head row id, consecutive decode/build failures on it}. In-memory by design. */
    private final Map<String, long[]> poisonStreaks = new ConcurrentHashMap<>();
    /** The schemas whose infra tables this process has ensured. */
    private final Set<String> infraEnsured = ConcurrentHashMap.newKeySet();
    /** The schemas where billing-core's outbox has been seen (it is never dropped, so it is asked only until then). */
    private final Set<String> outboxSeen = ConcurrentHashMap.newKeySet();
    /** The schemas already said to be waiting for their outbox (said once, not at every poll). */
    private final Set<String> waitingSaid = ConcurrentHashMap.newKeySet();

    @Inject
    public OutboxReader(UnitOfWorkFactory unitOfWorkFactory, SummaryEngine engine,
                        @ConfigProperty(name = "summary.outbox.segment-size", defaultValue = "1000") int segmentSize,
                        @ConfigProperty(name = "summary.outbox.max-rows-per-tx", defaultValue = "1") int maxRowsPerTx,
                        @ConfigProperty(name = "summary.outbox.quarantine-after", defaultValue = "8") int quarantineAfter) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.engine = engine;
        this.segmentSize = segmentSize;
        this.maxRowsPerTx = maxRowsPerTx;
        this.quarantineAfter = quarantineAfter;
    }

    /** Drain until this bean is caught up in the connection's own schema; returns the total outbox rows processed. */
    public <T extends SummaryEntity<T>> int drain(SummaryBean<T> bean) {
        return drain(null, bean);
    }

    /** Drain until this bean is caught up in {@code schema}; returns the total outbox rows processed. */
    public <T extends SummaryEntity<T>> int drain(String schema, SummaryBean<T> bean) {
        int total = 0;
        int processed;
        while ((processed = drainOnce(schema, bean)) > 0) {
            total += processed;
        }
        return total;
    }

    /** Ensure the infra tables in the connection's own schema. */
    public void ensureInfraTables() {
        ensureInfraTables(null);
    }

    /**
     * Ensure the service's own infra tables exist in {@code schema} ({@code summary_offset},
     * {@code summary_affected_dlq}; on MySQL also the dev copy of {@code summary_affected}) — once per schema per
     * process, before the schema's first worker starts.
     */
    public void ensureInfraTables(String schema) {
        if (!infraEnsured.add(key(schema))) {
            return;
        }
        UnitOfWork unitOfWork = null;
        try {
            unitOfWork = unitOfWorkFactory.begin(schema);
            for (String ddl : OutboxInfraDdl.createStatements(unitOfWork.dialect())) {
                unitOfWork.store().executeNonQuery(ddl);   // MySQL commits each by itself; PostgreSQL with the commit below
            }
            unitOfWork.commit();
        } catch (RuntimeException failure) {
            infraEnsured.remove(key(schema));   // retry on the next start attempt
            LOG.errorf(failure, "infra table provisioning failed in schema %s", said(schema));
            throw failure;
        } finally {
            if (unitOfWork != null) {
                closeQuietly(unitOfWork);
            }
        }
    }

    /**
     * Self-provision the bean's target table (user directive 2026-07-02): render its description for the engine
     * of the store and run it — {@code CREATE TABLE IF NOT EXISTS} — before its first drain. A no-op when the
     * table already exists (the pre-provisioned prod sets) or the bean describes no table.
     */
    public void ensureProvisioned(SummaryBean<?> bean) {
        ensureProvisioned(null, bean);
    }

    /** The same in {@code schema}: a tenant schema's tables are made the first time it is served. */
    public void ensureProvisioned(String schema, SummaryBean<?> bean) {
        if (bean.tableSpec() == null) {
            return;
        }
        UnitOfWork unitOfWork = unitOfWorkFactory.begin(schema);
        try {
            // MySQL: ONE statement, the full partition set inside it. PostgreSQL: the table and its indexes, made
            // in this ONE transaction — the table never exists without them.
            for (String ddl : bean.tableDdl(unitOfWork.dialect())) {
                unitOfWork.store().executeNonQuery(ddl);
            }
            unitOfWork.commit();
            LOG.infof("schema=%s bean=%s table %s ensured (CREATE IF NOT EXISTS)", unitOfWork.schema(), bean.name(), bean.table());
        } catch (RuntimeException failure) {
            rollbackQuietly(unitOfWork, bean, failure);
            throw failure;
        } finally {
            closeQuietly(unitOfWork);
        }
    }

    /**
     * Seed the bean's offset at the outbox HEAD if it has no bookmark yet (its own small transaction). The rule
     * for a bean switched on LATER, on a schema already served: it must summarise from NOW, not from the arbitrary
     * residue the reaper happens not to have deleted (a partial backfill would look like complete windows).
     * {@link #seedBookmarks} decides between this and offset 0; this is the head-init alone.
     */
    public void initOffsetAtHead(SummaryBean<?> bean) {
        UnitOfWork unitOfWork = unitOfWorkFactory.begin();
        try {
            unitOfWork.outbox().initOffsetAtHead(bean.entityType(), bean.name());
            unitOfWork.commit();
        } catch (RuntimeException failure) {
            rollbackQuietly(unitOfWork, bean, failure);
            throw failure;
        } finally {
            closeQuietly(unitOfWork);
        }
    }

    /**
     * Give every bean of {@code schema} that has no bookmark its first one — decided ONCE for the schema, for all
     * its beans, in ONE transaction, before any of its workers starts (brief S7):
     * <ul>
     *   <li>the schema has NO bookmark at all for the entity — it is seen for the first time: every bean starts
     *       at <b>0</b>. billing-core may have written before this service first served the schema; those rows
     *       must be summed, not skipped;</li>
     *   <li>the schema has bookmarks — it was served before: a bean WITHOUT one was switched on later and starts
     *       at the outbox <b>head</b> ("sums from now").</li>
     * </ul>
     * Deciding per bean would let the first bean's bookmark make the schema look served to the second one. A
     * bookmark that exists is never moved. Returns the beans that were given a bookmark, with it.
     */
    public Map<String, Long> seedBookmarks(String schema, Collection<? extends SummaryBean<?>> beans) {
        UnitOfWork unitOfWork = unitOfWorkFactory.begin(schema);
        try {
            Map<String, Long> seeded = new LinkedHashMap<>();
            Map<String, Set<String>> bookmarkedByEntity = new HashMap<>();
            boolean outbox = unitOfWork.outbox().outboxExists();
            for (SummaryBean<?> bean : beans) {
                Set<String> bookmarked = bookmarkedByEntity.computeIfAbsent(bean.entityType(), unitOfWork.outbox()::bookmarkedBeans);
                if (bookmarked.contains(bean.name())) {
                    continue;
                }
                if (bookmarked.isEmpty() || !outbox) {
                    // a schema seen for the first time (or one with no outbox yet: its head IS 0)
                    unitOfWork.outbox().seedOffsetIfAbsent(bean.entityType(), bean.name(), 0L);
                } else {
                    unitOfWork.outbox().initOffsetAtHead(bean.entityType(), bean.name());
                }
                seeded.put(bean.name(), unitOfWork.outbox().readOffset(bean.entityType(), bean.name()));
            }
            unitOfWork.commit();
            if (!seeded.isEmpty()) {
                LOG.infof("schema=%s first bookmarks: %s", unitOfWork.schema(), seeded);
            }
            return seeded;
        } catch (RuntimeException failure) {
            try {
                unitOfWork.rollback();
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            closeQuietly(unitOfWork);
        }
    }

    /** The name of the connection's own schema (a one-tenant deployment's tier). Opens and closes one connection. */
    public String ownSchema() {
        UnitOfWork unitOfWork = unitOfWorkFactory.begin();
        try {
            return unitOfWork.schema();
        } finally {
            closeQuietly(unitOfWork);
        }
    }

    /**
     * Trim the outbox of {@code schema}: delete the rows every one of {@code beanNames} has passed — its own
     * transaction. Nothing is deleted while a bean has no bookmark, and nothing in a schema with no outbox yet.
     */
    public int reap(String schema, String entityType, Collection<String> beanNames) {
        if (beanNames.isEmpty()) {
            return 0;
        }
        UnitOfWork unitOfWork = unitOfWorkFactory.begin(schema);
        try {
            int deleted = 0;
            long min = 0;
            if (hasOutbox(schema, unitOfWork)) {
                min = unitOfWork.outbox().minOffset(entityType, beanNames);
                deleted = min > 0 ? unitOfWork.outbox().deleteUpTo(entityType, min) : 0;
            }
            unitOfWork.commit();
            if (deleted > 0) {
                LOG.infof("schema=%s reaper deleted %d outbox rows (entity=%s id<=%d)", unitOfWork.schema(), deleted, entityType, min);
            }
            return deleted;
        } catch (RuntimeException failure) {
            try {
                unitOfWork.rollback();
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            closeQuietly(unitOfWork);
        }
    }

    /**
     * One transaction: read offset → per row IN ID ORDER: decode+build, then load-merge-write with the ROW's
     * {@code op} ({@code add}/{@code subtract}) → advance offset → commit. One outbox row = one packed billing
     * batch, so the load-windows-once invariant holds at exactly the legacy batch granularity; a later row in
     * the same tx re-reads windows an earlier row just wrote (same connection sees its own writes), which is
     * what makes a subtract row directly behind its add row correct. Returns the rows consumed (0 = caught up).
     */
    public <T extends SummaryEntity<T>> int drainOnce(SummaryBean<T> bean) {
        return drainOnce(null, bean);
    }

    /** The same step in {@code schema}: its outbox, its bookmark, its summary tables — one transaction there. */
    public <T extends SummaryEntity<T>> int drainOnce(String schema, SummaryBean<T> bean) {
        UnitOfWork unitOfWork = unitOfWorkFactory.begin(schema);
        try {
            String tier = tierOf(unitOfWork);
            if (!hasOutbox(schema, unitOfWork)) {
                unitOfWork.commit();
                return 0;                                 // billing-core has not served this schema yet: nothing to drain
            }
            long offset = unitOfWork.outbox().readOffset(bean.entityType(), bean.name());
            List<OutboxRow> rows = unitOfWork.outbox().readAfter(bean.entityType(), offset, maxRowsPerTx);
            if (rows.isEmpty()) {
                unitOfWork.commit();
                return 0;
            }

            // row by row so ONE poison row doesn't discard its clean neighbours
            int consumed = 0;
            RuntimeException poison = null;
            OutboxRow poisonRow = null;
            for (OutboxRow row : rows) {
                List<T> entities;
                try {
                    entities = bean.buildBatch(OutboxCodec.decode(row.data()), tier);
                } catch (RuntimeException decodeOrBuildFailure) {   // deterministic on the row's data -> poison
                    poison = decodeOrBuildFailure;
                    poisonRow = row;
                    break;
                }
                try {
                    if (bean.mode() == SummaryMode.REPLACE) {
                        // prototype path: throws until replace is implemented — a misconfigured bean fails
                        // LOUDLY (never quarantined: it is a config fault, not a data fault)
                        engine.replaceWindows(bean, entities, unitOfWork.store(), segmentSize);
                    } else {
                        engine.runBatch(bean, entities, row.mergeMode(), unitOfWork.store(), segmentSize);
                    }
                } catch (MissingWindowException subtractOnMissing) { // ruling A1: quarantinable, not a wedge
                    poison = subtractOnMissing;                      // (thrown pre-flush -> this row wrote nothing)
                    poisonRow = row;
                    break;
                }
                consumed++;
            }
            if (consumed == 0 && poison != null) {
                return quarantineOrRethrow(schema, bean, poisonRow, poison, unitOfWork);
            }

            long newOffset = rows.get(consumed - 1).id();
            unitOfWork.outbox().advanceOffset(bean.entityType(), bean.name(), newOffset);
            unitOfWork.commit();
            poisonStreaks.remove(streakKey(schema, bean));
            if (poison != null) {
                LOG.warnf(poison, "schema=%s bean=%s committed %d clean row(s); a poison row (id=%d) is now at the head",
                        tier, bean.name(), consumed, poisonRow.id());
            } else if (LOG.isDebugEnabled()) {
                LOG.debugf("schema=%s bean=%s drained rows=%d -> offset=%d", tier, bean.name(), consumed, newOffset);
            }
            return consumed;
        } catch (RuntimeException failure) {
            rollbackQuietly(unitOfWork, bean, failure);
            throw failure;
        } finally {
            closeQuietly(unitOfWork);
        }
    }

    /**
     * The tier a drain builds for = the schema its unit of work runs in. A unit of work that names none is a
     * WIRING fault: it fails the drain here, before any row is read — so it can never be taken for a poison
     * row and dead-letter data.
     */
    private static String tierOf(UnitOfWork unitOfWork) {
        String tier = unitOfWork.schema();
        if (tier == null || tier.isBlank()) {
            throw new IllegalStateException("the unit of work names no schema — a drain must know the tier it serves");
        }
        return tier;
    }

    /** The failing row IS the head: quarantine it once the streak reaches the threshold, else rethrow. */
    private int quarantineOrRethrow(String schema, SummaryBean<?> bean, OutboxRow row, RuntimeException poison, UnitOfWork unitOfWork) {
        if (nextPoisonStreak(streakKey(schema, bean), row.id()) < quarantineAfter) {
            throw poison;   // rolled back by the caller; the worker's backoff paces the retries
        }
        unitOfWork.outbox().deadLetter(bean.entityType(), bean.name(), row, summarize(poison));
        unitOfWork.outbox().advanceOffset(bean.entityType(), bean.name(), row.id());
        unitOfWork.commit();
        poisonStreaks.remove(streakKey(schema, bean));
        LOG.errorf(poison, "schema=%s bean=%s QUARANTINED poison outbox row id=%d after %d attempts — copied to "
                + "summary_affected_dlq; that row's records are NOT summarised for this bean (repair via correction)",
                unitOfWork.schema(), bean.name(), row.id(), quarantineAfter);
        return 1;
    }

    private int nextPoisonStreak(String streakKey, long rowId) {
        long[] streak = poisonStreaks.compute(streakKey,
                (k, prev) -> prev == null || prev[0] != rowId ? new long[]{rowId, 1} : new long[]{rowId, prev[1] + 1});
        return (int) streak[1];
    }

    /**
     * Has billing-core made its outbox in this schema? Asked of the catalog until it is seen once (the table is
     * never dropped). In a tenant schema ONLY billing-core makes the CDR road's tables — on PostgreSQL the role
     * that makes a table owns it, and an outbox made here could be neither written nor granted by billing-core
     * (its BC-0004 F3). So a schema without it is WAITING: said ONCE in a WARN that names the schema and the table
     * — not at every poll, not by every bean — never an error, and never a reason to make the table. It is looked
     * at again on the worker's normal cycle and picked up the moment it is there, with no restart.
     */
    private boolean hasOutbox(String schema, UnitOfWork unitOfWork) {
        String key = key(schema);
        if (outboxSeen.contains(key)) {
            return true;
        }
        if (unitOfWork.outbox().outboxExists()) {
            outboxSeen.add(key);
            if (waitingSaid.remove(key)) {
                LOG.infof("schema=%s the table %s is there now (billing-core made it): draining, no restart needed",
                        unitOfWork.schema(), OutboxInfraDdl.OUTBOX_TABLE);
            }
            return true;
        }
        if (waitingSaid.add(key)) {
            LOG.warnf("schema=%s has no table %s yet — billing-core makes it with its first batch there, and only billing-core may. "
                    + "WAITING for it: nothing is drained and nothing is made in its place; looked at again every poll",
                    unitOfWork.schema(), OutboxInfraDdl.OUTBOX_TABLE);
        }
        return false;
    }

    private static String key(String schema) {
        return schema == null ? "" : schema;
    }

    private static String said(String schema) {
        return schema == null ? "(the connection's own)" : schema;
    }

    private static String streakKey(String schema, SummaryBean<?> bean) {
        return key(schema) + "|" + bean.name();
    }

    private static String summarize(Throwable poison) {
        String text = poison.toString();
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private void rollbackQuietly(UnitOfWork unitOfWork, SummaryBean<?> bean, RuntimeException failure) {
        LOG.warnf(failure, "bean=%s drain rolled back; offset unchanged (will retry)", bean.name());
        try {
            unitOfWork.rollback();
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private void closeQuietly(UnitOfWork unitOfWork) {
        try {
            unitOfWork.close();
        } catch (RuntimeException closeFailure) {
            LOG.warn("unit of work close failed", closeFailure);
        }
    }
}
