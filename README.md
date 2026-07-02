# summary-service

A standalone **Java 21 / Quarkus** service that generates **time-windowed counters/summaries** for any event
stream — CDRs today, any log/entity later (softswitch/BSC/MSC-style performance counters). It **owns**
summarisation; billing-core hands off each rated-CDR batch via a **MySQL transactional outbox**, and
summary-service consumes it **incrementally**. Summaries are **eventually consistent** (outbox-fed) — fine for
derived roll-ups.

It ports billing-core's proven **load-merge-write** engine (.NET → Java) over a typed summary **entity**, and
consumes the outbox **exactly-once per bean**.

## Status — built, tests green (outbox consumer)

- Input is the MySQL outbox `summary_affected` (base64(gzip(JSON)) batches of `{Cdr, Customer}`); Kafka
  (`cdr_summary_ping`) is only a wakeup. Per-bean bookmark `summary_offset.last_offset`.
- **Exactly-once per bean**: each bean writes its summaries **and** advances its offset in ONE MySQL
  transaction → no double-count on redelivery. Daily & hourly are **separate parallel workers** sharing a
  read-only `MediationContext` (loaded once from config-manager). A **reaper** trims consumed outbox rows.
- The ratified engine (load-windows-once → merge → segmented insert → one-tx) + the `CallSummary` entity
  (faithful 1:1 port of `AbstractCdrSummary`, 47 cols) are **unchanged** — they read the blob now. UPDATE/DELETE
  carry `id + tup_starttime` so MySQL prunes to the one date partition (§13b).
- **Hardened (§13c)**: stop/start can never run two workers on one offset; a poison blob is quarantined to
  `summary_deadletter` after N consecutive failures instead of wedging the bean + reaper; a late-enabled bean
  **head-inits** its offset (starts from NOW — no partial backfill); key decimals/strings are canonicalized to
  what MySQL stores before keying. Both legacy service groups are covered (SG10 via the catalog classes, SG11
  via config-instantiated beans — §12g).
- **60 unit tests + 6 MySQL integration tests** green (exactly-once, crash-redelivery replay, reaper, quarantine
  and head-init all proven against real MySQL). **Not deployed** — cutover = billing runs with
  `Billing:Summary:Enabled=true` and summary-service runs with `summary.autostart=true`.
- Contract is **PINNED** by dotnet (blob codec, ping topic, outbox DDL, sum_voice). The `MediationContext`
  shape stays provisional but is not load-bearing for the CDR build. See `docs/decisions.md` §12–13c.

## Build & test

```bash
mvn test                 # 60 fast unit tests (no DB/Kafka needed — SPI fakes)
mvn package              # + Quarkus augmentation (builds the runnable app)
mvn verify -Dsummary.it.mysql.password=…   # + 6 MySQL integration tests; SELF-SKIP if MySQL is unreachable
```

The integration test targets the local dev MySQL (`127.0.0.1:3306`, `root`); the password is supplied at run
time (no credential in git), override with `-Dsummary.it.mysql.url=… -Dsummary.it.mysql.user=…`.

## The pipeline (per bean, per drain)

```
billing (one tx):  write cdr/chargeable  +  write 1 outbox row {entity_type, data=base64(gzip(json [{Cdr,Customer}…]))}  →  Kafka ping
summary  (one tx per drain, per bean):
   read THIS bean's last_offset  →  read summary_affected rows after it  →  decode blobs
      →  compute windows involved  →  load those windows ONCE  →  merge the batch's deltas
      →  segmented multi-row INSERT/UPDATE summaries  +  advance last_offset   →  COMMIT (together)
   reaper: delete summary_affected rows with id ≤ min(last_offset) across active beans
```

The **load-windows-once** rule (loading per event double-counts), the **segmented** writer, and the
**one-transaction** boundary are unchanged from the ported engine. Exactly-once comes from committing the
summaries **and** the offset in the same MySQL transaction (a crash before commit → offset unchanged →
reprocessed clean).

## Summary beans (typed entity, one class per window — `summarybeans/<category>/`)

Beans live under `summarybeans/<category>/` — `call` today (CDR/voice), with `packetflow` / `session` / `voip`
/ `video` as future categories. A **summary entity** `T` (e.g. `CallSummary`) owns its key, merge, negate,
clone, and SQL fragments (`bean/spi/SummaryEntity`). A category **base bean** (`CallSummaryBean`) decodes an
outbox row's `{Cdr, Customer}` batch into bucketed entities; each **window is its own `@Singleton` class** —
`HourlySummary`, `DailySummary` — fixing only `window()`. Browse the folder = see every counter the category emits.

Activate a bean by listing its name in `summary.enabledSummary`; `table-suffix` / `service-group` / `context`
come from `summary.beans.<name>` (the window is the class, discovered + registered by `SummaryBootstrap`). A new
**category** = a new entity + base bean + window classes; a new **window** of an existing category = one tiny
subclass. An enabled name with no catalog class but a `window:` key is **config-instantiated** (§12g) — an extra
instance under its own name/offset/table, e.g. the SG11 pair (legacy summarised SG10 **and** SG11; both must stay covered).

`window` (fixed per class) is one of: `5min` / `Nmin` (multiple of 5) / `hourly` / `daily` / `weekly`
(Monday-start ISO week) / `monthly` / `yearly`.

## Configuration (routesphere-like)

- `application.properties` — `summary.autostart` (default off; gates the workers, ping listener, and reaper).
  The active tenant/profile is selected in `config/tenants.yml` (first entry flagged `enabled: true`).
- `config/tenants.yml` + `config/tenants/<tenant>/<profile>/profile-<profile>.yml` — datasource, the
  `summary.contexts` (config-manager) block, the `summary.outbox` settings, and the **`enabledSummary`** list +
  each bean's `table-suffix`/`service-group`/`context` (the window is the class — or the `window:` key for
  config-instantiated instances). Flattened by `TenantProfileConfigSource`.
- **DB credentials** are **inline** in the profile yml (no OpenBao), matching billing-core — fill the CCL creds
  at cutover (see `docs/decisions.md` §8). The integration-test password is supplied at run time, never committed.

## Layout

```
bean/spi      SummaryEntity<T> + SummaryBean<T> contracts · SummaryKey · WindowSize · SqlLiterals
beans/        PUBLIC API — fluent builders (one per bean): SummaryBeanBuilder<T,B> contract · Daily/HourlySummaryBuilder
engine/       load-merge-write over T: SummaryEngine (api) · SummaryStore (spi) · SummaryCache<T> (internal)
outbox/       OutboxReader (api, the ONE tx per drain) · OutboxStore + OutboxRow (spi) · codec + reaper (internal)
runtime/      UnitOfWork (spi, summary + outbox stores) · JDBC impls (internal)
registry/     SummaryBeanRegistry (api) · OutboxWorker + SummaryBootstrap [CDI-discovers beans] (internal)
context/      ContextRegistry (api) · SummaryContext (spi) · ConfigManagerClient + MediationContext (internal/cdr)
ping/         PingListener (Kafka cdr_summary_ping → wake workers)
config/       TenantProfileConfigSource (routesphere-like profile loader)
summarybeans/ one package per category (call today; sms/packetflow/session/voip/video later)
  call/       HourlySummary · DailySummary  (high-level beans only)
    internal/ CallSummaryBean (base) · CallSummaryBuilder · CdrBlobMapper
    model/    CallSummary (47 cols) · Cdr/Customer/CdrBlobEntry
  sms/        future — same shape as call
```

### Assemble a bean (public builder API)
```java
SummaryBean<CallSummary> daily = DailySummaryBuilder.create(mapper)
        .serviceGroup(10).tableSuffix("3").context("mediationContext").build();   // -> sum_voice_day_3
```
Every bean — now and future — exposes the same fluent chain (enforced by `beans/SummaryBeanBuilder`); the table
derives as `sum_voice_<window>_<table-suffix>` (the suffix selects a pre-provisioned set, e.g. `sum_voice_day_3`).
The running service still wires beans via CDI + YAML; the builder is the programmatic entry point for embedders.

See `docs/architecture.md` for the package tree + flow and `docs/decisions.md` for the architect rulings.
