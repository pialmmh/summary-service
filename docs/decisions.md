# summary-service — architect decisions

The rulings this service is built on. Items marked **RATIFIED** come from the design brief
(`CLAUDE.md` + `/tmp/shared-instruction/summary-service-design.md`); the rest are this stream's calls.

> **v2 redesign (2026-06-27) — see §12.** The generic axis moved from the EVENT to the typed summary ENTITY
> `T` per `summary-service-entity-redesign.md` (dotnet, ratified + user directive). The type-model rulings are
> in §12. The v1 declarative `SummaryRow`/dimension-extractor model is superseded.
>
> **v3 redesign (2026-06-28) — see §13.** The INPUT moved from Kafka cdr-events to a MySQL transactional
> OUTBOX + per-bean MySQL offset + Kafka-as-ping, per `summary-service-outbox-design.md` (user + architect +
> dotnet ratified). The engine (§1–3) is unchanged — it reads the outbox blob now. §4 (Kafka-offset
> idempotency) and §11 (recompute-from-cdr correction) are SUPERSEDED by §13.

## 1. load-merge-write is the core — RATIFIED
Not bare `INSERT … ON DUPLICATE KEY UPDATE`. The cache → merge → segmented-write path handles increment
**and** decrement **and** overwrite uniformly and is idempotent for the correction path. Ported from
billing-core's `CdrSummaryContext` / `SummaryCache` / `BatchSqlWriter`.

## 2. Load all involved windows ONCE — RATIFIED (the core invariant)
Per batch, compute the distinct day/hour buckets, then load every existing row for those buckets in ONE query
per window (`WindowsInvolved` → `SummaryStore.load`). Loading per event double-counts. Tested:
`involved_windows_are_loaded_once_per_window_not_per_event`.

## 3. One transaction per batch — RATIFIED
`BatchRunner` owns the single commit/rollback (the `MySqlCdrBatchRunner` rule). The engine, cache, and store
NEVER commit or roll back. Any failure rolls the whole batch back. Tested unit (`BatchRunnerRollbackTest`) and
against real MySQL (`CdrBatchAtomicityIT`).

## 4. Increment idempotency: commit the Kafka offset AFTER the DB commit — RATIFIED (v1 strategy)
At-least-once Kafka + increment double-counts on redelivery. v1: the worker processes the whole batch in one
DB transaction and `commitSync()`s the offset only AFTER the DB commit. A crash in between redelivers and
double-counts; the **correction/overwrite** path is the repair (naturally idempotent). Add per-event/offset
dedup only if double-counts are observed in practice. Demonstrated:
`redelivery_double_counts_and_overwrite_repairs_it`.

## 5. Row identity: AUTO_INCREMENT id + UPDATE-by-id — DEVIATION from the .NET port (intentional)
The .NET impl stamped explicit ids via a `CountingAutoIncrementManager` and inserted with the id. We instead
**omit id on INSERT** (MySQL AUTO_INCREMENT assigns it) and **UPDATE/DELETE by the loaded row's id**. This
works because the engine only ever updates rows it LOADED (which carry their DB id); new rows are always
inserts. Why deviate: it removes a stateful, collision-prone id allocator and is idiomatic MySQL, with no loss
of the load-merge-write semantics. The in-memory cache still de-dups by the dimension+bucket tuple; an
optional `UNIQUE KEY` on that tuple is a safety net (see the DDL).

## 6. MySQL only — RATIFIED
`quarkus-jdbc-mysql` + Agroal. The `;`-joined UPDATE/DELETE segments need `allowMultiQueries=true` on the JDBC
URL (set in the profile yml).

## 7. Config is routesphere-like — RATIFIED
`config/tenants.yml` + per-tenant `profile-<profile>.yml`, flattened by a `ConfigSource`. v1 implements the
profile loader (the core of the pattern). The config-manager REST feed + Kafka reload-with-debounce are
documented extension points, off by default so the app boots with no external services.

## 8. Secrets: INLINE in the profile yml (no OpenBao) — RATIFIED (user, 2026-06-28; supersedes the OpenBao plan)
The DB credentials live **inline** in the active profile yml (`quarkus.datasource.username/password`), pointing
at the CCL infra (DB `103.95.96.77`, Kafka `103.95.96.78:9092`, config-manager `103.95.96.78:7072`) — the
**same approach billing-core uses** (it committed inline creds, `ebd8da0`), chosen for operational parity. This
is a **deliberate deviation from the house OpenBao rule**, made by the user; recorded here so it is intentional,
not accidental. Consequence: when the CCL creds are filled at cutover they become a committed secret (the
tradeoff the user accepted for parity). The fields are currently empty TODOs — nothing sensitive is committed
yet. No `quarkus-vault` dependency. The integration test password is supplied at run time
(`-Dsummary.it.mysql.password`), never committed.

## 9. Dynamic UI-defined beans — LATER (RATIFIED)
The registry already supports runtime `start`/`stop` (a bean hot-starts its own consumer + worker thread with
no restart). The UI to define beans is a later phase; v1 registers the compiled beans at startup.

## 10. Contract pinning — DONE (history)
This was the placeholder-until-dotnet-pins note. The contract is now PINNED and consumed — see §13 and §13a.
The consumed shape is the `{Cdr, Customer}` blob (not the old flattened `RatedCdrEvent`), built by
`CdrSummaryBean`; `sum_voice` matches `CdrSummary` (47 cols).

## 11. Correction path — designed, increment shipped
`MergeMode.OVERWRITE` and the `correction-topic` config exist; the cache supports overwrite (clone the
recomputed entity, keep the loaded id). The dedicated correction consumer (recompute a window from
source-of-truth and overwrite) is a thin follow-up — this delivers the increment pipeline as the brief's first
deliverable specifies.

## 12. Generic axis = the typed entity `T` — RATIFIED (v2, supersedes §the v1 declarative model)
Per `summary-service-entity-redesign.md` (dotnet, ratified + user directive). The engine is generic over the
summary ENTITY `T` (a real class), not the event and not a map-based `SummaryRow`. `T` implements
`SummaryEntity<T>` (= legacy `ISummary` + `ICacheble`): `id` / `tupleKey` / `merge` / `multiply` /
`cloneWithFakeId` / `insertValues` / `updateAssignments`. The key type is fixed to `SummaryKey` (a canonical
token tuple) so the engine carries a single type param. "Support any entity" = a new class on the same
interface. The load-once / segmented-write / one-txn rulings (§1–4, §6) are unchanged.

### 12a. CdrSummary = faithful 1:1 port — incl. the `connectedcallsCC` quirk
`CdrSummary` clones billing-core's `AbstractCdrSummary` verbatim: all 47 columns, the `GetTupleKey` order, and
`Merge`/`Multiply`. `Multiply` scales every counter EXCEPT `connectedcallsCC` (it IS summed in `Merge`) — a
deliberate legacy inconsistency, replicated with a comment so a reader doesn't "tidy" it. Correcting it is a
separate, explicit decision. The id strategy from §5 (AUTO_INCREMENT + update-by-id, no allocator) stands.

### 12b. One bean per entity + configurable window in YAML — RATIFIED (user directive); window-as-config SUPERSEDED by §12d
One `SummaryBean<T>` class per entity (e.g. `CdrSummaryBean`); each `summary.enabledSummary` entry is a
distinct CONFIGURED instance differing only by `window` + `table` (+ topic/`service-group`). Daily, hourly,
5-minute, weekly are configs, not classes — refines dotnet's "two bean classes" (logged to dotnet). A
`SummaryBeanFactory` (one per entity, CDI-discovered) builds instances from a `BeanConfig`. `WindowSize`
supports `5min` / `Nmin` (multiple of 5) / `hourly` / `daily` / `weekly` (Monday-start ISO week, identifies
week 1–52/53) / `monthly` / `yearly`. The week bucket is stored as the week-start datetime in `tup_starttime`
(a datetime, uniquely identifying the week); if an integer week-number column is wanted later, that's a schema
addition to coordinate.

### 12c. Provisional surface (updated by §13)
The blob/topic/DDL are now PINNED by dotnet (see §13). `MediationContext` shape stays provisional but is not
load-bearing for the CDR build.

### 12d. Beans organised as `summarybeans/<category>/`, one class per window — RATIFIED (user, 2026-06-28; supersedes 12b's window-as-config)
The service is a catalog of summary/counter generators across categories (call/voice today; packet-flow,
session, voip, video flow later). So beans are organised by **category package**: `summarybeans/<category>/`.
Within a category, a shared base bean holds the decode/build/map logic and **each time window is its own
`@Singleton` class** that fixes only `window()` — `summarybeans/call/HourlySummary.java`,
`summarybeans/call/DailySummary.java` over `CallSummaryBean` (the entity is `CallSummary`, renamed from
`CdrSummary`). This makes the catalog browsable ("open the folder, see every counter the category emits").

The category package holds **only the high-level window beans** (constructor / startup + the bean's processing
surface); the complexity is nested (user, 2026-06-29): `<category>/internal` for the shared `CallSummaryBean`
machinery + `CallSummaryBuilder` + `CdrBlobMapper`, and `<category>/model` for the `CallSummary` row entity +
the inbound `Cdr`/`Customer`/`CdrBlobEntry` blob shapes. A reserved `summarybeans/sms` sibling (package-info
only) marks where the next category lands — same shape. Tests mirror it: `…/call/internal/CallSummaryBeanTest`,
`…/call/model/CallSummaryTest`, with the cross-window behaviour suite at `…/call/MultiWindowAggregationTest`.

Wiring change: the per-entity `SummaryBeanFactory` + `BeanConfig` are **removed**. `SummaryBootstrap` now
**CDI-discovers** all `SummaryBean`s (`@Any Instance<SummaryBean<?>>`) and activates the ones whose `name()` is
in `summary.enabledSummary`; `table`/`service-group`/`context` come from `summary.beans.<name>`, the `window` is
the class, and `entity_type` is pinned `"cdr"` in the base. Tradeoff vs 12b: one instance per window class (you
can't spin up two hourly beans of the same category from YAML alone — that'd be a second class). The user chose
this for the browsable per-window catalog. The load-once / segmented-write / one-txn / exactly-once rulings
(§1–3, §13) are unchanged. Build green (33 unit + 1 MySQL IT) after the move.

### 12e. Builder convention — every bean ships a fluent builder under `beans/` — RATIFIED (user, 2026-06-30)
A library user assembles any bean the same brief way:
`XxxBuilder.create(mapper).table(..).serviceGroup(..).context(..).build()`. The convention is **enforced by a
base class**: `beans/SummaryBeanBuilder<T,B>` (recursive self-type) supplies the shared fluent chain and a
`final build()` that validates the common invariant (a target table is required); a new bean joins simply by
shipping a `XxxBuilder extends SummaryBeanBuilder` in `beans/`. `beans/` is the **public API** (the high-level
entry points embedders import) — distinct from `bean/spi` (the contracts) and `summarybeans/<category>` (the
impl). **Supplement, not replace** (user choice): the running Quarkus service still wires beans via CDI +
`summary.beans.<name>` YAML + `SummaryBootstrap` discovery; the builders are the programmatic path (embedders +
tests — `CdrTestSupport` builds daily/hourly through them). Build green (46 unit + 2 MySQL IT).

### 12f. Table name DERIVED as `sum_voice_<window>_<table-suffix>` — RATIFIED (user, 2026-06-30; revised same day)
The bean's target table is **derived** as `sum_voice_<window token>_<table-suffix>` (e.g. `sum_voice_day_3`).
Window tokens `day`/`hr`/`5min`/`week`/`month`/`year` via `WindowSize.tableToken()`; prefix `sum_voice` owned by
the call category in `CallSummaryBean`. The **suffix is config** (`summary.beans.<name>.table-suffix`, a string)
— it selects one of the **pre-provisioned** sets (`sum_voice_{day,hr}_{1,2,3}`), created up front WITH all
partitions because a 1000-partition table is far too slow to create on the fly; the bean only POINTS at an
existing table. `service-group` and `table-suffix` are now BOTH required on the builder (service-group FILTERS
records, table-suffix NAMES the table — fully decoupled); YAML drops the full `table:`. *(Supersedes the same-day
first cut that derived the suffix from the service-group NUMBER — it invented names like `sum_voice_day_10`
needing fresh partitioned tables; wrong.)* No forced divergence from billing: set the suffix to match the
existing tables (`"3"` → `sum_voice_day_3`, or `"03"` to match billing's legacy `_03`). Build green (48u + 2 IT).

## 13. Input = MySQL outbox + per-bean offset + Kafka-ping — RATIFIED (v3, supersedes §4 and §11)
Per `summary-service-outbox-design.md` (user + architect + dotnet). MySQL+Kafka can't be atomic (dual-write)
and full window recompute is impossible (millions of cdrs), so:

- **Outbox:** billing writes ONE `summary_affected{id, entity_type, data}` row per ~1000-cdr batch inside its
  cdr transaction; `data` = `base64(gzip(UTF-8 JSON array of {Cdr, Customer}))` (PINNED). Kafka topic
  `cdr_summary_ping` carries only a wakeup (payload informational). The engine reads the blob now.
- **Exactly-once per bean:** each bean writes its summaries AND advances its `summary_offset.last_offset` in
  ONE MySQL transaction. The Kafka offset is NOT progress. Crash before commit → offset unchanged →
  reprocessed clean. This SUPERSEDES §4 (commit-Kafka-offset-after-DB) — the gap that needed it is gone.
- **Parallel beans, shared context:** daily & hourly are separate workers (own offset, own tx), sharing a
  read-only `MediationContext` loaded once from config-manager (unchanged; summary is just a client). The
  context is NOT load-bearing for the CDR build (the blob carries already-rated cdr+chargeable) — wired lazily
  per the user directive; reconcile its fields if a future build needs them.
- **Reaper:** option A (shared outbox, billing writes once). A scheduled task deletes `summary_affected` rows
  with `id ≤ min(last_offset)` across the entity's ACTIVE beans; a bean with no offset row counts as 0 (nothing
  deleted until every bean has progressed). Removing a bean from `enabledSummary` permanently should delete its
  offset row so it stops blocking the reaper (architect Q2 — proceeding on this default; ruling pending).
- **Correction:** the "recompute the window from the cdr table" path (§11) is DROPPED — impossible at scale;
  summarisation is incremental-only. `MergeMode.OVERWRITE` stays in the cache for a future targeted correction.
- **Topology:** single active instance per tenant (architect Q1, ratified by the user). The per-bean
  `summary_offset` (+ optional FOR UPDATE) is the HA hook if multi-instance is needed later.

### 13a. PINNED by dotnet (no longer provisional)
blob codec + `{Cdr,Customer}` field list; ping topic `cdr_summary_ping`; outbox DDL (`summary_affected` +
`summary_offset.last_offset`, billing-core `src/Billing.Data/Sql/summary_outbox.sql`); `sum_voice` 47 cols
(matches `CallSummary`), RANGE-by-month partitions. The table name is now DERIVED `sum_voice_<window>_<table-suffix>`
where the suffix is config (§12f) — point it at the existing `_03`/`_02` (or `_3`/`_2`) tables, so no forced
divergence. The summary side decodes the C# PascalCase blob case-insensitively. Still open: architect Q2 (reaper deregister rule); `MediationContext` shape.
