package com.telcobright.summary.testkit;

import com.telcobright.summary.bean.spi.SqlDialect;
import com.telcobright.summary.runtime.spi.UnitOfWork;
import com.telcobright.summary.runtime.spi.UnitOfWorkFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands out units of work over fake stores and keeps the last one for flag assertions. A unit of work begun
 * WITHOUT a schema runs over this factory's own pair of stores (the connection's own schema); one begun ON a
 * schema runs over that schema's pair ({@link #tier}) — each tenant schema has its own outbox, its own bookmarks
 * and its own summary tables, as in a database.
 */
public final class FakeUnitOfWorkFactory implements UnitOfWorkFactory {

    /** One tenant schema's stores. */
    public record Tier(FakeSummaryStore store, FakeOutboxStore outbox) {
    }

    public final FakeSummaryStore store;
    public final FakeOutboxStore outbox;
    public volatile FakeUnitOfWork last;
    /** The schema the next units of work begun WITHOUT one say they run in ({@code null} = a unit of work that names none). */
    public String schema = FakeUnitOfWork.SCHEMA;
    /** The engine the units of work say they run on. */
    public SqlDialect dialect = SqlDialect.MYSQL;
    private final Map<String, Tier> tiers = new ConcurrentHashMap<>();

    public FakeUnitOfWorkFactory(FakeSummaryStore store, FakeOutboxStore outbox) {
        this.store = store;
        this.outbox = outbox;
    }

    /** A factory with fresh stores of its own. */
    public FakeUnitOfWorkFactory() {
        this(new FakeSummaryStore(), new FakeOutboxStore());
    }

    /** The stores of the tenant schema {@code name}, made the first time it is asked for. */
    public Tier tier(String name) {
        return tiers.computeIfAbsent(name, n -> new Tier(new FakeSummaryStore(), new FakeOutboxStore()));
    }

    /** The schemas a unit of work was begun on, by name. */
    public Set<String> schemasEntered() {
        return Set.copyOf(tiers.keySet());
    }

    /** A named schema is the one the unit of work runs in; {@code null} = this factory's own ({@link #schema}). */
    @Override
    public UnitOfWork begin(String named) {
        if (named == null) {
            last = new FakeUnitOfWork(store, outbox, schema, dialect);
        } else {
            Tier tier = tier(named);
            last = new FakeUnitOfWork(tier.store(), tier.outbox(), named, dialect);
        }
        return last;
    }
}
