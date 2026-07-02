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
│   ├── api · OutboxReader (drain = the ONE tx per step: read offset+rows → merge → write summaries → advance offset;
│   │         + head-init, + poison quarantine → summary_deadletter after quarantine-after consecutive failures)
│   ├── spi · OutboxStore (offset/rows/reap/dead-letter DAO), OutboxRow
│   └── internal · OutboxCodec (base64/gzip/json, 64 MiB decoded cap), OutboxReaper (scheduled trim)
├── runtime (the ONE transaction + JDBC)
│   ├── spi · UnitOfWork (store() + outbox(), same connection), UnitOfWorkFactory
│   └── internal · JdbcUnitOfWorkFactory, JdbcUnitOfWork, JdbcSummaryStore, JdbcOutboxStore
├── registry (lifecycle / parallel workers / hot-start)
│   ├── api · SummaryBeanRegistry (register, start, stop, wakeAll, runningBeanNames)
│   └── internal · OutboxWorker<T> (drain loop, woken by ping/timer), SummaryBootstrap (CDI-discovers beans by name)
├── context (shared read-only, from config-manager)
│   ├── api · ContextRegistry (load each context once)
│   ├── spi · SummaryContext
│   ├── internal · ConfigManagerClient
│   └── cdr · MediationContext (PROVISIONAL shape; not load-bearing for the CDR build)
├── ping/internal · PingListener (Kafka cdr_summary_ping → wake workers)
├── config/internal · TenantProfileConfigSource, ProfileYamlLoader
├── beans/                       (PUBLIC API — fluent builders, the high-level entry points lib users import)
│   · SummaryBeanBuilder<T,B> (root: mapper+context+final build→validate) · CallBeanBuilder (voice: SG+suffix)
│   · DailySummaryBuilder · HourlySummaryBuilder · Daily/HourlyChargeableSummaryBuilder
└── summarybeans/                (one sub-package per category — call + chargeable today; sms/packetflow later)
    ├── call ·  HourlySummary · DailySummary        (HIGH-LEVEL window beans only)
    │   │       · CallSummaries (factory for CONFIG-INSTANTIATED extra instances, e.g. the SG11 pair — §12g)
    │   ├── internal · CallSummaryBean (shared base) · CallSummaryBuilder (+ key canonicalization) · CdrBlobMapper
    │   │            · SumVoiceDdl (self-provisioning CREATE with full partitions)
    │   └── model    · CallSummary (entity, 47 cols) · Cdr/Chargeable/CdrBlobEntry (blob v2, v1 tolerated —
    │                  the ONE pinned blob contract, shared by both categories)
    ├── chargeable · HourlyChargeableSummary · DailyChargeableSummary   (EVERY leg, every SG — §13d)
    │   ├── internal · ChargeableSummaryBean (base) · ChargeableSummaryBuilder · SumChargeableDdl
    │   └── model    · ChargeableSummary (7-col key + 15 measures, DECIMAL(20,8))
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

1. `OutboxWorker` loops `OutboxReader.drainOnce(bean)` until caught up — checking its stop flag between the
   bounded per-tx steps, so `stop()`/`start()` can never run two workers over one offset.
2. `OutboxReader` begins a `UnitOfWork` and reads `outbox().readOffset(entity, bean)` (seeded at the outbox
   HEAD by `initOffsetAtHead` when the worker first started — a late-enabled bean summarises from NOW).
3. `outbox().readAfter(entity, offset, maxRowsPerTx)` → the next `summary_affected` rows (default 1 = one
   packed billing batch of ~1000 cdrs per transaction).
4. For each row: `OutboxCodec.decode` (base64→gunzip) → `bean.buildBatch` (parse `{Cdr,Customer}` array, filter
   service group, bucket each via `WindowSize`) → entities. A row that fails HERE (deterministic on data) is
   poison: after `quarantine-after` consecutive failures at the head it is copied to `summary_deadletter` and
   skipped in the same tx; clean rows before it commit as a prefix. SQL failures are never quarantined.
5. `SummaryEngine.runBatch` runs PER ROW with the row's `op` (`add` → ADD, `subtract` → SUBTRACT — a billing
   correction is subtract(OLD)+add(NEW) in id order): computes the involved windows, `SummaryStore.load`s them
   ONCE, merges every entity (`SummaryCache<T>`), and flushes segmented INSERT/UPDATE (`… WHERE id=? AND
   <bucket>=?` — partition pruning, §13b) through the same connection. A SUBTRACT on a missing window is
   poison (ruling A1) and follows step 4's quarantine.
6. `outbox().advanceOffset(entity, bean, lastRowId)`; `UnitOfWork.commit()` — summaries + offset together.
7. On ANY exception in 2–6, `OutboxReader` rolls back; the offset is unchanged and the rows redeliver (the
   worker backs off linearly to 60s while failing, with an escalating error count).
8. Separately, `OutboxReaper` deletes `summary_affected` rows with `id ≤ min(last_offset)` across the active beans.

## Why these seams

- **`SummaryEntity` / `SummaryBean` (bean/spi)** — a new summary kind is a new entity + bean class under
  `summarybeans/<category>/`; the engine is untouched. A new **window** of an existing category is a tiny
  `@Singleton` subclass of the category base (e.g. `HourlySummary`/`DailySummary` over `CallSummaryBean`).
- **`SummaryStore` + `OutboxStore` (engine/spi, outbox/spi)** — both DB sides are seams over one connection,
  so the reader is tested with in-memory fakes and proven against real MySQL in the IT.
- **`UnitOfWork` (runtime/spi)** — the transaction boundary that makes summaries + offset atomic (exactly-once).
- **`SummaryBootstrap` (registry/internal)** — CDI-discovers every `SummaryBean` and activates the ones named
  in `summary.enabledSummary`; `table-suffix`/`service-group`/`context` come from `summary.beans.<name>` (the
  window is the class). Adding a catalog bean is adding a class + a YAML line; an extra INSTANCE of a window
  (e.g. a second service group) is YAML-only via the `window:` key → `CallSummaries.forWindow` (§12g).
- **`ContextRegistry` (context/api)** — config-manager loaded once, shared read-only (not load-bearing for CDR v1).
