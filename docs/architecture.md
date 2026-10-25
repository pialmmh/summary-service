# summary-service — architecture

The generic axis is the typed summary **entity `T`** (e.g. `CallSummary`). The input is a transactional
**outbox** (`summary_affected`) in each tenant's own schema — MySQL or PostgreSQL, per profile — consumed
**exactly-once per (schema, bean)** via a bookmark in the same schema (`summary_offset`); Kafka
(`cdr_summary_ping`) is only a wakeup. The ratified load-merge-write engine reads the decompressed blob. One
process serves every tenant schema of its root's tree (2026-10-04; decisions §16). Since SS-0008 (2026-10-24; decisions §17) the
beans are grouped by KIND — the call, the ad view, the WiFi session — each ONE package with its bean set, switched on per
tenant by `summary.kinds` (none named = the call alone).

## TreeView

```
summary-service (incremental time-windowed counters from a MySQL outbox)
├── bean/spi · SummaryEntity<T> · SummaryBean<T> · SummaryKey · WindowSize · SqlLiterals
│             · SqlDialect · SummaryTableSpec (a table described once) · TableDdl (rendered per engine)
│             · SummaryKind (a kind: its word, its service groups, its entities, its bean set — SS-0008)
│             · ServiceGroupReader (a call-shaped bean: the kind of the group it reads) · CrossKindReader (the chargeable roll-up)
├── engine
│   ├── api · SummaryEngine (runBatch), BatchResult
│   ├── spi · SummaryStore (summary-row db seam), RowMapper, MergeMode, SummaryStoreException
│   └── internal · SummaryCache<T>, SegmentedSqlWriter, CollectionSegmenter
├── outbox
│   ├── api · OutboxReader (drain = the ONE tx per step: read offset+rows → merge → write summaries → advance offset;
│   │         + head-init, + poison quarantine → summary_deadletter after quarantine-after consecutive failures)
│   ├── spi · OutboxStore (offset/rows/reap/dead-letter DAO), OutboxRow, RowCensus (hears a step's committed rows — SS-0008)
│   └── internal · OutboxCodec (base64/gzip/json, 64 MiB decoded cap), OutboxReaper (scheduled trim)
├── runtime (the ONE transaction + JDBC — the EDGE: everything an engine differs in is here)
│   ├── spi · UnitOfWork (schema(), dialect(), store() + outbox(), same connection), UnitOfWorkFactory (begin(schema))
│   └── internal · JdbcUnitOfWorkFactory (enters the tenant's schema; PostgreSQL: SET LOCAL, nothing stays on the
│                  connection), JdbcUnitOfWork, JdbcSummaryStore, JdbcOutboxStore (the bookmark's SQL per engine),
│                  StoreDataSource (the pool, built from the profile at first use), StoreConfig, StoreSecret
├── registry (lifecycle / parallel workers / hot-start)
│   ├── api · SummaryBeanRegistry (register; serve / unserve a schema; start / stop a bean; wake(tenant, entity))
│   └── internal · OutboxWorker<T> (the drain loop of ONE (schema, bean), woken by ping/timer),
│                  SummaryBootstrap (CDI-discovers beans by name; a bean of a kind not enabled refuses the start),
│                  StartEndpoints (what a start will dial, said first), KnownKinds (call, ad, wifi), TenantKinds
│                  (summary.kinds), KindGate (the rows of the kinds not enabled: counted once, KIND_NOT_ENABLED)
├── tenancy (which schemas this process serves)
│   ├── api · TenantWatcher (single: the connection's own schema | tree: every schema of the root's tree; reads
│   │         the tree at the start, at every doorbell, on a timer; what could not be served is tried again)
│   ├── spi · TenantTreeSource
│   └── internal · PrimeContextTreeSource (POST /get-specific-tenant-root), TenantTreeParser (dbName + children,
│                  streamed), DoorbellListener (Kafka config_event_loader_<root>)
├── context (shared read-only, from config-manager)
│   ├── api · ContextRegistry (load each context once)
│   ├── spi · SummaryContext
│   ├── internal · ConfigManagerClient
│   └── cdr · MediationContext (PROVISIONAL shape; not load-bearing for the CDR build)
├── ping/internal · PingListener (Kafka cdr_summary_ping → wake the workers of the tenant it names), PingPayload
├── config/internal · TenantProfileConfigSource, ProfileYamlLoader
├── beans/                       (PUBLIC API — fluent builders, the high-level entry points lib users import)
│   · SummaryBeanBuilder<T,B> (root: mapper+context+final build→validate) · CallBeanBuilder (voice: SG+suffix)
│   · DailySummaryBuilder · HourlySummaryBuilder · Daily/HourlyChargeableSummaryBuilder
└── summarybeans/                (ONE package per KIND — SS-0008; sms next)
    ├── call ·  CallKind (groups 10, 11 and every group no kind names) · HourlySummary · DailySummary (window beans)
    │   │       · CallSummaries (factory for CONFIG-INSTANTIATED extra instances, e.g. the SG11 pair — §12g)
    │   ├── internal · CallSummaryBean (shared base, a ServiceGroupReader) · CallSummaryBuilder (+ key canonicalization)
    │   │            · CdrBlobMapper · SumVoiceDdl (self-provisioning CREATE with full partitions)
    │   ├── model    · CallSummary (entity, 47 cols) · Cdr/Chargeable/CdrBlobEntry (blob v2, v1 tolerated —
    │   │              the ONE pinned blob contract, shared by every kind)
    │   └── chargeable · HourlyChargeableSummary · DailyChargeableSummary   (EVERY leg of the ENABLED kinds' records — §13d, §17e)
    │       ├── internal · ChargeableSummaryBean (base, a CrossKindReader) · ChargeableSummaryBuilder · SumChargeableDdl
    │       └── model    · ChargeableSummary (7-col key + 15 measures, DECIMAL(20,8))
    ├── ad · AdKind (group 30) · HourlyAdSummary · DailyAdSummary   (the SAME cdr stream — §16b; + the call-shaped sum_voice_*_30)
    │   ├── internal · AdSummaryBean (base) · AdSummaryBuilder · AdMetaData · SumAdDdl
    │   └── model    · AdSummary · AdCallCdr / AdCallEntry (the ad's view of the blob) · AdViewFacts · AdView
    ├── wifi · WifiKind (group 101; entities cdr + wifi_flow) · WifiSummaries · WifiBeanKind (the shapes wifi-session | wifi-dst)
    │   ├── internal · WifiSessionSummaryBean · WifiDestinationSummaryBean · builders · generators · SumWifi*Ddl
    │   └── model    · WifiSessionSummary · WifiDestinationSummary · WifiFlowRow         (+ the call-shaped sum_voice_*_101)
    └── sms  ·  (future — SmsKind, same shape: docs/ad-as-call/SS-0008-done.md §8)
```

Discover the system through `**/api` + `**/spi`; `internal/` is implementation (no outward imports).

## The kinds (SS-0008, decisions §17)

```
profile: summary.kinds: [call, ad, wifi]   (none = [call])
           │
SummaryBootstrap ── every enabledSummary bean ─▶ KnownKinds.kindOf(bean) ── not enabled? ─▶ REFUSING TO START (nothing registered)
           │                                           (a call-shaped bean: the kind of its group; else the kind owning its class)
           ├── the chargeable roll-up (CrossKindReader) ◀── readOnly(the enabled kinds' groups)
           └── KindGate.countFor(kinds, the first cdr bean, the tenant's zone)
drain of the first cdr bean, per tier ── commit ─▶ RowCensus.committed(rows) ─▶ KindGate: records of kinds not enabled
                                                    counted; WARN KIND_NOT_ENABLED <kind> <n> rows once per tenant, kind and day
```

A record's kind is the kind of its service group (its customer chargeable's, else its `Cdr.ServiceGroup`). With every
kind enabled — the bed's profile — nothing is counted and every bean is exactly as before.

## Exactly-once per (schema, bean)

Each bean has, in each tenant schema, its own `summary_offset(entity_type, bean_name, last_offset)`. A drain reads
the offset, reads the next outbox rows, writes its summaries, **and advances its offset — all in ONE transaction**
in that schema (`UnitOfWork` exposes both `store()` and `outbox()` over the same connection). So work + progress
commit together: a crash before commit rolls both back (offset unchanged → reprocessed clean, no double-count);
the Kafka offset is never used for progress, so a lost/duplicate ping is harmless. Single active instance per
root (architect Q1). The same tests hold it on MySQL and on PostgreSQL (`OutboxConsumerContract`).

A schema's first bookmarks are decided ONCE, for all its beans, before its workers start: a schema never served
starts every bean at 0; on a schema already served, a bean without a bookmark starts at the outbox head (§16h).

## Flow: one drain (cdrOutboxDrain)

Runs when a worker wakes (ping or fallback timer) for a bean that has un-consumed outbox rows.

1. `OutboxWorker` loops `OutboxReader.drainOnce(schema, bean)` until caught up — checking its stop flag between
   the bounded per-tx steps, so `stop()`/`start()` can never run two workers over one offset.
2. `OutboxReader` begins a `UnitOfWork` ON the schema and reads `outbox().readOffset(entity, bean)` (its first
   value was decided when the schema was first served — `seedBookmarks`). A schema that has no outbox table yet
   (billing-core has not written there) is WAITING: the step ends, one WARN was said, nothing is made in its place.
3. `outbox().readAfter(entity, offset, maxRowsPerTx)` → the next `summary_affected` rows (default 1 = one
   packed billing batch of ~1000 cdrs per transaction).
4. For each row: `OutboxCodec.decode` (base64→gunzip) → `bean.buildBatch(json, tier)` (parse the `{Cdr,
   Chargeables}` array, filter service group, bucket each via `WindowSize`; the tier = the schema's own name,
   which an ad row carries as `tup_tenant`) → entities. A row that fails HERE (deterministic on data) is
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
8. Separately, `OutboxReaper` deletes, in each served schema and for EACH entity that has a configured bean, that
   entity's `summary_affected` rows with `id ≤ min(last_offset)` across THAT entity's configured beans — one
   transaction per (schema, entity); an entity no bean reads is never trimmed (decisions §18). It deletes in bounded
   batches — at most `reap-batch` rows (10 000) per transaction, the lowest ids first, through the `(entity_type, id)`
   index — until the passed rows are gone or the pass's `reap-pass-budget-ms` (5 000) is spent; every (schema, entity)
   gets its first batch whatever the budget, the rest waits for the next pass.

## Why these seams

- **`SummaryEntity` / `SummaryBean` (bean/spi)** — a new summary kind is a new entity + bean class under
  `summarybeans/<category>/`; the engine is untouched. A new **window** of an existing category is a tiny
  `@Singleton` subclass of the category base (e.g. `HourlySummary`/`DailySummary` over `CallSummaryBean`).
- **`SummaryStore` + `OutboxStore` (engine/spi, outbox/spi)** — both DB sides are seams over one connection,
  so the reader is tested with in-memory fakes and proven against real MySQL and real PostgreSQL in the ITs.
- **`SqlDialect` + `SummaryTableSpec` (bean/spi)** — the engine, the cache and the entities are one code on both
  engines; a bean DESCRIBES its table once and the store's edge renders it (MySQL: partitioned, the full set in
  the one CREATE; PostgreSQL: a plain table and its indexes, in one transaction).
- **`TenantWatcher` (tenancy/api)** — which schemas are served is one decision in one place; the registry only
  serves what it is told. A tree that cannot be read, or a schema that cannot be served, changes nothing else.
- **`UnitOfWork` (runtime/spi)** — the transaction boundary that makes summaries + offset atomic (exactly-once).
- **`SummaryBootstrap` (registry/internal)** — CDI-discovers every `SummaryBean` and activates the ones named
  in `summary.enabledSummary`; `table-suffix`/`service-group`/`context` come from `summary.beans.<name>` (the
  window is the class). Adding a catalog bean is adding a class + a YAML line; an extra INSTANCE of a window
  (e.g. a second service group) is YAML-only via the `window:` key → `CallSummaries.forWindow` (§12g).
- **`ContextRegistry` (context/api)** — config-manager loaded once, shared read-only (not load-bearing for CDR v1).
