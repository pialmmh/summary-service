# summary-service — project brief (for the agent that builds this)

> You were started via `/start-dev summary-service`. Read this file, then **read the AUTHORITATIVE design at
> `/tmp/shared-instruction/summary-service-outbox-design.md`** (dotnet/billing-core, **user-ratified 2026-06-27**;
> architect-ratified) **and the architect RULINGS at
> `/tmp/shared-instruction/summary-service-architect-rulings-outbox.md`** (answers your Q1–Q6: single-active
> topology, reaper/deregister rules, MediationContext NOT load-bearing for the CDR bean v1, ping broadcast,
> entity_type taxonomy, summary_offset ownership + head-init), then load `/code-convention`. Coordinate on the
> `summary-service` dev channel.

> ⚠ **DESIGN SUPERSEDED (2026-06-27) — follow the outbox-design doc, NOT the old Kafka-events model below.**
> The event source is now a **MySQL transactional OUTBOX** (`summary_affected`, written inside billing's cdr-batch
> transaction) + **per-bean MySQL offset** (`summary_offset`) + **Kafka-as-ping** (no payload). This replaces
> *"publish rated CDRs to a Kafka topic"* (MySQL+Kafka can't be atomic — dual-write) **and** *"correction =
> recompute the window from the cdr table"* (a daily window is millions of cdrs — impossible). Summarisation is
> **INCREMENTAL-ONLY**; **exactly-once per bean** = each bean writes its summaries **and** advances its own offset
> in **ONE MySQL transaction**. Daily/hourly are **separate parallel beans** (own worker/offset/tx) sharing only a
> **read-only MediationContext** loaded once from config-manager. **config-manager is UNCHANGED** (summary is just
> another client of the same apis). A **reaper** deletes outbox rows once all active beans pass them. The engine
> invariants further down (load-windows-once, segmented insert, one-txn-per-batch, incremental merge) are
> UNCHANGED — they now read the outbox blob. **Where this brief disagrees with the outbox doc, the outbox doc wins.**

## Mission
A standalone **Java 21 / Quarkus** service that generates **time-windowed counters/summaries** for any
event stream — CDRs today, any log/entity later (softswitch/BSC/MSC-style performance counters). It OWNS
summarisation; billing-core (and other producers) hand off events via a **MySQL transactional outbox** (see the
SUPERSEDED banner above). This **decouples summary from billing-core** → summaries are **eventually consistent**
(outbox-fed, incremental), which is fine for derived roll-ups — state it explicitly in the docs.

## Port from billing-core (`pialmmh/billing-dotnetcore`, proven .NET → Java)
| billing-core | role |
|---|---|
| `Summary/CdrSummaryContext` | load-windows-involved → merge → write orchestration |
| `Summary/Cache/{AbstractCache, SummaryCache}` | change-tracking + Add/Substract merge |
| `Sql/{BatchSqlWriter, CollectionSegmenter}` | segmented MySQL extended (multi-row) inserts |
| `Data/MySqlCdrBatchRunner` | the ONE top-level commit/rollback |
| `sum_voice_*` schema + the CDR bean | the reference summary implementation |

## Core pipeline (per bean, per batch) — keep these invariants
1. **Poll Kafka**: a batch of events (default 1000, configurable).
2. **Cache existing**: compute the **windows involved** (distinct dates + hours the batch touches), fetch their
   existing rows in ONE query. **CRITICAL: load all involved windows once — loading per-event double-counts.**
3. **Merge**: increment/decrement the cached windows by the batch's events (`SummaryCache.Merge`).
4. **Write**: insert-or-update changed windows via **segmented multi-row insert** (`BatchSqlWriter` + segmenter).
5. **Atomic batch**: ONE top-level transaction; rollback the whole batch on any failure; **no commit/rollback in
   inner classes** — the entry owns the single commit (`MySqlCdrBatchRunner` rule). MySQL only for now.

## Summary beans (pluggable, YAML-activated)
`spi/SummaryBean` — one counter/summary impl (CDR per-day and per-hour = beans). Each declares: event type +
Kafka topic; time window(s) (day/hour, generalisable to 15-min/week/month); dimensions (group-by:
switch/partner/route/prefix); counters (calls/duration/cost/tax …) + each event's delta; target table
(e.g. `sum_voice_day_03`). Switch on/off + parameterise (batch size, topic, DB) in YAML.

## Increment vs Correction
- **Increment** (normal): new events add to the window.
- **Correction** (manual): dump event(s) to a **separate topic**; that consumer **fully recomputes** the window
  from source-of-truth and **OVERWRITES** it (needs read access to the source events). Naturally idempotent.

## Architect ratifications / decisions (apply these)
1. **load-merge-write is the core** (handles increment + decrement + overwrite uniformly) — NOT bare
   `ON DUPLICATE KEY UPDATE` (no decrement/overwrite, not idempotent). Ratified.
2. **Increment idempotency**: at-least-once Kafka + increment double-counts on redelivery. v1 strategy:
   process the whole batch in one DB txn and **commit the Kafka offset only AFTER the DB commit**; if a crash
   redelivers, the correction/overwrite path is the repair. Add per-event/offset dedup only if double-counts
   are observed. (Pick + document; revisit with the architect if you need stronger guarantees.)
3. **MySQL only**; **config = routesphere-like** (tenants.yml + per-tenant profile yml, config-manager-fed,
   Kafka reload debounce).
4. **Dynamic UI-defined beans = later phase** — design the bean registry so a new bean can hot-start a
   consumer/worker without restart, but don't build the UI now.

## First deliverable (v1)
1. Quarkus skeleton + routesphere-like config + the `SummaryBean` SPI + registry.
2. The **CDR day/hour bean**: consume the rated-CDR Kafka topic (dotnet provides it) → windows-involved load →
   merge → segmented extended-insert into `sum_voice_*` → ONE transaction per batch.
3. Tests: a batch touching N windows loads them once; increment + decrement correctness; one-txn rollback on
   failure; redelivery handling per the chosen idempotency strategy.
4. Build clean, tests green. Don't deploy; coordinate the cutover (billing-core drops its summary write) with
   the architect + dotnet.

## House rules (binding)
- SRP, small files, short named orchestrators; RULE ONE logging; SECRETS via OpenBao (DB creds), not env/committed.
- Java 21. Do not git push until the user asks.
