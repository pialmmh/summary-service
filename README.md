# summary-service

A standalone **Java 21 / Quarkus** service that generates **time-windowed counters/summaries** for any event
stream — CDRs today, any log/entity later (softswitch/BSC/MSC-style performance counters). It **owns**
summarisation; producers (billing-core and others) just emit events to Kafka. Summaries become
**eventually consistent** (Kafka-fed) — fine for derived roll-ups.

It ports billing-core's proven **load-merge-write** summary pipeline (.NET → Java) and generalises the
CDR-specific code into a declarative, pluggable **bean** model.

## Status — v1 scaffold (built, tests green)

- Quarkus skeleton + routesphere-like tenant/profile config + the `SummaryBean` SPI + registry.
- The reference **cdr-voice** bean (day + hour windows → `sum_voice_*`).
- 15 unit tests + 2 MySQL integration tests green. **Not deployed** — cutover is coordinated with the
  architect + dotnet (billing-core drops its summary write).
- The rated-CDR event schema + `sum_voice_*` DDL are **PROVISIONAL**, pending billing-core (dotnet) pinning
  them. See `docs/decisions.md`.

## Build & test

```bash
mvn test                 # 15 fast unit tests (no DB/Kafka needed — uses SPI fakes)
mvn package              # + Quarkus augmentation (builds the runnable app)
mvn verify               # + MySQL integration tests; SELF-SKIP if MySQL is unreachable
```

The integration tests target the local dev MySQL (`127.0.0.1:3306`, `root`); override with
`-Dsummary.it.mysql.url=… -Dsummary.it.mysql.user=… -Dsummary.it.mysql.password=…`.

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

## Summary beans (pluggable, YAML-activated)

A **bean** = one counter/summary, declared data-first (`bean/spi/SummaryBean`): the event + topic, the time
windows (day/hour, generalisable to 15-min/week/month), the group-by **dimensions**, the additive
**counters** (with each event's delta), and the target tables. The engine does load-merge-write generically
from these declarations — no per-bean SQL. Beans are switched on + parameterised in the profile yml and can
be **hot-started** by the registry without a restart (the UI-defined-beans phase comes later).

## Configuration (routesphere-like)

- `application.properties` — only **selects** the active tenant/profile + `summary.autostart` (default off).
- `config/tenants.yml` — which tenant is enabled + its profile.
- `config/tenants/<tenant>/<profile>/profile-<profile>.yml` — datasource, Kafka, and per-bean activation,
  flattened into Quarkus config by `TenantProfileConfigSource`.
- **Secrets** (DB password) come from **OpenBao** at cutover — never committed, never from env. See
  `docs/decisions.md`.

## Layout

```
bean/spi         the pluggable SummaryBean contract (declare windows/dimensions/counters)
engine/          load-merge-write: SummaryEngine (api) · SummaryStore (spi) · cache+SQL (internal)
runtime/         BatchRunner (api, the ONE transaction) · UnitOfWork (spi) · JDBC store (internal)
registry/        SummaryBeanRegistry (api, start/stop/hot-start) · Kafka worker (internal)
config/          TenantProfileConfigSource (routesphere-like profile loader)
beans/cdr/       the reference cdr-voice bean + RatedCdrEvent (PROVISIONAL)
```

See `docs/architecture.md` for the package tree and `docs/decisions.md` for the architect rulings.
