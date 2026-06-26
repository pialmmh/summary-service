package com.telcobright.summary.bean.spi;

/**
 * A summary row as a real typed entity — the port of legacy {@code ISummary<TEntity,TKey>} + {@code ICacheble}.
 * The generic axis of the engine is THIS entity {@code T} (e.g. {@code CdrSummary}), not the event: {@code T}
 * owns its key, its merge math, its negate, its clone, and the SQL fragments it writes. "Support any entity"
 * (a future {@code CallQuality}) = a new class on this same interface.
 *
 * <p>{@code TKey} is fixed to {@link SummaryKey} (a canonical token tuple) so the engine is generic over only
 * the entity. Id is {@code null} for a row built this batch (an INSERT — MySQL AUTO_INCREMENT assigns it) and
 * set for a row loaded from the DB (an UPDATE/DELETE targets it by id).
 *
 * @param <T> the concrete entity type (self-type)
 */
public interface SummaryEntity<T extends SummaryEntity<T>> {

    Long id();

    void setId(Long id);

    /** The in-memory dedup/merge key (dimensions + window bucket), as canonical tokens. */
    SummaryKey tupleKey();

    /** ADD: {@code this.counter += other.counter} for every counter. */
    void merge(T other);

    /** Scale every counter by {@code factor} (e.g. -1 to negate for the SUBTRACT path). */
    void multiply(int factor);

    /** A field-for-field copy with a fresh (null) id, so merging events into the cached copy never mutates the source. */
    T cloneWithFakeId();

    /** The {@code (v1,v2,…)} value tuple for the multi-row INSERT, in the bean's insert-column order (no id). */
    String insertValues();

    /** The {@code col=val,col=val,…} counter assignments for an UPDATE (dimensions never change). */
    String updateAssignments();
}
