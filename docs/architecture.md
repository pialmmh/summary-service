# summary-service — architecture

The generic axis is the **summary ENTITY `T`** (a real typed class, e.g. `CdrSummary`), not the event. The
engine does load-merge-write over `T`; `T` owns its key, merge, negate, clone, and SQL fragments.

## TreeView

```
summary-service (time-windowed counters for any event stream)
├── bean/spi · SummaryEntity<T> · SummaryBean<T> · SummaryKey · WindowSize · SqlLiterals
├── engine
│   ├── api · SummaryEngine (runBatch), BatchResult
│   ├── spi · SummaryStore (db seam), RowMapper, MergeMode, SummaryStoreException
│   └── internal · SummaryCache<T>, SegmentedSqlWriter, CollectionSegmenter
├── runtime (the ONE transaction + JDBC)
│   ├── api · BatchRunner (begins a unit of work, commits once, rolls back on any failure)
│   ├── spi · UnitOfWork, UnitOfWorkFactory
│   └── internal · JdbcUnitOfWorkFactory, JdbcUnitOfWork, JdbcSummaryStore
├── registry (lifecycle / hot-start / config-driven instances)
│   ├── api · SummaryBeanRegistry (register, start, stop, status)
│   ├── spi · SummaryBeanFactory (one per entity), BeanConfig
│   └── internal · SummaryWorker<T> (poll loop), KafkaConsumerFactory, SummaryBootstrap
├── config/internal · TenantProfileConfigSource, ProfileYamlLoader
└── beans/cdr · CdrSummary (entity, 47 cols) · CdrSummaryBuilder · CdrSummaryBean · CdrSummaryBeanFactory · RatedCdrEvent
```

Discover the system through `**/api` + `**/spi`; `internal/` is implementation (no outward imports).

## The entity model

- **`SummaryEntity<T>`** (= legacy `ISummary` + `ICacheble`): `id` · `tupleKey()` · `merge(T)` · `multiply(int)`
  · `cloneWithFakeId()` · `insertValues()` · `updateAssignments()`. The key type is fixed to `SummaryKey`
  (a canonical token tuple), so the engine is generic over just `T`.
- **`CdrSummary`** is the faithful 1:1 port of billing-core's `AbstractCdrSummary` — all 47 columns, exact
  `Merge` (adds every counter incl. `connectedcallsCC`) and `Multiply` (scales every counter EXCEPT
  `connectedcallsCC` — a deliberate legacy quirk, replicated). ONE entity for day AND hour AND call AND sms.
- A future `CallQuality` summary = a new class on the same interface + its own factory; no engine change.

## Beans are config-driven instances

One **`SummaryBean<T>` class per entity** (e.g. `CdrSummaryBean`); each `enabledSummary` entry is a distinct
configured instance differing only by `window` + `table` (+ topic/filter). Daily vs hourly vs 5-minute vs
weekly are configs, not classes. `SummaryBeanFactory` (one per entity) turns a `BeanConfig` into a live bean.

`WindowSize` truncates an event instant to the bucket (`tup_starttime`): `5min` / `Nmin` (multiple of 5) /
`hourly` / `daily` / `weekly` (Monday-start ISO week) / `monthly` / `yearly`.

## Flow: one batch (cdrSummaryOnBatch)

Runs when a worker polls a non-empty batch from the bean's topic.

1. `SummaryWorker` polls up to `batchSize` records; `SummaryBean.build` decodes + buckets each into a `T`
   (or null to skip — e.g. another service group on a shared topic).
2. `SummaryWorker` calls `BatchRunner.run(bean, entities)`.
3. `BatchRunner` begins a `UnitOfWork` (a MySQL connection, autocommit off) and calls `SummaryEngine.runBatch`.
4. `SummaryEngine` computes the distinct buckets, calls `SummaryStore.load` ONCE (mapping rows via
   `bean.mapRow`), seeds `SummaryCache<T>`, then merges every entity (`T.merge`).
5. `SummaryEngine` flushes the cache: `T.insertValues()` build the multi-row INSERT (new rows) +
   `T.updateAssignments()` build id-targeted UPDATEs (loaded rows), run in segments through the store.
6. `BatchRunner` commits; then `SummaryWorker` `commitSync()`s the Kafka offset.
7. On ANY exception in 3–5, `BatchRunner` rolls the whole batch back and the offset is NOT committed → the
   batch redelivers (repaired by the correction/overwrite path).

## Why these seams

- **`SummaryEntity` / `SummaryBean` (bean/spi)** — a new summary kind is a new entity + bean; the engine is untouched.
- **`SummaryStore` (engine/spi)** — the DB is a seam; the engine is tested with an in-memory fake, production is JDBC.
- **`UnitOfWork` (runtime/spi)** — the transaction boundary is a seam; rollback is unit-tested with no DB and proven against real MySQL.
- **`SummaryBeanFactory` + `BeanConfig` (registry/spi)** — config-driven instances; new windows/tables with no code.
