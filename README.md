# summary-service

A standalone **Java 21 / Quarkus** service that generates **time-windowed counters/summaries** for any event
stream — CDRs today, any log/entity later (softswitch/BSC/MSC-style performance counters). It **owns**
summarisation; billing-core hands off each rated-CDR batch via a **transactional outbox** in the tenant's own
schema — on **MySQL or PostgreSQL**, chosen per profile — and summary-service consumes it **incrementally**.
Summaries are **eventually consistent** (outbox-fed) — fine for derived roll-ups.

It ports billing-core's proven **load-merge-write** engine (.NET → Java) over a typed summary **entity**, and
consumes the outbox **exactly-once per bean**, for **every tenant of its root's tree in one process**.

## Since 2026-10-04 — an ad is a Call (branch `postgres-ad-call`; decisions §16)

- **The ad view is a `cdr` row of service group 30.** The ad beans (`sum_ad_day_30` / `sum_ad_hr_30`) read the call's
  own outbox stream; the call bean takes group 30 from a profile (`sum_voice_*_30`); the chargeable beans are as
  they were. Money and a package's units are two measures (`chargedamount`, `chargedunits`), never added.
- **PostgreSQL as the store**, chosen at run time (`summary.store.kind`): one jar serves either engine. A tenant
  is a schema there; the summary tables are plain; the service never makes billing-core's tables.
- **Every tenant of the tree in one process**: a worker and a bookmark per (schema, bean). The tree comes from
  prime-context; a reseller made at run time is served with no restart. A schema seen for the first time starts
  at offset 0. The ping wakes the tenant it names.
- **The password by the NAME of its environment variable** (`summary.store.password-ref: env:NAME`).
- A start **names its tenant** (`SUMMARY_ACTIVE_TENANT=<tenant>/<profile>`), says the endpoints it resolved before
  it dials one, and a deployment's profile is a file beside its working directory.

The page for a deployment: [`docs/ad-as-call/postgres-ad-profile.md`](docs/ad-as-call/postgres-ad-profile.md).
The work's notes: `docs/ad-as-call/SS-0001-update.md`, `SS-0002-done.md`.

## Status before that — built, tests green (outbox consumer; the numbers of 2026-07)

- Input is the MySQL outbox `summary_affected` — blob **v2**: base64(gzip(JSON)) batches of
  `{Cdr, Chargeables:[ALL legs]}` (the v1 `{Cdr, Customer}` shape is tolerated permanently), plus an
  `op` column (`add`/`subtract` — a billing correction writes subtract(OLD)+add(NEW), applied in id order).
  Kafka (`cdr_summary_ping`) is only a wakeup. Per-bean bookmark `summary_offset.last_offset`.
- **Exactly-once per bean**: each bean writes its summaries **and** advances its offset in ONE MySQL
  transaction → no double-count on redelivery. Daily & hourly are **separate parallel workers** sharing a
  read-only `MediationContext` (loaded once from config-manager). A **reaper** trims consumed outbox rows.
- The ratified engine (load-windows-once → merge → segmented insert → one-tx) + the `CallSummary` entity
  (faithful 1:1 port of `AbstractCdrSummary`, 47 cols) are **unchanged** — they read the blob now. UPDATE/DELETE
  carry `id + tup_starttime` so MySQL prunes to the one date partition (§13b).
- **Two categories** now roll up the same stream: **voice** (SG10 suffix `03` + SG11 suffix `02`, per-SG beans —
  §12g) into `sum_voice_*`, and the net-new **chargeable** category (§13d) — EVERY leg of every cdr, customer and
  supplier as separate rows — into `sum_chargeable_day`/`_hr`. SG10 voice is now field-faithful to legacy
  (`MatchedPrefixCustomer`/`ZAmount`/`CostAnsIn`/package fields + the `ChargingStatus` early-return).
- **Hardened (§13c)**: stop/start can never run two workers on one offset; a poison row (bad blob OR a
  subtract targeting a missing window) is quarantined to `summary_affected_dlq` after N consecutive failures
  instead of wedging the bean + reaper; a late-enabled bean **head-inits** its offset; key decimals/strings are
  canonicalized to what MySQL stores before keying; `readOffset … FOR UPDATE`.
- **Self-provisioning (§13d)**: each bean `CREATE TABLE IF NOT EXISTS`-es its own table at activation with the
  FULL daily partition set inside the CREATE; infra tables (`summary_offset`, `summary_affected_dlq`) are
  ensured at startup. The only ops item left is the **GRANT** (CREATE/ALTER on the tenant schema).
- **77 unit tests + 8 MySQL integration tests** green (exactly-once, crash-redelivery replay, reaper,
  quarantine, head-init, subtract-correction arithmetic, and partitioned self-provisioning all proven against
  real MySQL). **Not deployed** — cutover = billing enables the outbox producer, this service runs with
  `summary.autostart=true`, and the legacy .NET summary jobs are STOPPED (required step).
- Contract is **PINNED** by dotnet (blob v2 codec, `op`, ping topic, outbox DDL, sum_voice 03/02). The
  `MediationContext` shape stays provisional but is not load-bearing. See `docs/decisions.md` §12–13d.

## Build & test

```bash
mvn test                 # the unit tests (no DB/Kafka needed — SPI fakes)
mvn package              # + Quarkus augmentation (builds the runnable app)
tools/lab/pg-lab.sh all  # the lab: PostgreSQL 16 (7643), MySQL 5.7.44 (7633), Kafka 3.9 (7692) — 127.0.0.1 only, no password exists
mvn verify -Dsummary.it.mysql.url='jdbc:mysql://127.0.0.1:7633/?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true'
```

`mvn verify` adds the integration tests: the outbox consumer's contract word for word on MySQL and on PostgreSQL
(`OutboxConsumerContract`), the tree on both, the two Kafka listeners, and a guard that reads the packaged jar. A
test whose lab does not answer is SKIPPED, never passed. PostgreSQL's and Kafka's lab are the defaults
(`-Dsummary.it.pg.url`, `-Dsummary.it.kafka`); MySQL's default is `127.0.0.1:3306` with a password given at run
time (`-Dsummary.it.mysql.password`), so name the lab's as above. The lab's stories with the packaged jar:
`tools/lab/tree-e2e.sh`, `reconcile-with-billing-core.sh`, `secret-e2e.sh`, `this-box-e2e.sh`, `ping-topic-e2e.sh` — a service is started there only
through `tools/lab/run-lab.sh`, which shows the profile and the endpoints first and starts only when every host is
THIS BOX: localhost, a loopback address, or an address one of this box's own interfaces holds (a real
prime-context never listens on loopback; `tools/lab/this-box-e2e.sh` shows both sides). A host name is never
looked up. The lab's key is `summary.endpoints.local-only: true` — the lab profile carries it itself.

## The pipeline (per bean, per drain)

```
billing (one tx):  write cdr/chargeable  +  write 1 outbox row {entity_type, op, data=base64(gzip(json [{Cdr,Chargeables[]}…]))}  →  Kafka ping
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

### The ad summary tables — `sum_ad_day_30`, `sum_ad_hr_30` (one pair per tier schema)

The same columns on MySQL and on PostgreSQL (`src/main/resources/db/postgres/sum_ad.sql` is the reference copy).
A row is one KEY — every `tup_*` column — in one window; the measures are summed over the views of that key.

| column | type (MySQL / PostgreSQL) | from the tier's `cdr` record | |
|---|---|---|---|
| `id` | BIGINT, numbered by the database | | |
| `tup_tenant` | VARCHAR(100) | the schema's own name | key: the tier |
| `tup_partnerid` | INT / INTEGER | `InPartnerId` | key: this tier's payer |
| `tup_campaignid` | INT / INTEGER | meta data `campaignId` | key (0 = none) |
| `tup_rulecode` | VARCHAR(64) | `OriginatingCalledNumber` | key: the rule's code (20) — or the ZONE (64), which a tenant with no rule table has as its called number |
| `tup_zone` | VARCHAR(64) | meta data `zone` | key |
| `tup_site` | VARCHAR(64) | meta data `site` | key |
| `tup_app` | VARCHAR(64) | meta data `app` | key |
| `tup_mediakind` | VARCHAR(16) | `Codec` | key |
| `tup_outcome` | VARCHAR(32) | `done` when `HangupCause` is `NORMAL_CLEARING`, else `failed` | key |
| `tup_starttime` | DATETIME / TIMESTAMP (no zone) | `StartTime`, cut to the day or the hour | key: the window (MySQL partitions by it) |
| `views`, `shown`, `completed`, `credited`, `failed`, `watchedsec` | BIGINT | 1 per view; an answer time; meta data `completed`, `credited`; not `done`; `DurationSec` | measures |
| `chargedamount` | DECIMAL(18,6) / NUMERIC(18,6) | the customer chargeable's `BilledAmount` when its unit is `BDT` | measure: money |
| `chargedunits` | DECIMAL(18,6) / NUMERIC(18,6) | the same amount when the unit is a package's | measure: units, never added to the money |
| `tup_contentid` | VARCHAR(64) | meta data `contentId` | key: the content shown — a campaign holds several; `''` when the record carries none (a refused view, a house ad) |

- **The key is the summary engine's**, not a database constraint: a drain loads the rows of the windows it touches
  ONCE, merges by the key in memory, then inserts the new keys and updates the loaded rows by `id` — one writer
  per table, the rows and the bookmark in one transaction. No `UNIQUE` index carries the key, on either engine
  (decisions §5).
- **A value is never wider than its column.** Every text of the key has ONE width, used by the builder's cut and
  by the table (`AdSummaryBuilder.*_WIDTH`), and it is its source's full width: a zone, a site, an app and a
  content id 64; the rule code 64 (it was 20). So what the switch can send is stored whole. A text that is wider
  all the same is cut to the column by the service BEFORE the key is taken — the database never refuses a row
  over a width, and a row that is built keys as the row that is reloaded.
- **Columns are only ever appended.** `tup_contentid` (2026-10-04) is the table's LAST column: a table that gets a
  column by `ALTER` — which can only append on PostgreSQL — is then the same table as one made with it.
- **An EXISTING table is brought up to this by the service itself**, at the table's first use after an upgrade
  (`TableDdl.bringUpToDate`): it reads the table's columns from the database's catalog and adds what is missing —
  `ALTER TABLE … ADD COLUMN tup_contentid VARCHAR(64) NOT NULL DEFAULT ''` — and widens a text column that is
  narrower — `tup_rulecode` from 20 to 64. The rows that are there read `''` as their content and keep the rule
  code they have: **history is not rebuilt**. A table that is as described gets no statement and no lock, so the
  step runs at every start and changes nothing the second time. It applies to the ad tables only (net-new, this
  service's alone): the voice and chargeable tables, which a legacy system may also write, are never altered.

## Configuration (routesphere-like)

- `application.properties` — `summary.autostart` (default off; gates the workers, ping listener, and reaper;
  with it off nothing is dialled).
- **A deployment's configuration is OUTSIDE the jar**, in ONE directory — the one that holds `config/tenants/…` —
  named by one key: `-Dsummary.config.dir=<directory>` or `SUMMARY_CONFIG_DIR` (not named: the working directory).
  A profile file found there wins; else the jar's own is read. The start's first line says which tenant, what
  named it and which FILE was read (`PROFILE tenant btcl, profile bed (…): the file /etc/…/profile-bed.yml`).
- The active tenant/profile: the one the START names (`SUMMARY_ACTIVE_TENANT=<tenant>/<profile>` or
  `-Dsummary.active-tenant=`), else the first entry flagged `enabled: true` in the directory's own
  `config/tenants.yml`, else in the jar's.
- `config/tenants/<tenant>/<profile>/profile-<profile>.yml`: the store (`summary.store.*`: kind, url, username, password | password-ref), the tenants
  (`summary.tenants.*`: single | tree), the `summary.contexts` (config-manager) block, the `summary.outbox`
  settings, and the **`enabledSummary`** list + each bean's `table-suffix`/`service-group`/`context` (the window
  is the class — or the `window:` key for config-instantiated instances). A profile answers `summary.*` keys by
  name and lists nothing (`TenantProfileConfigSource`): no tenant's value is baked into the jar at build time.
- **The ping's topic carries the ROOT on a deployment**: `summary.outbox.ping-topic: cdr_summary_ping_<root>`, and
  the SAME name on billing-core's side (its `billing.summary.ping-topic`) — as the lane's other topics do
  (`cdr_<root>`, `cdr_dlq_<root>`, `config_event_loader_<root>`). On one broker with two operators a shared
  `cdr_summary_ping` would bring every operator's pings to every summary-service (a ping wakes the tier it names:
  two trees may hold a tier of the same name, and a one-tenant deployment wakes on every ping). **The topic must EXIST** (a deployment's broker makes none
  by itself): without it billing-core's ingest waits 60 s per tier per batch for the ping's metadata (seen in the
  rehearsal), and this service runs on the poll alone, with about one WARN line a second from the Kafka client
  that names the topic; made later, the topic is heard with no restart (`tools/lab/ping-topic-e2e.sh`). The same
  holds for the doorbell's topic, `config_event_loader_<root>` (prime-context's). The key's default,
  `cdr_summary_ping`, is the voice deployment's name and is not changed.
- **DB credentials**: `password-ref: env:NAME` on the wifi bed (the value in the unit's environment only); the
  **inline** form stays for the other deployments (see `docs/decisions.md` §8, §16j).

## Layout

```
bean/spi      SummaryEntity<T> + SummaryBean<T> contracts · SummaryKey · WindowSize · SqlLiterals · DdlPartitions
              · SqlDialect (mysql | postgresql) · SummaryTableSpec (a table described once) · TableDdl (rendered per engine)
beans/        PUBLIC API — fluent builders: SummaryBeanBuilder<T,B> root · CallBeanBuilder (voice layer: SG+suffix)
              · Daily/HourlySummaryBuilder · Daily/HourlyChargeableSummaryBuilder
engine/       load-merge-write over T: SummaryEngine (api) · SummaryStore (spi) · SummaryCache<T> (internal)
outbox/       OutboxReader (api, the ONE tx per drain) · OutboxStore + OutboxRow (spi) · codec + reaper (internal)
runtime/      UnitOfWork (spi, summary + outbox stores, begun ON a tenant schema) · JDBC impls · StoreDataSource
              (the pool, from the profile) · StoreConfig · StoreSecret (internal)
registry/     SummaryBeanRegistry (api: a worker per (schema, bean)) · OutboxWorker + SummaryBootstrap [CDI-discovers
              beans] + StartEndpoints [what a start will dial, said first] (internal)
tenancy/      TenantWatcher (api: which schemas are served — one, or the root's tree) · TenantTreeSource (spi)
              · PrimeContextTreeSource + TenantTreeParser + DoorbellListener (internal)
context/      ContextRegistry (api) · SummaryContext (spi) · ConfigManagerClient + MediationContext (internal/cdr)
ping/         PingListener (Kafka cdr_summary_ping → wake the workers of the tenant it names) · PingPayload
config/       TenantProfileConfigSource (routesphere-like profile loader)
summarybeans/ one package per category (call + chargeable today; sms/packetflow/session later)
  call/       HourlySummary · DailySummary · CallSummaries (config-instantiated extras, e.g. the SG11 pair)
    internal/ CallSummaryBean (base) · CallSummaryBuilder · CdrBlobMapper · SumVoiceDdl
    model/    CallSummary (47 cols) · Cdr/Chargeable/CdrBlobEntry (blob v2, v1 tolerated — shared by both categories)
  chargeable/ HourlyChargeableSummary · DailyChargeableSummary   (EVERY leg, every SG; fixed tables)
    internal/ ChargeableSummaryBean (base) · ChargeableSummaryBuilder · SumChargeableDdl
    model/    ChargeableSummary (7-key + 15 measures, DECIMAL(20,8))
  ad/         DailyAdSummary · HourlyAdSummary   (service group 30 of the cdr stream; one pair per tier schema)
    internal/ AdSummaryBean (base) · AdSummaryBuilder · AdMetaData · SumAdDdl
    model/    AdSummary (10-key + 8 measures) · AdCallCdr / AdCallEntry (the ad's view of the blob) · AdViewFacts · AdView
  sms/        future — same shape
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
