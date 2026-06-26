# summary-service

A standalone **Java 21 / Quarkus** service that generates **time-windowed counters/summaries** for any event
stream — CDRs today, any log/entity later (softswitch/BSC/MSC-style performance counters). It **owns**
summarisation; producers (billing-core and others) just emit events to Kafka. Summaries become
**eventually consistent** (Kafka-fed) — fine for derived roll-ups.

It ports billing-core's proven **load-merge-write** summary pipeline (.NET → Java) and generalises the
CDR-specific code into a declarative, pluggable **bean** model.

## Status — built, tests green (typed `Summary<T>` redesign)

- Quarkus skeleton + routesphere-like tenant/profile config + the typed `SummaryEntity<T>`/`SummaryBean<T>` SPI
  + registry.
- The reference **`CdrSummary`** entity (faithful 1:1 port of billing-core's `AbstractCdrSummary`, all 47
  columns) with `CdrSummaryBean` configured per window (daily + hourly → `sum_voice_*`).
- 27 unit tests + 2 MySQL integration tests green. **Not deployed** — cutover is coordinated with the
  architect + dotnet (billing-core drops its summary write).
- `RatedCdrEvent` + `sum_voice_*` DDL are **PROVISIONAL**, pending billing-core (dotnet) pinning the topic /
  event field names / real DDL. See `docs/decisions.md` §12.

## Build & test

```bash
mvn test                 # 27 fast unit tests (no DB/Kafka needed — uses SPI fakes)
mvn package              # + Quarkus augmentation (builds the runnable app)
mvn verify -Dsummary.it.mysql.password=…   # + MySQL integration tests; SELF-SKIP if MySQL is unreachable
```

The integration tests target the local dev MySQL (`127.0.0.1:3306`, `root`); the password is supplied at run
time (no credential in git), override with `-Dsummary.it.mysql.url=… -Dsummary.it.mysql.user=…`.

## The pipeline (per bean, per batch)

```
poll Kafka batch  →  compute windows involved  →  load those windows ONCE
   →  merge (increment/decrement)  →  segmented multi-row INSERT/UPDATE  →  ONE transaction per batch
```

1. **Poll** a batch (default 1000, configurable).
2. **Load** every existing row for the distinct day/hour buckets the batch touches, in ONE query per window.
   *Loading per event would double-count — this is the core invariant.*
3. **Merge** each event into its cached window (`SummaryCache`): increment, decrement, or overwrite.
4. **Write** the net change as a segmented multi-row extended INSERT (new rows) + id-targeted UPDATEs
   (loaded rows).
5. **Atomic batch** — ONE top-level transaction (`BatchRunner`); any failure rolls the WHOLE batch back. No
   inner class commits.

## Summary beans (typed entity, config-driven instances)

A **summary entity** `T` (e.g. `CdrSummary`) is a real class that owns its key, merge, negate, clone, and SQL
fragments (`bean/spi/SummaryEntity`). A **bean** (`bean/spi/SummaryBean<T>`) builds `T` from an event stream
into one table over one window. There is **one bean class per entity**; each `enabledSummary` entry is a
distinct **configured instance** — daily, hourly, 5-minute, weekly are just different `window` + `table`
configs of the same class, no code change. The engine does load-merge-write over `T`; a future `CallQuality`
summary is a new entity + factory. Beans **hot-start** via the registry without a restart.

`window` accepts: `5min` / `Nmin` (multiple of 5) / `hourly` / `daily` / `weekly` (Monday-start ISO week) /
`monthly` / `yearly`.

## Configuration (routesphere-like)

- `application.properties` — only **selects** the active tenant/profile + `summary.autostart` (default off).
- `config/tenants.yml` — which tenant is enabled + its profile.
- `config/tenants/<tenant>/<profile>/profile-<profile>.yml` — datasource, Kafka, and the **`enabledSummary`**
  list + each bean's `entity`/`window`/`table`/`topic`/`service-group`, flattened into Quarkus config by
  `TenantProfileConfigSource`. Only beans in `enabledSummary` run.
- **Secrets** (DB password) come from **OpenBao** at cutover — never committed, never from env. See
  `docs/decisions.md`.

## Layout

```
bean/spi         SummaryEntity<T> + SummaryBean<T> contracts · SummaryKey · WindowSize · SqlLiterals
engine/          load-merge-write over T: SummaryEngine (api) · SummaryStore (spi) · SummaryCache<T> (internal)
runtime/         BatchRunner (api, the ONE transaction) · UnitOfWork (spi) · JDBC store (internal)
registry/        SummaryBeanRegistry (api) · SummaryBeanFactory + BeanConfig (spi) · worker + bootstrap (internal)
config/          TenantProfileConfigSource (routesphere-like profile loader)
beans/cdr/       CdrSummary entity (47 cols) · CdrSummaryBuilder · CdrSummaryBean · factory · RatedCdrEvent (PROVISIONAL)
```

See `docs/architecture.md` for the package tree and `docs/decisions.md` for the architect rulings.
