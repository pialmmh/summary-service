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
- The ratified engine (load-windows-once → merge → segmented insert → one-tx) + the `CdrSummary` entity
  (faithful 1:1 port of `AbstractCdrSummary`, 47 cols) are **unchanged** — they read the blob now.
- 33 unit tests + 1 MySQL integration test green. **Not deployed** — cutover = billing runs with
  `Billing:Summary:Enabled=true` and summary-service runs with `summary.autostart=true`.
- Contract is **PINNED** by dotnet (blob codec, ping topic, outbox DDL, sum_voice). The `MediationContext`
  shape stays provisional but is not load-bearing for the CDR build. See `docs/decisions.md` §12–13.

## Build & test

```bash
mvn test                 # 33 fast unit tests (no DB/Kafka needed — SPI fakes)
mvn package              # + Quarkus augmentation (builds the runnable app)
mvn verify -Dsummary.it.mysql.password=…   # + MySQL integration test; SELF-SKIPS if MySQL is unreachable
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

## Summary beans (typed entity, config-driven instances)

A **summary entity** `T` (e.g. `CdrSummary`) owns its key, merge, negate, clone, and SQL fragments
(`bean/spi/SummaryEntity`). A **bean** (`bean/spi/SummaryBean<T>`) decodes an outbox row's `{Cdr, Customer}`
batch into bucketed entities. **One bean class per entity**; each `enabledSummary` entry is a distinct
**configured instance** — daily, hourly, 5-minute, weekly are different `window` + `table` configs, no code
change. A future `CallQuality` summary is a new entity + factory.

`window` accepts: `5min` / `Nmin` (multiple of 5) / `hourly` / `daily` / `weekly` (Monday-start ISO week) /
`monthly` / `yearly`.

## Configuration (routesphere-like)

- `application.properties` — selects the active tenant/profile + `summary.autostart` (default off; gates the
  workers, ping listener, and reaper).
- `config/tenants.yml` + `config/tenants/<tenant>/<profile>/profile-<profile>.yml` — datasource, the
  `summary.contexts` (config-manager) block, the `summary.outbox` settings, and the **`enabledSummary`** list +
  each bean's `entity`/`window`/`table`/`service-group`/`context`. Flattened by `TenantProfileConfigSource`.
- **DB credentials** are **inline** in the profile yml (no OpenBao), matching billing-core — fill the CCL creds
  at cutover (see `docs/decisions.md` §8). The integration-test password is supplied at run time, never committed.

## Layout

```
bean/spi      SummaryEntity<T> + SummaryBean<T> contracts · SummaryKey · WindowSize · SqlLiterals
engine/       load-merge-write over T: SummaryEngine (api) · SummaryStore (spi) · SummaryCache<T> (internal)
outbox/       OutboxReader (api, the ONE tx per drain) · OutboxStore + OutboxRow (spi) · codec + reaper (internal)
runtime/      UnitOfWork (spi, summary + outbox stores) · JDBC impls (internal)
registry/     SummaryBeanRegistry (api) · SummaryBeanFactory + BeanConfig (spi) · OutboxWorker + bootstrap (internal)
context/      ContextRegistry (api) · SummaryContext (spi) · ConfigManagerClient + MediationContext (internal/cdr)
ping/         PingListener (Kafka cdr_summary_ping → wake workers)
config/       TenantProfileConfigSource (routesphere-like profile loader)
beans/cdr/    CdrSummary (47 cols) · CdrSummaryBuilder · CdrSummaryBean · factory · Cdr/Customer/CdrBlobEntry (blob, PROVISIONAL fields)
```

See `docs/architecture.md` for the package tree + flow and `docs/decisions.md` for the architect rulings.
