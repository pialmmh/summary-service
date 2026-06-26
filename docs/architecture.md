# summary-service — architecture

## TreeView

```
summary-service (generate time-windowed counters for any event stream)
├── bean (the pluggable contract)
│   └── spi · SummaryBean, WindowDef, DimensionDef, CounterDef, Granularity, ColumnType
├── engine (load-merge-write, bean-agnostic)
│   ├── api · SummaryEngine (runBatch), BatchResult
│   ├── spi · SummaryStore (db seam), SummaryRow, WindowSchema, ColumnDef, MergeMode, RowKey
│   └── internal · SummaryCache, SqlRenderer, SegmentedSqlWriter, CollectionSegmenter,
│                  WindowSchemaFactory, RowFactory, WindowsInvolved
├── runtime (the ONE transaction + JDBC)
│   ├── api · BatchRunner (begins a unit of work, commits once, rolls back on any failure)
│   ├── spi · UnitOfWork, UnitOfWorkFactory
│   └── internal · JdbcUnitOfWorkFactory, JdbcUnitOfWork, JdbcSummaryStore
├── registry (lifecycle / hot-start)
│   ├── api · SummaryBeanRegistry (register, start, stop, status)
│   └── internal · SummaryWorker (poll loop), KafkaConsumerFactory, SummaryBootstrap (startup)
├── config (routesphere-like)
│   └── internal · TenantProfileConfigSource, ProfileYamlLoader
└── beans/cdr (the reference bean)
    └── CdrVoiceSummaryBean, RatedCdrEvent (PROVISIONAL)
```

Discover the system through `**/api` + `**/spi`; `internal/` is implementation (no outward imports).

## Flow: one batch (cdrSummaryOnBatch)

Runs when a worker polls a non-empty batch from the bean's topic.

1. `SummaryWorker` polls up to `batchSize` records and decodes each via `SummaryBean.deserialize` → events.
2. `SummaryWorker` calls `BatchRunner.run(bean, events)`.
3. `BatchRunner` begins a `UnitOfWork` (a MySQL connection, autocommit off) and calls `SummaryEngine.runBatch`.
4. For each `WindowDef` (day, hour): `SummaryEngine` computes `WindowsInvolved`, calls `SummaryStore.load`
   ONCE for those buckets, seeds the `SummaryCache`, then merges every event's `RowFactory` delta (ADD).
5. `SummaryEngine` flushes each cache: `SqlRenderer` builds the multi-row INSERT (new rows) + id-targeted
   UPDATEs (loaded rows), `SegmentedSqlWriter` runs them in segments through the `SummaryStore`.
6. `BatchRunner` commits the `UnitOfWork`; then `SummaryWorker` `commitSync()`s the Kafka offset.
7. On ANY exception in 3–5, `BatchRunner` rolls the whole batch back and the offset is NOT committed → the
   batch redelivers (repaired by the correction/overwrite path).

## Why these seams

- **`SummaryBean` (bean/spi)** — bean authors declare windows/dimensions/counters; no engine change to add a
  counter type. The reference `CdrVoiceSummaryBean` is just one implementation.
- **`SummaryStore` (engine/spi)** — the DB is a seam, so the engine is tested with an in-memory fake (the
  fake IS the test surface); production is `JdbcSummaryStore`.
- **`UnitOfWork` (runtime/spi)** — the transaction boundary is a seam, so rollback is unit-tested with no
  database, and proven against real MySQL in the integration test.
