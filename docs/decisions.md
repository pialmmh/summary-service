# summary-service — architect decisions

The rulings this v1 is built on. Items marked **RATIFIED** come from the design brief
(`CLAUDE.md` + `/tmp/shared-instruction/summary-service-design.md`); the rest are this stream's calls.

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

## 11. Correction path — designed, increment shipped in v1
`MergeMode.OVERWRITE` and the `correction-topic` config exist; the cache supports overwrite. The dedicated
correction consumer (recompute a window from source-of-truth and overwrite) is a thin follow-up — v1 delivers
the increment pipeline as the brief's first deliverable specifies.
