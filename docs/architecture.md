# summary-service — architecture

The generic axis is the typed summary **entity `T`** (e.g. `CallSummary`). The input is a **MySQL outbox**
(`summary_affected`), consumed **exactly-once per bean** via a per-bean **MySQL offset** (`summary_offset`);
Kafka (`cdr_summary_ping`) is only a wakeup. The ratified load-merge-write engine reads the decompressed blob.

## TreeView

```
summary-service (incremental time-windowed counters from a MySQL outbox)
├── bean/spi · SummaryEntity<T> · SummaryBean<T> · SummaryKey · WindowSize · SqlLiterals
├── engine
│   ├── api · SummaryEngine (runBatch), BatchResult
│   ├── spi · SummaryStore (summary-row db seam), RowMapper, MergeMode, SummaryStoreException
│   └── internal · SummaryCache<T>, SegmentedSqlWriter, CollectionSegmenter
├── outbox
│   ├── api · OutboxReader (drain = the ONE tx per step: read offset+rows → merge → write summaries → advance offset)
│   ├── spi · OutboxStore (offset/rows/reap DAO), OutboxRow
│   └── internal · OutboxCodec (base64/gzip/json), OutboxReaper (scheduled trim)
├── runtime (the ONE transaction + JDBC)
│   ├── spi · UnitOfWork (store() + outbox(), same connection), UnitOfWorkFactory
│   └── internal · JdbcUnitOfWorkFactory, JdbcUnitOfWork, JdbcSummaryStore, JdbcOutboxStore
├── registry (lifecycle / parallel workers / hot-start)
│   ├── api · SummaryBeanRegistry (register, start, stop, wakeAll, runningBeanNames)
│   └── internal · OutboxWorker<T> (drain loop, woken by ping/timer), SummaryBootstrap (CDI-discovers beans by name)
├── context (shared read-only, from config-manager)
│   ├── api · ContextRegistry (load each context once)
│   ├── spi · SummaryContext
│   └── internal/cdr · ConfigManagerClient, MediationContext
├── ping/internal · PingListener (Kafka cdr_summary_ping → wake workers)
├── config/internal · TenantProfileConfigSource, ProfileYamlLoader
├── beans/                       (PUBLIC API — fluent builders, the high-level entry points lib users import)
│   · SummaryBeanBuilder<T,B> (the enforced contract) · DailySummaryBuilder · HourlySummaryBuilder
└── summarybeans/                (one sub-package per category — call today; sms/packetflow/session/voip/video later)
    ├── call ·  HourlySummary · DailySummary        (HIGH-LEVEL window beans only)
    │   ├── internal · CallSummaryBean (shared base) · CallSummaryBuilder · CdrBlobMapper
    │   └── model    · CallSummary (entity, 47 cols) · Cdr/Customer/CdrBlobEntry
    └── sms  ·  (future — same shape as call)
```

Discover the system through `**/api` + `**/spi`; `internal/` is implementation (no outward imports).

## Exactly-once per bean

Each bean has its own `summary_offset(entity_type, bean_name, last_offset)`. A drain reads the offset, reads
the next outbox rows, writes its summaries, **and advances its offset — all in ONE MySQL transaction**
(`UnitOfWork` exposes both `store()` and `outbox()` over the same connection). So work + progress commit
together: a crash before commit rolls both back (offset unchanged → reprocessed clean, no double-count); the
Kafka offset is never used for progress, so a lost/duplicate ping is harmless. Single active instance per
tenant (architect Q1).

## Flow: one drain (cdrOutboxDrain)

Runs when a worker wakes (ping or fallback timer) for a bean that has un-consumed outbox rows.

1. `OutboxWorker` calls `OutboxReader.drain(bean)`.
2. `OutboxReader` begins a `UnitOfWork` and reads `outbox().readOffset(entity, bean)`.
3. `outbox().readAfter(entity, offset, maxRowsPerTx)` → the next `summary_affected` rows.
4. For each row: `OutboxCodec.decode` (base64→gunzip) → `bean.buildBatch` (parse `{Cdr,Customer}` array, filter
   service group, bucket each via `WindowSize`) → entities.
5. `SummaryEngine.runBatch` computes the involved windows, `SummaryStore.load`s them ONCE, merges every entity
   (`SummaryCache<T>`), and flushes segmented INSERT/UPDATE through the same connection.
6. `outbox().advanceOffset(entity, bean, lastRowId)`; `UnitOfWork.commit()` — summaries + offset together.
7. On ANY exception in 2–6, `OutboxReader` rolls back; the offset is unchanged and the rows redeliver.
8. Separately, `OutboxReaper` deletes `summary_affected` rows with `id ≤ min(last_offset)` across the active beans.

## Why these seams

- **`SummaryEntity` / `SummaryBean` (bean/spi)** — a new summary kind is a new entity + bean class under
  `summarybeans/<category>/`; the engine is untouched. A new **window** of an existing category is a tiny
  `@Singleton` subclass of the category base (e.g. `HourlySummary`/`DailySummary` over `CallSummaryBean`).
- **`SummaryStore` + `OutboxStore` (engine/spi, outbox/spi)** — both DB sides are seams over one connection,
  so the reader is tested with in-memory fakes and proven against real MySQL in the IT.
- **`UnitOfWork` (runtime/spi)** — the transaction boundary that makes summaries + offset atomic (exactly-once).
- **`SummaryBootstrap` (registry/internal)** — CDI-discovers every `SummaryBean` and activates the ones named
  in `summary.enabledSummary`; `table`/`service-group`/`context` come from `summary.beans.<name>` (the window is
  the class). No factory: adding a bean is adding a class + a YAML line.
- **`ContextRegistry` (context/api)** — config-manager loaded once, shared read-only (not load-bearing for CDR v1).
