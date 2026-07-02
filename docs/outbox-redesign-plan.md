# Outbox-consumer redesign — migration plan (for review, not yet built)

Authoritative design: `/tmp/shared-instruction/summary-service-outbox-design.md` (user + architect + dotnet
ratified). This plan switches the **input + progress** layer from Kafka cdr-events to a MySQL outbox; the
ratified **engine** (load-once → merge → segmented insert → one-tx) and the `CdrSummary` entity/builder are
**unchanged** — they just read the decompressed blob.

> **CONTRACT PINNED (2026-06-27, dotnet producer DONE + 101 tests green).** No more provisional input:
> blob = `base64(gzip(UTF8 JSON))` array of `{Cdr, Customer}` (C# PascalCase, case-insensitive, nulls omitted);
> ping topic `cdr_summary_ping`; offset column **`last_offset`**; DDL = billing-core
> `src/Billing.Data/Sql/summary_outbox.sql`; sum_voice 47 cols (matches `CallSummary`); table name now DERIVED
> `sum_voice_<window>_<table-suffix>` (suffix is config — point it at the existing `_03`/`_02` tables, no forced divergence),
> RANGE partitions **by date on `tup_starttime`** (one/day, ~1000 up front — NOT by month); the engine prunes
> UPDATE/DELETE to the one day partition via `… WHERE id=? AND tup_starttime=?` (decisions §13b). Architect **Q3
> effectively answered** (builder reads only Cdr+Customer — context is
> NOT load-bearing). Architect **Q1 (topology) / Q2 (reaper set)** still open but don't block (defaults hold).

## 1. The two tables (PINNED — billing-core `src/Billing.Data/Sql/summary_outbox.sql`)

| Table | Owner | Shape |
|---|---|---|
| `summary_affected` | billing writes, summary reads + reaps | `id BIGINT AUTO_INCREMENT PK, entity_type VARCHAR(32), data LONGTEXT, KEY ix_entity(entity_type,id)` |
| `summary_offset` | summary writes | `entity_type VARCHAR(32), bean_name VARCHAR(64), last_offset BIGINT DEFAULT 0, PK(entity_type,bean_name)` |

`last_offset` = id of the last fully-processed outbox row for that bean. Read `WHERE entity_type=? AND
id>last_offset ORDER BY id LIMIT N`. (Summary ships an identical copy of this DDL for its IT.)

## 2. The per-bean drain — exactly-once in ONE MySQL tx

Each enabled bean runs its **own worker thread** (parallel; daily + hourly are independent). Woken by a Kafka
**ping** (no payload) or a fallback timer, it drains the outbox:

```
drainOnce(bean):                      # ONE MySQL transaction
  begin uow (autocommit off)
    offset  = outbox.readOffset(entity, bean)              # SELECT ... FOR UPDATE
    rows    = outbox.readAfter(entity, offset, maxRowsPerTx)
    if rows empty: commit; return DRAINED
    entities = for each row: decode(decompress(row.data)) -> for each rated cdr: bean.build(bytes)  (SG/window filter)
    engine.runBatch(bean, entities, uow.store(), segmentSize)   # load-once + merge + segmented write
    outbox.advanceOffset(entity, bean, rows.last.id)            # SAME tx
    commit                                                       # summaries + offset together => exactly-once
  return (rows.size == maxRowsPerTx) ? MORE : DRAINED
```

- crash before commit → all rolls back → offset unchanged → reprocessed clean (no double-count).
- crash after commit → done. The Kafka offset is NOT progress; a lost/dup ping is harmless.

## 3. The reaper

A scheduled task (every `reaper-interval-seconds`): for each entity with active beans,
`min = MIN(offset)` across those beans, then `DELETE FROM summary_affected WHERE entity_type=? AND id<=min`
(its own tx). Keeps the outbox bounded (option A — shared outbox, billing writes once).

## 4. config-manager context layer

Per the user directive: an active cdr bean loads the **same** `MediationContext` billing loads, from
**config-manager** (unchanged — summary is just another client of `/get-specific-tenant-root`). Loaded **once**
per `contexts:` entry, shared **read-only**; beans reference it by name. Loaded **lazily at bean activation**,
so build/test/boot need no config-manager. The `CdrSummaryBuilder` consults it where billing does (SG→table /
partner-derived fields) — exact reads are PROVISIONAL (the blob already carries rated cdrs+chargeables).

## 5. Config (profile yml) — new shape

```yaml
summary:
  contexts:
    mediationContext:
      source: config-manager
      config-manager: { base-url: http://10.10.196.1:7071, tenant: tcbl }   # PROVISIONAL
  outbox:
    ping-topic: cdr_summary_ping        # PINNED
    poll-interval-seconds: 5            # fallback poll if no ping
    max-rows-per-tx: 1                  # 1 outbox row = 1 packed billing batch (~1000 cdrs) per tx (user-ratified; was 50)
    reaper-interval-seconds: 60
    quarantine-after: 8                 # decode/build failures on the same head row before dead-lettering (§13c)
  # NOTE: shape below is SUPERSEDED — see decisions §12d (per-window classes + CDI discovery) and §12f
  # (derived table). Shipped reality: window + entity_type="cdr" are fixed by the bean class; the table
  # derives as sum_voice_<window>_<table-suffix>; service-group + table-suffix + context are config.
  enabledSummary: [ dailyCallSummary, hourlyCallSummary ]
  beans:
    dailyCallSummary:  { service-group: 10, table-suffix: "3", context: mediationContext }   # -> sum_voice_day_3
    hourlyCallSummary: { service-group: 10, table-suffix: "3", context: mediationContext }   # -> sum_voice_hr_3
```

`topic` (per-bean Kafka cdr topic) is **gone**; replaced by `entity` (outbox entity_type) + `context`.

## 6. File-by-file

> **Shipped deltas vs this table** (decisions §13c audit): `JdbcOutboxStore` lives in `runtime/internal/` (not
> `outbox/internal/`); the SPI method is `buildBatch(byte[])` (not `build`); the reaper uses a raw
> `ScheduledExecutorService` and the config-manager client the JDK `HttpClient` — `quarkus-scheduler` /
> `quarkus-rest-client` were never added; the `MORE/DRAINED` protocol shipped as loop-until-0-rows; the
> `contexts:` block flattened to `summary.contexts.<name>.{base-url,tenant}`. New since the audit:
> `summary_deadletter` (poison quarantine), `initOffsetAtHead` (head-init), `summarybeans/call/CallSummaries`
> (config-instantiated instances, §12g).

### New
| File | Role |
|---|---|
| `outbox/spi/OutboxStore` | readOffset · readAfter · advanceOffset · minOffset · deleteUpTo (the outbox+offset DAO seam) |
| `outbox/spi/OutboxRow` | `{ long id, String data }` (data = base64(gzip(json))) |
| `outbox/internal/OutboxCodec` | base64-decode → gunzip → JSON array of `{Cdr,Customer}` (PINNED codec, isolated) |
| `outbox/internal/JdbcOutboxStore` | OutboxStore over the tx connection |
| `outbox/api/OutboxReader` | the drain loop (`drainOnce` + drain-until-empty) — replaces the per-batch tx owner |
| `outbox/internal/OutboxReaper` | scheduled min-offset delete |
| `context/api/ContextRegistry` | load each `contexts:` entry once, hold read-only, share |
| `context/spi/SummaryContext` | marker for a loaded context |
| `context/cdr/MediationContext` | cdr context (partners/routes/SG routing) — PROVISIONAL shape |
| `context/internal/ConfigManagerClient` | REST `/get-specific-tenant-root` (mirrors routesphere) |
| `registry/internal/OutboxWorker` | one thread per bean: wait ping/timer → `OutboxReader.drain(bean)` |
| `ping/internal/PingListener` | one Kafka consumer on the ping topic → wake workers |
| `src/main/resources/db/summary_outbox.sql` | provisional `summary_affected` + `summary_offset` DDL |

### Changed
| File | Change |
|---|---|
| `bean/spi/SummaryBean` | drop `topic()`; add `entityType()` + `contextName()`; keep `build(byte[])` (now a blob record) |
| `runtime/spi/UnitOfWork` | add `outbox()` (OutboxStore over the same connection) |
| `runtime/internal/JdbcUnitOfWork[Factory]` | construct JdbcOutboxStore too |
| `runtime/api/BatchRunner` | fold into / called by `OutboxReader` so the offset-advance rides the summary tx (1 tx) |
| `registry/spi/BeanConfig` | `topic`→`entityType`; add `contextName`; (window/table/sg unchanged) |
| `registry/api/SummaryBeanRegistry` | start an `OutboxWorker` per bean instead of a Kafka cdr worker |
| `registry/internal/SummaryBootstrap` | read `contexts:` + load via ContextRegistry; wire `entity`/`context` |
| `beans/cdr/CdrSummaryBean` | `entityType()="cdr"`, `contextName()`; build from a `{Cdr,Customer}` blob entry |
| `beans/cdr/CdrSummaryBuilder` | take `(Cdr, Customer)` (the legacy signature) instead of a flattened event; context not load-bearing |
| `beans/cdr/RatedCdrEvent` → split | `Cdr` + `Customer` (acc_chargeable) records + `CdrBlobEntry{Cdr,Customer}`; Jackson case-insensitive (C# PascalCase), JavaTime for `StartTime`/`ConnectTime` |
| `bean/spi/WindowSize` | bucket a **LocalDateTime** (cdr.StartTime is already wall-clock local) — drop the `Instant`+`ZoneId` path |
| `config/.../profile-dev.yml` | the new shape in §5 |
| `pom.xml` | add `quarkus-scheduler` (reaper) + `quarkus-rest-client` (config-manager) |

### Removed
| File | Why |
|---|---|
| `registry/internal/SummaryWorker` (Kafka cdr poll loop) | replaced by `OutboxWorker` + `OutboxReader` |
| `registry/internal/KafkaConsumerFactory` (cdr consumer) | replaced by the ping consumer (small, in PingListener) |

### Unchanged (the ratified core)
`engine/*` (SummaryEngine, SummaryCache, SummaryStore, SegmentedSqlWriter, CollectionSegmenter, MergeMode),
`bean/spi/{SummaryEntity, SummaryKey, SqlLiterals}`, `beans/cdr/CdrSummary` (47 cols already match),
`config/internal/{TenantProfileConfigSource, ProfileYamlLoader}`. (`WindowSize` is lightly changed — see above.)

## 7. Tests
- Keep: CdrSummaryTest, WindowSizeTest, SummaryCacheTest, SummaryEngineTest, CdrSummaryBeanTest.
- New unit (fakes): OutboxCodecTest (base64+gzip+json {Cdr,Customer} round-trip, C# PascalCase decode) ·
  OutboxReaderTest (drain reads after offset, builds, merges, advances offset; **re-drain after simulated
  crash-before-commit does NOT double count**) · exactly-once (offset+summaries roll back together on failure) ·
  OutboxReaperTest (min-offset delete).
- New IT (real MySQL, opt-in/skip): seed `summary_affected` with a real base64(gzip(json)) {Cdr,Customer} batch
  → run the daily bean drain → assert summaries written + `last_offset` advanced + re-drain is a no-op
  (exactly-once) + reaper deletes once both beans pass.

## 8. Contract status — now PINNED (dotnet producer DONE + green)
- **blob codec + field list** — PINNED: `base64(gzip(json))` array of `{Cdr,Customer}`; isolated in `OutboxCodec`
  + `Cdr`/`Customer` records. ✓
- **outbox DDL** (`summary_affected` + `summary_offset.last_offset`) — PINNED (billing-core sql). ✓
- **ping topic** `cdr_summary_ping` — PINNED. ✓
- **sum_voice** 47 cols + SG routing — PINNED (matches `CdrSummary`); partitioned by DATE on `tup_starttime`
  (one/day) — the provisional DDL notes it and the engine prunes UPDATE/DELETE to the one day partition.
- **Still open (architect, non-blocking):** Q1 deployment topology, Q2 reaper offset-set + bean deregister.
  **MediationContext shape** stays provisional but is NOT load-bearing for the CDR build (builder reads only
  Cdr+Customer), so it doesn't gate the build.

## 9. Defaults I'll use (call out — change if you prefer)
- `max-rows-per-tx=1` (user-ratified 2026-07-02; one packed billing batch per tx — was 50), `reaper-interval=60s`,
  `poll-fallback=5s`, `quarantine-after=8`, **gzip** codec (64 MiB decoded cap), **one worker thread per bean**,
  offset = last-processed outbox id (`id>offset`), head-init on first start. Lands on **master** (per your choice), no deploy.
