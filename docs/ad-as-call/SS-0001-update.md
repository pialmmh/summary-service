from: SS        to: ARCH        kind: update        number: 0001
date: 2026-10-04T03:58+06:00        branch: postgres-ad-call (from master ec2546e)        head: this commit
subject: my reading of the brief against the code, BEFORE I build — 3 things need your word (partitions on PostgreSQL, the group-30 call summary, units in the charged amount), 1 list goes to billing-core, and the `sum_ad_*` DDL on PostgreSQL is here

# SS-0001 · update — the brief against the code

## 0 · What to relay

| | |
|---|---|
| the DDL (brief §4.2) | `src/main/resources/db/postgres/sum_ad.sql` — `sum_ad_day_30` and `sum_ad_hr_30`, the same 17 columns as on MySQL, `tup_app` 64 wide. Run in my lab: `summary_service` creates it in a tier schema, `ad_sphere` reads it through prime-context's default privileges, `ad_sphere` cannot delete. **ad-sphere's real `JdbcCdrReader` (its compiled class, pgjdbc 42.7.7) reads the row** |
| baseline | `master` ec2546e: **96 unit + 8 MySQL IT, 0 failed** (the ITs on my own MySQL 5.7.44, no password anywhere) |
| **decide 1** | **A partitioned `sum_ad_*` table is invisible to ad-sphere's reader** (F1, proven). I make the PostgreSQL tables PLAIN by default. The partitioned form is built too, behind one profile key. Say which you want |
| **decide 2** | **S4 is not "no code".** The call bean throws on every group-30 record (F2). I add a group-30 branch. My mapping is in F2. Say if it is wrong |
| **decide 3** | `chargedamount` adds units to money when a tier pays from a package (F5). I build it as the brief says. Say if only money must count |
| **relay to billing-core** | F3: six facts the ad beans need in the outbox blob, and two about the outbox ids. Its Kafka path does two of them differently today |
| nothing waits | I build S1–S9 now with the defaults above. Each default is one small change if you rule the other way |

## 1 · Where I stand

- Branch `postgres-ad-call` from `origin/master` ec2546e. `master` is not touched.
- Read: the instruction page and its deltas, the brief, the design (§3, §4.1, §5), billing-core's brief, `postgres-tenancy.md`,
  `JdbcCdrReader`, my `CLAUDE.md` and `docs/`, stream X's `summarybeans/ad`. Also read (not changed): billing-core's outbox writer,
  ping publisher and Kafka mapping (`master`, its branch `postgres-ad-call` has no new commit yet), seed-callflow's `CallCdr` /
  `CdrAssembler`, ad-sphere's `AdRecords.fillCdr`, prime-context's `SchemaSharing` (main 1b974a1).
- My lab: `tools/lab/pg-lab.sh` — my own containers `ss-pg16-lab` (127.0.0.1:7643, trust), `ss-mysql57-lab` (127.0.0.1:7633,
  empty root password), `ss-kafka-lab` (127.0.0.1:7692). No other container is touched.

## 2 · What I find wrong or missing — said before I build

### F1 · S2 against S5: a partitioned table on PostgreSQL reads as EMPTY in ad-sphere

The brief asks both: "ad-sphere's reader needs no change but the schema" (S2) and "the partitions with the full set made in the
same step" (S5). On PostgreSQL they cannot both hold today.

| | |
|---|---|
| the cause | `JdbcCdrReader.present()` asks the catalog for the types `TABLE`, `BASE TABLE`. pgjdbc 42.7.7 reports a partitioned parent as `PARTITIONED TABLE` |
| the proof | my lab, ad-sphere's compiled `JdbcCdrReader`: a partitioned `sum_ad_day_30` with 1 row. `ad_sphere` counts 1 row by SQL. `reader.summary("day", …)` answers `[]` (and one WARN "there is no … table yet"). The same row in a plain table is read |
| the cost of the full set | one `sum_ad` table with 730 daily partitions, made in one transaction on PostgreSQL 16.13: **18.8 s, 2,929 relations, 17 MB of empty index pages**. A tier has 6 summary tables (ad, call 30, chargeable × day, hour): about 2 minutes, 17,500 relations and 105 MB **per tier, empty**. A reseller is made at run time; 100 resellers = 1.7 million relations in the Odoo cluster |
| what I do | PostgreSQL: **plain tables by default** (same columns, same indexes, same key). The reader works as it is. A summary table is small: one row per key per day or hour |
| what is also built | `summary.ddl.postgres-partitions: true` gives the partitioned form: the parent and its full daily set in ONE transaction (PostgreSQL DDL is transactional, so it is all or nothing). Horizon by the existing keys `summary.ddl.partition-start` / `partition-days` |
| MySQL | unchanged: partitioned, full set inside the CREATE |
| **your word** | (a) plain stays, or (b) partitioned — then ad-sphere's `present()` must also accept `PARTITIONED TABLE` (one line, stream X's), and I advise a shorter horizon. A plain table cannot become partitioned in place, so rule before the bed window |

### F2 · S4 needs code: the call bean refuses service group 30

`CallSummaryBuilder.populateServiceGroup` knows groups 10 and 11. For any other group it throws
`IllegalArgumentException("no summary mapping for service group 30")`. The drain treats a build failure as a poison row: after 8
tries the whole outbox row is dead-lettered for that bean. So `service-group: 30` in a profile, as S4 says, would dead-letter every
batch that holds an ad view. The filter is also on the CHARGEABLE's `servicegroup`, not on `Cdr.ServiceGroup`.

What I build — a group-30 branch (pre-rated, the customer direction only):

| `sum_voice_*_30` column | from |
|---|---|
| partner, routes, IPs, `totalcalls`, `connectedcalls`, durations, PDD | the common part, as for every group: in-partner = the payer, incoming route = the campaign's route, outgoing route = the zone, connected = shown |
| `tup_customerrate`, `tup_customercurrency`, `customercost` | the customer chargeable: `unitPriceOrCharge`, `idBilledUom`, `BilledAmount` |
| `tup_matchedprefixcustomer` | `Cdr.MatchedPrefixCustomer` |
| supplier, taxes, VAT | nothing (nothing is bought, no tax on the record) |

One difference from 10 and 11, on purpose: **no `ChargingStatus` early return for group 30.** billing-core sets `ChargingStatus` from
`durationSec > 0`. A view that was admitted and never shown has 0 seconds and IS charged (your D5). With the early return its charge
would be missing from `sum_voice_*_30` while `sum_ad_*` and `sum_chargeable_*` have it. The chargeable is the truth for group 30.

**Your word:** is this mapping right? The config part stays as S4 says: a config-instantiated call bean, `service-group: 30`,
`table-suffix: "30"`.

### F3 · For billing-core (please relay): what the ad beans read in the blob

The ad beans read the entity `cdr`, blob v2, and keep the entries whose `Cdr.ServiceGroup` is 30. They need:

| # | in the blob | why I say it |
|---|---|---|
| 1 | `Cdr.ServiceGroup` = 30 | the filter (S1) |
| 2 | `Cdr.HangupCause` = the cause word | **today billing-core's Kafka path writes the cause into `AreaCodeOrLata`** (`CdrEventPreprocessor.Map`) and leaves `HangupCause` null. With that, every view counts as failed |
| 3 | `Cdr.AdditionalMetaData` = the record's `additionalMetaData` string | **today that column gets the SIP call id** (`variableSipCallId`). With that, campaign 0, no zone, no app, nothing completed |
| 4 | `Cdr.AnswerTime` (null = never shown), `StartTime`, `DurationSec`, `InPartnerId`, `OriginatingCalledNumber`, `Codec` | the dimensions and measures of S1 |
| 5 | ONE customer chargeable per record: `servicegroup` 30, `assignedDirection` 1, `BilledAmount`, `idBilledUom`, `unitPriceOrCharge`, `transactionTime` | the charged amount; the call bean's filter (F2); the chargeable bean's bucket is `transactionTime` and it skips a leg without one |
| 6 | a failed view: a `cdr` row and a chargeable of zero (B3) | it then counts as 1 view, failed, charge 0 |

And two facts about the outbox that the exactly-once cursor (`id > last_offset`) stands on:

| # | fact |
|---|---|
| 7 | outbox ids must become visible in commit order, per schema. On MySQL `GET_LOCK` across the commit gives it. On PostgreSQL the advisory lock of B6 must be held across the commit too (a transaction-level one is) |
| 8 | the identity of `summary_affected.id` must keep `CACHE 1` (the default). A cached sequence gives each connection its own block of ids: a later commit gets a smaller id and the reader skips it |

I test with rows written as these eight say. When billing-core's DDL arrives I check my reads against it.

### F4 · The brief's sample is shorter than what the switch sends

In the sample of billing-core's brief the root record's `additionalMetaData` has only `campaignId`, `levelIndex`, `partnerName`,
`reserveRef`. Read literally, `btcl`'s summary row has no zone, no site, no app, completed 0, credited 0.

The switch does more: seed-callflow's `CallCdr.sealed` calls `fillCdr` for EVERY tier, and ad-sphere's `AdRecords.fillCdr` writes
the view's facts (`campaignId`, `app`, `zone`, `site`, `completed`, `credited`, …) into each one. So in real records every tier has
them, and each tier's row has the same dimensions — what X's tests expected. My test of S1 uses the sample with the switch's meta
data on both tiers; one more test shows the literal sample's result. Nothing to do, unless the short form is wanted.

### F5 · `chargedamount` adds units to money

S1: charged amount = the customer chargeable's billed amount. B3: that amount is the settled money (`inPartnerCost`) OR the units
(`packageAmount`), with the unit in `idBilledUom`. `sum_ad_*` has no unit column. A tier that pays from a package (10 views) and one
that pays 0.50 BDT sum to 10.50 in the same column when their other dimensions are equal.

I build it as the brief says. **Your word** if you want: (a) only legs whose unit is `BDT` count into `chargedamount` (units stay
in `sum_chargeable_*`, which has the unit in its key), or (b) keep as is.

### F6 · On PostgreSQL I must not create `summary_affected`

`OutboxInfraDdl` makes a dev copy of billing's outbox (`CREATE TABLE IF NOT EXISTS summary_affected`) at the first start. In a tier
schema on PostgreSQL that would make `summary_service` the OWNER of billing-core's table when I come first: billing-core finds it
present, cannot insert, and the rights of §3 are upside down. So on PostgreSQL I create only my own (`summary_offset`,
`summary_affected_dlq`, `sum_*`). A schema that has no outbox yet is "billing-core has not served it": its workers wait and say so
once, they do not fail. MySQL keeps the dev copy (unchanged).

### F7 · S7: the head-init is per bean today, and two beans of a new schema would race

`registry.start()` seeds each bean at the outbox head when it has no bookmark. That is X's lost rows. The rule I build: **decided
once per schema, before any of its workers starts, for all its beans in one transaction** — no bookmark at all in the schema (it was
never served): every bean starts at 0. Else: a bean without a bookmark starts at the head (a late-enabled bean), as today. Deciding
per bean would let the first bean's commit make the schema look "served" to the second one.

### F8 · String literals: a backslash is written differently

The entities render their own SQL literals (`SqlLiterals.str`) the MySQL way: a backslash is doubled. PostgreSQL's default
(`standard_conforming_strings = on`) keeps both. A zone or an app with a backslash would then be stored with two, reload with a
different key, and get a second row for the same window. I close it at the edge: the PostgreSQL unit of work sets
`standard_conforming_strings = off` (and `escape_string_warning = off`) on its connection, so the same text means the same on both
engines. No entity changes. A test writes a value with a backslash and a quote on both engines and reads one row back.

### F9 · "Chosen per profile" needs my own pool

The profile carries `quarkus.datasource.db-kind`. Quarkus fixes that key when the jar is BUILT: one jar, one engine (prime-context
makes two jars for the same reason). I have no Hibernate, so I do not need that: the store gets its own pool (Agroal, built at
start) from the profile, and one jar serves either engine.

```yaml
summary:
  store:
    kind: postgresql                 # mysql | postgresql
    url: jdbc:postgresql://10.10.199.20:5432/routesphere
    username: summary_service
    password-ref: env:TENANT_BTCL_SWITCH_SUMMARY_SERVICE_PASSWORD   # S9; or  password: "…"  (the inline form, kept)
```

The tcbl profile moves to these keys (its inline password form stays). The Quarkus datasource extension goes.

### F10 · Smaller things

| # | what | what I do |
|---|---|---|
| a | `tup_tenant` "is always the schema's own name" (S2), but a bean does not know which schema it is drained for | the drain passes the tier's name to the bean (one new SPI method with a default). The ad bean uses it. I do not derive it from `ResellerHierarchy` |
| b | S3 on a table made before today | `CREATE TABLE IF NOT EXISTS` never alters. PostgreSQL tables are new, so they are 64. A MySQL `sum_ad_*` made by X's rehearsal keeps 32: `ALTER TABLE … MODIFY tup_app VARCHAR(64) NOT NULL DEFAULT ''` once, or drop it (the `ad_cdr` writer is retired) |
| c | `DurationSec` is a decimal on the `cdr`; `watchedsec` is a whole number | rounded half up, per view |
| d | `tup_rulecode` is 20 wide. With no rule the switch puts the ZONE (up to 64) into the called number | it is cut to 20. No rows merge by it: `tup_zone` (64) is in the key too. Left as is |
| e | time zone (N4) | the bucket is cut on the `cdr`'s wall clock as it is in the blob; no zone conversion exists in that path. The one place that asks the JVM's clock is the partition horizon ("this year"): it takes the tenant's zone now (`summary.zone`, default `Asia/Dhaka`). A test runs the path in a JVM set to UTC |
| f | the ping (S8) | billing-core's payload is `{tenant, entity, rows}`, `tenant` = the schema name (`CdrProcessor.ProcessBatch`). Today every ping wakes every worker. I wake that schema's workers; a payload I cannot read wakes all, as today |
| g | partner types (D4), refunds (D5) | nothing of mine reads a partner type. Group 30 has no refund path of its own; `op = subtract` stays billing-core's correction, for every group |
| h | infra tables "once per process" | become once per schema |

### F11 · The rights, as I see them in my lab (D2)

I make a tier schema with the statements of prime-context `SchemaSharing` (main 1b974a1), then act as each role:

| | seen |
|---|---|
| `summary_service` creates `sum_ad_*` in the tier schema | yes (USAGE + CREATE) |
| `ad_sphere` reads them | yes, through `ALTER DEFAULT PRIVILEGES FOR ROLE summary_service … GRANT SELECT ON TABLES TO ad_sphere` |
| `ad_sphere` deletes from them | refused |
| on billing-core's tables | main still gives `summary_service` **SELECT and DELETE on every table billing-core creates** (`summary-on-billing` = `SELECT,DELETE`): W12 has not landed, as you say. My reaper deletes from `summary_affected` only |

My PostgreSQL tests run with the RULED rights (SELECT by default, DELETE on `summary_affected` granted by its owner), the narrower
set. The full table of what I saw comes in the done page of S5.

## 3 · How I build S1–S9

| # | how |
|---|---|
| S1 | the ad beans decode the `cdr` blob themselves (their own view of `Cdr`: the fields of F3), keep group 30, read the meta data's JSON. `ad_cdr`, `AdCdr`, `AdLeg`, `AdTier` go. X's tests are rewritten on the new input with the same expected rows |
| S2 | `AdSummary.INSERT_COLUMNS` unchanged; a test holds the reader's column list against it |
| S3 | 64 in the DDL of both engines and in the builder's cut |
| S4 | F2, and the wifi profile lists the beans |
| S5 | a `SqlDialect` at the store edge: the DDL (one table description, two renderers), the offset's upsert and head-init, the session of F8, the pool of F9. The engine, the cache and the entities do not change. The MySQL ITs are mirrored on PostgreSQL |
| S6 | the tree from prime-context (`POST /get-specific-tenant-root`: `dbName` + `children`), a worker and a bookmark per (schema, bean), the doorbell `config_event_loader_<root>` reloads the tree and starts the new schemas' workers; their tables are made then. The unit of work is opened ON a schema. Off by default: a profile without a tree serves one schema, as today |
| S7 | F7 |
| S8 | F10 f |
| S9 | F9: `password-ref: env:NAME`; a missing variable refuses the start in words |

Every new rule gets a test, is broken once in the code, seen red, and restored with `git checkout`.

## 4 · Questions

| # | question | what I do until you answer |
|---|---|---|
| Q1 | PostgreSQL summary tables: plain, or partitioned (then ad-sphere's `present()` changes)? (F1) | plain; partitioned behind `summary.ddl.postgres-partitions` |
| Q2 | is the group-30 mapping of `sum_voice_*_30` right, with no `ChargingStatus` early return? (F2) | as written in F2 |
| Q3 | `chargedamount`: every unit, or money only? (F5) | every unit, as the brief says |
| Q4 | please relay F3 to billing-core | I test with rows written as F3 says |
