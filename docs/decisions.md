# summary-service — architect decisions

The rulings this service is built on. Items marked **RATIFIED** come from the design brief
(`CLAUDE.md` + `/tmp/shared-instruction/summary-service-design.md`); the rest are this stream's calls.

> **v2 redesign (2026-06-27) — see §12.** The generic axis moved from the EVENT to the typed summary ENTITY
> `T` per `summary-service-entity-redesign.md` (dotnet, ratified + user directive). The pipeline rulings
> below (§1–4, §6) are unchanged and still hold; the type-model rulings are updated in §12. The v1
> declarative `SummaryRow`/dimension-extractor model is superseded.

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

## 8. Secrets via OpenBao — RATIFIED (mechanism deferred, rule honored now)
No secret is ever committed or read from an env var. The committed profile yml holds the datasource **without
a password**. At cutover the `quarkus-vault` extension (pinned to the version aligned with this Quarkus 3.26
build) maps `kv/summary-service/mysql` → `quarkus.datasource.password`. The extension is intentionally NOT on
the v1 classpath: the only cached versions (4.1/4.2) break augmentation on Quarkus 3.26, and until the OpenBao
AppRole is provisioned it would just add a broker dependency to the build. The secrets rule is satisfied
regardless — nothing sensitive is in git. The local dev MySQL password used by the integration test is a
dev-only convenience credential (overridable via `-Dsummary.it.mysql.password`), not a production secret.

## 9. Dynamic UI-defined beans — LATER (RATIFIED)
The registry already supports runtime `start`/`stop` (a bean hot-starts its own consumer + worker thread with
no restart). The UI to define beans is a later phase; v1 registers the compiled beans at startup.

## 10. PROVISIONAL contract — pending billing-core (dotnet)
`RatedCdrEvent` (the consumed event) and `sum_voice.provisional.sql` (the target tables) are the architect's
placeholders so the pipeline + tests have something concrete. A handoff is posted on the `summary-service`
channel asking dotnet to pin: the topic name(s), the rated-CDR event schema, and the real `sum_voice_*` DDL.
When they land, reconcile `RatedCdrEvent` + `CdrVoiceSummaryBean`'s extractors + the DDL — nothing else
changes.

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

### 12b. One bean per entity + configurable window in YAML — RATIFIED (user directive)
One `SummaryBean<T>` class per entity (e.g. `CdrSummaryBean`); each `summary.enabledSummary` entry is a
distinct CONFIGURED instance differing only by `window` + `table` (+ topic/`service-group`). Daily, hourly,
5-minute, weekly are configs, not classes — refines dotnet's "two bean classes" (logged to dotnet). A
`SummaryBeanFactory` (one per entity, CDI-discovered) builds instances from a `BeanConfig`. `WindowSize`
supports `5min` / `Nmin` (multiple of 5) / `hourly` / `daily` / `weekly` (Monday-start ISO week, identifies
week 1–52/53) / `monthly` / `yearly`. The week bucket is stored as the week-start datetime in `tup_starttime`
(a datetime, uniquely identifying the week); if an integer week-number column is wanted later, that's a schema
addition to coordinate.

### 12c. Provisional surface (updated)
`RatedCdrEvent` (now the full builder input set) + `sum_voice.provisional.sql` (all 47 columns) remain
PROVISIONAL pending dotnet's still-owed items: real `CREATE TABLE` (with partitions, SG10 `*_03` / SG11
`*_02`), the pinned `RatedCdrEvent` field names mapped to `CdrSummaryBuilder`, and the final topic name(s).
Reconcile `RatedCdrEvent` + `CdrSummaryBuilder` + the DDL when they land — nothing else changes.
