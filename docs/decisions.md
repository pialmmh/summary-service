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
`SummaryEngineTest.involved_windows_are_loaded_once_not_per_event`.

## 3. One transaction per batch — RATIFIED
The drain (`OutboxReader.drainOnce`, via `JdbcUnitOfWork`) owns the single commit/rollback (the
`MySqlCdrBatchRunner` rule; the interim `BatchRunner` was folded into the reader per the outbox plan). The
engine, cache, and store NEVER commit or roll back. Any failure rolls the whole batch back. Tested unit
(`OutboxReaderTest.a_write_failure_rolls_back…`) and against real MySQL
(`OutboxConsumerIT.a_failed_drain_rolls_back_completely_and_the_redelivery_counts_once`).

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
`MergeMode.OVERWRITE` exists and the cache supports overwrite (clone the recomputed entity, keep the loaded
id — and, since 2026-07-02, correctly replace a row first INSERTED in the same batch). There is NO
`correction-topic` config and no correction consumer yet: §13 dropped full-window recompute as impossible at
scale, and the pinned producer contract carries no correction rows — wiring a correction INPUT needs a
contract addition ratified with dotnet (see §13c).

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
(matches `CallSummary`), RANGE partitions by **DATE on `tup_starttime`** (per the DBA — one partition per day,
~1000 up front; NOT by month). The table name is now DERIVED `sum_voice_<window>_<table-suffix>`
where the suffix is config (§12f) — point it at the existing `_03`/`_02` (or `_3`/`_2`) tables, so no forced
divergence. The summary side decodes the C# PascalCase blob case-insensitively. Still open: architect Q2 (reaper deregister rule); `MediationContext` shape.

### 13b. Partition-pruning WHERE on UPDATE/DELETE (delta #1 — DONE)
The `sum_voice` tables are RANGE-partitioned **by date on `tup_starttime`** (§13a). `id` is the PK but NOT the
partition key, so `UPDATE/DELETE … WHERE id=<id>` alone forces MySQL to probe **every** date partition (~1000).
The engine now appends the row's bucket to the predicate — `… WHERE id=<id> AND tup_starttime='<bucket>'` — so
MySQL prunes to the single day partition. The bucket value is the loaded row's own `tup_starttime`, which for a
daily bean is the day-start and for an hourly bean an hour within that day — both map to the same date partition,
so pruning is exact and never excludes the target row (DATETIME, no fractional seconds → literal equality holds).
Mechanics: `SummaryEntity.bucketLiteral()` renders the literal; `SummaryEngine` passes `bean.bucketColumn()` into
`SummaryCache`, which builds the WHERE. This restores billing-core's partition-pruning behaviour (billing did the
same, keyed on its own date column). Correctness was never at risk (`id` is unique); this is a pure read-amplification fix.

### 12g. Config-instantiated bean instances (SG coverage) — 2026-07-02
The per-window catalog (§12d) fixes ONE name per class, so one class covers one service group — but legacy
billing summarised SG10 AND SG11 in parallel, and with the reaper an un-covered SG is **unrecoverable** (records
skipped while the offset advances, then the rows are deleted). Supplement: an `enabledSummary` name with **no
catalog class** but a `summary.beans.<name>.window` key is materialised by `SummaryBootstrap` through
`summarybeans/call/CallSummaries.forWindow(..)` as an EXTRA call-bean instance under its own name — own offset
bookmark, own worker, own derived table. Dev profile now runs 4 beans: SG10 day/hr (suffix `3`, catalog classes)
+ SG11 day/hr (suffix `2`, config-instantiated). v1 assumes the `call` category; a `category:` key selects among
factories once a second category exists. **Cutover duty:** every SG billing summarises must have beans here, and
each SG's suffix must point at ITS legacy set (SG10→`03`, SG11→`02`) — nothing can enforce that pairing in code.

### 13c. Audit hardening batch — 2026-07-02 (3-agent audit: legacy diff / scope diff / bug hunt)
All engine-math parity with legacy re-confirmed (41-point diff, incl. the producer side). Landed fixes:
- **Exactly-once under stop/start** (was CRITICAL): the drain loop moved into `OutboxWorker` (checks its stop
  flag between per-tx steps); `SummaryBeanRegistry.stop()` keeps the entry until the thread is confirmed dead
  and `start()` refuses while it is alive — two workers can never share one bean's offset. A worker killed by an
  `Error` logs FATAL, keeps its entry (reaper keeps counting it → outbox preserved), `start()` respawns over it.
- **OVERWRITE of a same-batch insert** silently wrote the stale values (the `inserted` map kept the old object) — fixed.
- **SUBTRACT** negated the caller's delta in place — now negates a clone (a delta can feed day AND hour caches).
- **Head-init** (per the architect ruling): `registry.start()` seeds `summary_offset` at MAX(outbox id) via
  `INSERT IGNORE … SELECT` before the first drain — a late-enabled bean starts from NOW instead of consuming
  nondeterministic reaper residue; also closes the reaper-vs-new-bean race.
- **Poison-row quarantine**: a row that fails DECODE/BUILD (deterministic on data — SQL failures never qualify)
  `quarantine-after` (default 8) consecutive times at the head is copied to summary-owned `summary_deadletter`
  (per bean, blob preserved) and skipped in the same tx — one bad blob can no longer wedge a bean forever and
  block the reaper for the whole entity. Clean rows before a poison row commit as a prefix. Worker retries back
  off linearly to 60s with an escalating ERROR count. `OutboxCodec` caps decoded blobs at 64 MiB (poison, not OOM).
- **Key canonicalization**: the builder scales `tup_customerrate`/`tup_supplierrate` to the column's 6dp
  (HALF_UP, what MySQL stores) and clips key strings to their VARCHAR widths BEFORE the tuple key is taken —
  otherwise a 7-dp rate keys differently from its own reloaded row (duplicate window → `uq_tuple` wedge, or
  two rows that later poison `populateExisting`).
- Smaller: connection no longer leaks when `setAutoCommit` fails mid-outage; `advanceOffset` upsert drops the
  deprecated `VALUES()`; malformed profile yml logs + boots instead of crashing the ConfigSource; `table-suffix`
  validated `[A-Za-z0-9_]+` at build/activation (it lands in Statement-built SQL with `allowMultiQueries` on);
  one bean's bad config no longer breaks every other bean's CDI lookup (`Instance.handles()`);
  `PingListener`/`OutboxReaper` now stopped by a `@PreDestroy`.
- **`max-rows-per-tx` default 50 → 1** (user-ratified): billing already packs ~1000 cdrs per outbox row, so the
  natural drain unit is one row = one tx (smallest tx, one-row poison blast radius); raise it only to amortise
  hot-window catch-up after downtime.
- **Pinned assumption (documented, not code):** outbox ids must become visible in commit order — holds because
  billing is a SINGLE serial producer (one mediation pipeline). A concurrent-producer future needs a
  gap-tolerant read before `id>offset` stays safe.
- Tests: 60 unit + 6 MySQL IT (reaper min/delete, crash-redelivery replay, quarantine, head-init now proven
  against real MySQL). Still open: correction-path INPUT (needs a dotnet-ratified contract addition), Q2
  deregister rule, `MediationContext` real shape.

### 13d. CONTRACT v2 cut — 2026-07-02 (work order + dotnet answers A1–A6 + user directive; one coordinated cut)
The billing work order (`/tmp/shared-instruction/summary-service-work-order.md`) + the answered Q1–Q6
(`…questions-for-dotnet.md`) + the user's self-provisioning directive, all landed together:
- **Blob v2 (§1a, BREAKING at the producer; tolerant here):** entries are now `{Cdr, Chargeables:[ALL legs]}`.
  `Customer` → `Chargeable` (it is the acc_chargeable row) with the full leg field set; `CdrBlobEntry` reads
  BOTH shapes PERMANENTLY (A3: v1 `{Cdr, Customer}` decodes as a single-element leg list — never depend on a
  drained outbox). The voice bean picks the customer leg (`assignedDirection == 1`, else first — billing's own
  `Entry.Customer()` rule) then filters by SG as before.
- **`op` plumbing (§1b):** `summary_affected.op ENUM('add','subtract') DEFAULT 'add'` (absent/NULL → add, A3).
  `OutboxRow(id, op, data)`; the drain runs the ENGINE PER OUTBOX ROW in id order with the row's `MergeMode` —
  one row = one packed billing batch, so load-once holds at exactly the legacy batch granularity, and a later
  row re-reads windows an earlier row wrote in the same tx (subtract-behind-add is correct). A correction =
  `subtract`(OLD)+`add`(NEW), consecutive ids, one billing tx; half-applied-until-repair ACCEPTED (A2), no
  `op='overwrite'` ever, `correction_id` comes later with the correction producer (we read columns by name).
- **SUBTRACT-on-missing = poison (A1=B, billing's keep-throw withdrawn):** `MissingWindowException` (thrown
  pre-flush — the row wrote nothing) joins the decode/build failures in the quarantine policy; after
  `quarantine-after` consecutive head-row failures the row is dead-lettered COMPLETE and un-applied (the
  repair ticket). SQL failures still never quarantine. DLQ renamed `summary_deadletter` → **`summary_affected_dlq`**.
- **SG10 faithfulness (§2):** `Cdr` +5 fields; the SG10 branch now stamps `tup_matchedprefixcustomer` from
  `cdr.MatchedPrefixCustomer` (the chargeable's prefix was legacy-overwritten dead code), `vat = ZAmount` +
  `tup_vatcurrency='BDT'`, `longDecimalAmount1 = CostAnsIn`, `longDecimalAmount2 = AdditionalSystemCodes`,
  `intAmount1 = AdditionalPartyNumber` — the last two are JSON STRINGS (legacy repurposed free-text columns):
  parsed, null/blank/unparsable → 0 + warn (A4). Both SG branches gained the legacy `ChargingStatus != 1`
  early-return: a non-charged call gets counts/durations + its pre-charge stamps only.
- **Suffixes (§3, A6):** SG10 → `03`, SG11 → `02` — billing's LIVE sets, recorded here as the decision (no
  greenfield `_3`). Java billing writes NOTHING to sum_voice_*; REQUIRED cutover step: stop the legacy .NET
  summary jobs before going live on the same tables.
- **Chargeable category (§4, net-new):** `summarybeans/chargeable/` — `ChargeableSummary` (key = sg/sf/
  direction/product/uom/prefix + `tup_transactiontime`; measures totalcount + 14 amounts, `multiply` scales
  ALL — no legacy quirk), base bean consumes the SAME `cdr` stream (blob model/mapper reused from the call
  category — one pinned contract), EVERY leg of EVERY entry becomes a row (no SG/direction filter; direction
  is a key column). Tables FIXED per window: `sum_chargeable_day`/`_hr`, DECIMAL(20,8) (billing rounds
  HALF_EVEN at 8dp; the chargeable KEY has no decimal column, so 6dp key canonicalization stays voice-only).
  Bucket = the leg's own `transactionTime` (always present, == StartTime today, authoritative if that ever
  diverges — A4). `beans/` restructured for the second category: `SummaryBeanBuilder` (generic root:
  mapper+context+final build→validate) + `CallBeanBuilder` (the voice layer requiring SG+suffix) +
  `Daily/HourlyChargeableSummaryBuilder` (no required fields). §12e's convention unchanged for users.
- **Self-provisioning DDL (user directive, supersedes A5's manual-deploy split):** `SummaryBean.tableDdl()` —
  each bean CREATEs its table IF NOT EXISTS at worker start, carrying the FULL daily partition set inside the
  CREATE (house rule; horizon via `summary.ddl.partition-start`/`partition-days`, default current-year Jan 1 ×
  730 + pMAX; partitioned form uses PK `(id, bucket)` as MySQL requires). Service-level: `summary_offset`,
  `summary_affected_dlq` (+ a dev copy of `summary_affected`) ensured once per process. The ONLY remaining ops
  item is the GRANT (CREATE/ALTER on the tenant schema).
- **Q2 RESOLVED (work order §5.2):** the reaper watermark runs over the CONFIGURED (registered) beans, not
  just running workers — a hot-stopped bean's unread rows survive until it catches up or its offset row is
  explicitly decommissioned. Plus: `readOffset … FOR UPDATE` (A1c belt-and-braces for an accidental second
  instance) and entry-level null guards in every buildBatch (skip + count + warn, never NPE the drain).
- Producer-side guarantees now relied on (A/§1c): outbox ids are COMMIT-ordered (GET_LOCK held across commit)
  — closes the §13c serial-producer assumption at the producer; chargeables carry real ProductId/uom;
  SfA2Z supplier TaxAmount1 is null (faithful).
- Tests: **77 unit + 8 MySQL IT** (subtract correction arithmetic, chargeable self-provision with REAL
  partitions + rollup, both proven against MySQL). Still open: `MediationContext` real shape (provisional,
  not load-bearing); the correction PRODUCER (billing's next round, with `correction_id`).

### 13e. MediationContext — CLOSED as "same as billing, typed on first use" (2026-07-02)
Nothing was wrong with it; the "provisional shape" flag was a deferred TYPING decision, now pinned:
- We already load THE SAME context billing loads — same config-manager api (`POST /get-specific-tenant-root`),
  same tenant chain, loaded once at activation, shared read-only (`ContextRegistry`).
- NO summary bean reads a field from it, and none should today: the legacy summary stamps
  (`SgDomOffnetOut/In.SetServiceGroupWiseSummaryParams`) read only cdr + chargeable — MediationContext is a
  MEDIATION/RATING input on billing's side, and its results are already stamped on the cdr before the outbox.
  The blob arrives post-mediation. Typing it now = dead code that drifts.
- Therefore ours holds the payload RAW. The moment a future bean needs a context lookup (e.g. a partner-name
  dimension not on the blob), the typed shape is ADOPTED from billing-core java's
  `com.telcobright.billing.mediation.context.MediationContext` (copy or shared module — decide then) —
  never invented on this side. Until such a consumer exists, this item is CLOSED, not open.

## 14. Generic input axis: SummaryGenerator<I,T> + per-bean mode (incremental | replace-prototype) — user directive 2026-07-03
The user's model — `Summary<T>` with `T = the INPUT type` (e.g. a cdr), a base class holding the generation
METHOD PROTOTYPES with per-implementation logic, shared append (add/subtract), and two api modes — mapped onto
what exists honestly: the engine/cache/append/window machinery was ALREADY generic and reused (it is typed on
the summary ENTITY; voice + chargeable both ride it unchanged). What was missing and is now formalized:
- **`bean/spi/SummaryGenerator<I, T extends SummaryEntity<T>>`** — the input-typed base: abstract
  `generateOne(I, window)` (the one prototype an implementation writes; logic differs per summary kind) +
  shared `final generate(batch, window)`. Append stays the reusable entity contract (`merge`/`multiply`)
  applied by the engine's MergeMode. Category generators: `CallSummaryGenerator` (`I = RatedCall(cdr,
  customerLeg)` — the legacy (cdr, acc_chargeable) signature as a record) and `ChargeableSummaryGenerator`
  (`I = Chargeable`); the beans' `buildBatch` now assembles inputs (decode/pick/filter) and delegates
  generation through the generator. Naming: the user's `CdrSummary<Cdr>` ≈ `CallSummaryGenerator` — the
  `*Summary` names stay on the ENTITIES (§12d catalog), the generator suffix marks the input-typed contract.
- **`bean/spi/SummaryMode` + `SummaryBean.mode()`** — the per-bean SETTING (yml `summary.beans.<name>.mode`,
  default `incremental`). Cdr processing runs INCREMENTAL; ALL outbox polls are incremental by definition.
- **REPLACE = PROTOTYPE ONLY (per the user):** `SummaryEngine.replaceWindows(bean, fresh, store, segment)`
  documents the intended semantics (caller supplies ALL inputs of the window(s); drop the windows' rows;
  write the fresh aggregation — an overwrite, naturally idempotent) and THROWS
  `UnsupportedOperationException` until implemented. The drain routes a `mode: replace` bean into it, so a
  misconfigured bean fails LOUDLY every poll — a config fault, deliberately never quarantined/dead-lettered.
- Tests: 80 unit + 8 MySQL IT green (mode default, prototype throw, loud-not-quarantined routing).

## 15. DESIGN CLOSED — cutover seamlessness for cdr + ledger (user priority, 2026-07-03)
The design is finished when both summary streams that exist in production keep working through cutover.

### 15a. CDR summaries — OURS at cutover; seamless BY CONSTRUCTION
- **Mid-period cutover is safe because load-merge-write MERGES ONTO legacy's partial windows:** the drain loads
  the bucket's existing rows — including rows legacy .NET wrote before the cutover moment — and UPDATEs them
  (id + bucket). A day/hour window half-filled by legacy finishes correctly under us; no duplicate windows, no
  reset counters. This is why key faithfulness mattered: the tuple our builder produces must equal the tuple
  legacy stored (§2 field repairs + 6dp/width canonicalization make it so).
- Tables: billing's LIVE sets (SG10 → `sum_voice_{day,hr}_03`, SG11 → `_02`); our AUTO_INCREMENT inserts
  coexist with legacy-written ids (we never touch legacy id ranges; UPDATE targets loaded rows only).
- Scope: SG10 + SG11 — the pinned tenant's summarising service groups (legacy's map is per-tenant
  `IServiceGroup.GetSummaryTargetTables()`; other tenants' SGs/sets `_01/_04/_05/_06` are out of scope until
  such a tenant is onboarded — then: one config-instantiated bean per SG, §12g, zero code).
- REQUIRED cutover order: (1) apply the GRANT; (2) stop legacy .NET summary jobs; (3) start billing's outbox
  producer + `summary.autostart=true`. Between (2) and (3) the outbox buffers — nothing is lost (head-init
  only affects beans enabled LATER, not this first start... the first start seeds at the then-current head, so
  cut (3) BEFORE billing writes its first outbox row, or set the offset rows to 0 explicitly at first deploy).
- Residual documented risk: MySQL ci collation vs our case-sensitive keys — a legacy row whose dimension
  differs only by case from a fresh build yields two rows (real prod tables carry no uq_tuple); reports
  grouping by the tuple still sum correctly. Accepted; no code.

### 15b. LEDGER summaries — stay LEGACY/ACCOUNTING-owned; land here later as a THIRD category
Legacy's ledger summary is `acc_ledger_summary(idAccount, transactionDate, AMOUNT)` fed from
`acc_transaction` (double-entry, running balances) inside the ACCOUNTING job (`AccountingContext` — the same
SummaryCache machinery, key `(glAccountId, transactionTime.Date)`); invoicing reads it
(`InvoiceGenerationByLedgerSummary`). Java billing dropped balances/transactions FINAL — so there IS no
transaction stream on the new stack, and summary-service MUST NOT derive ledger rows from chargeable legs
(that would re-implement the double-entry accounting rules in the wrong service; glAccountId on a leg is one
side of the entry only).
**Ruling:** the cdr-summary cutover does not touch accounting — legacy .NET accounting/invoicing keeps
producing `acc_transaction` + `acc_ledger_summary` exactly as today (seamless because we change nothing in
that pipeline). When billing ports accounting and emits transactions into the outbox (a new
`entity_type='transaction'`), the ledger rollup lands here as `summarybeans/ledger/` with ZERO engine work —
the framework-closure proof: `LedgerSummary` entity (key idAccount + transactionDate bucket; measure AMOUNT),
`LedgerSummaryGenerator extends SummaryGenerator<Transaction, LedgerSummary>`, a Daily bean, tableDdl.

### 15c. What "finished" means (the framework is closed under extension)
- new INPUT kind → a new category: entity + generator + window beans (+ builders, + tableDdl) — no core change
  (chargeable proved it; ledger is the next worked example);
- new WINDOW of a category → one subclass; new INSTANCE (another SG) → yml only (§12g);
- fold modes: INCREMENTAL live (all outbox polls), SUBTRACT via `op` (corrections), REPLACE prototype (§14);
- exactly-once, head-init, poison DLQ, reaper watermark, self-provisioning — all category-agnostic.
