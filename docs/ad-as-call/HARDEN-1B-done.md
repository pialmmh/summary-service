from: SS (HARDEN-1 B)  to: ARCH  kind: done  date: 2026-10-25  branch: `reaper-entities` (from `kinds` ead3f2b)  ·  nothing pushed

# HARDEN-1 B — summary-service: the reaper trims every entity that has beans, each by its own beans' bookmarks

The brief: `~/arch-drafts/launch/HARDEN-1-billing-summary-brief.md`, part B. The rules: `~/arch-drafts/launch/RULES-2026-10-25.md`.

## 0 · What to relay

| | |
|---|---|
| the branch | `reaper-entities`, from `kinds` ead3f2b: **9008c3a** B1 (the code, the tests, the jar's profiles) · **1fe828d** B2 (the docs, the state file) · **e598117** B3 (the report) · **4ca45a0** B4 (bounded batches: code, tests, docs, report §7) · **f09a6b8** B4 (the store's comment) · the commit that carries this note's final form. Not pushed; `kinds`, `master-merge` and `postgres-ad-call` not touched |
| the rule, as built | every reaper pass trims EACH entity that has ≥ 1 registered bean, each by `min(last_offset)` over THAT entity's beans only; an entity with no registered bean is never trimmed (SS-0008 D1); each (schema, entity) its own transaction; the once-said WARN / INFO of S16 now per (schema, entity); `summary.outbox.entity-type` retired — set, ONE INFO at start says it is ignored and why |
| what a deployment sees | the first passes after this jar delete the `wifi_flow` rows every WiFi destination bean has passed (never trimmed until now) — since B4 in BOUNDED batches: 10 000 rows a transaction, as many as a pass's 5 s allow beyond each (schema, entity)'s first, the rest over the next passes (§7). The `cdr` trims are exactly as before: one transaction a pass. A rendered profile that still sets `summary.outbox.entity-type` (the bed's, probably) gets the INFO; the line can go (the deploy page says so) |
| the gate (Maven `verify`, a git-archive clean copy of 1fe828d, both engines) | **unit 442 · 0 · 0 · 0, ITs 92 · 0 · 0 · 3**, BUILD SUCCESS (05:23:43–05:30:05 +06) — the baseline of `kinds` (unit 436 · 0 · 0 · 0, ITs 88 · 0 · 0 · 3) + `OutboxReaperTest`'s 6 + the 2 contract tests on each engine. ITs: `PostgresOutboxConsumerIT` 43 + `PostgresTreeIT` 9 (PostgreSQL 52), `OutboxConsumerIT` 34 + `MySqlTreeIT` 2 (MySQL 36), `BuildBakesNoTenantIT` 1, `KafkaListenersIT` 3 skipped (no broker, as SS-0007/SS-0008). No red; 0 ERROR lines |
| the gate of B4 (Maven `verify`, a git-archive clean copy of f09a6b8, both engines) | **unit 446 · 0 · 0 · 0, ITs 96 · 0 · 0 · 3**, BUILD SUCCESS (06:25:27–06:30:22 +06) — B3's 442 / 92 + `OutboxReaperBatchesTest`'s 4 + the two batch tests on each engine. No red (§7.5) |
| red first | 5 red on the code of before (§2) |
| mutants | MB1–MB5 (the brief's three + two), all red (§3); on the B4 code MB6 (the batch ignored = one big delete) and MB1–MB5 again (MB2 per engine), all red (§7.4) |

## 1 · As built

| where | what |
|---|---|
| `registry/api/SummaryBeanRegistry.registeredBeanNamesByEntity()` | the configured beans' names per entity — the reaper's watermark sets, one per entity. An entity with no registered bean is not in the map. Configured, not running (Q2, as before): a hot-stopped bean still holds its entity's rows. It replaces `registeredBeanNames(entity)` (its one caller was the reaper) |
| `outbox/internal/OutboxReaper.reapOnce()` | `registeredBeanNamesByEntity()` → for each served schema → for each entity: `reader.reap(schema, entity, THAT entity's beans)` — the reader's reap and the store's `minOffset` / `deleteUpTo` were per entity already (`entity_type = ?`); only the reaper chose one entity |
| a trouble | keyed by (schema, entity): ONE WARN `reaper: schema <s>, entity <e> failed this pass; the other schemas and entities go on. …`, DEBUG lines while it lasts, ONE INFO `reaper: schema <s>, entity <e> trims again — after <n> failed passes` |
| the retired key | `@ConfigProperty(name = "summary.outbox.entity-type") Optional<String>`, read only to say at `start()`: `summary.outbox.entity-type=<e> is IGNORED: the key is retired (HARDEN-1). Every pass trims EACH entity that has a registered bean, each by min(last_offset) over that entity's own beans; an entity with no registered bean is never trimmed (a bean that comes back must find its rows). The line can be removed from the profile`. The start line: `reaper started: every <n>s, each entity that has a registered bean (now [cdr, wifi_flow]) trimmed by its own beans' bookmarks` |
| constructors | the CDI one (the retired key, the interval) and `OutboxReaper(reader, registry, intervalSeconds)` for tests and embedders. Every test that made `new OutboxReaper(reader, registry, "cdr", 60)` now makes the three-argument one |
| the jar's profiles | `btcl/lab`, `btcl/radius2`, `tcbl/dev` no longer carry `entity-type: cdr`; nor the profile `tools/lab/kinds-pin-e2e.sh` renders (an older jar defaults the key to `cdr` anyway) |
| the docs | decisions §18 (+ §17f's line overwritten with the new fact), README, architecture.md step 8, the deploy page (the key's row → retired; an upgrade row; a behaviour row) |

## 2 · The tests — red first

| test | what it proves | on the code of before |
|---|---|---|
| `OutboxReaperTest.every_entity_that_has_beans_is_trimmed_to_its_own_beans_minimum` | one schema, `cdr` beans at 7/5 and `wifi_flow` beans at 8/2, the rows interleaved in one id sequence (cdr odd, wifi even): cdr trimmed to 5 (1, 3, 5), wifi to 2 (2); 7 and 4, 6, 8 stay | RED (3 deleted, not 4) |
| `….a_wifi_flow_row_with_no_wifi_bean_registered_is_never_trimmed` | only cdr beans registered, a stale bookmark of an unregistered wifi bean at 8: cdr trimmed, every wifi row stays | green (a guard) |
| `….a_trouble_is_said_once_per_schema_and_entity_and_once_more_when_that_entity_trims_again` | a store away for 5 passes: one WARN per (schema, entity), one INFO each when back; both entities trimmed on the first pass back | RED |
| `….the_retired_entity_type_key_is_said_once_at_start_and_changes_nothing` | a profile naming `wifi_flow`: one INFO at start, and BOTH entities trimmed | RED |
| `….a_profile_without_the_retired_key_hears_nothing_about_it` | no word about the key | green (a guard) |
| `….the_jars_own_profiles_no_longer_set_the_retired_key` | btcl/lab, btcl/radius2, tcbl/dev | (added with the change) |
| the IT contract, BOTH engines: `the_reaper_trims_every_entity_that_has_beans_each_to_its_own_beans_minimum` | the first unit case on a real schema: the service's own registry serves it, its workers stopped before any row is written, the bookmarks set as drains would leave them, the REAL reaper | RED on PostgreSQL and on MySQL (3 deleted, not 4) |
| the IT contract, BOTH engines: `a_wifi_flow_row_with_no_wifi_bean_registered_is_never_trimmed` | the second unit case on a real schema | green (a guard; MB2 makes it red) |

The fake outbox (`testkit/FakeOutboxStore`) learnt rows OF an entity (`seedOf`, `idsOf`), as the real table has them; a row
seeded without one keeps today's meaning (it answers every entity), so no existing test changed its meaning. Existing tests
changed only in the words they expect (`SummaryBeanRegistryTest`'s S16 test: `schema btcl, entity cdr`), the constructor, and
`WifiProfileTest`'s sample key (the retired key → `summary.outbox.ping-topic`).

## 3 · Each rule broken once (fast runner, both labs; each original copied aside, the break, the tests, the original back with a FRESH mtime, byte equality asserted)

The tests run per mutant: `OutboxReaperTest`, `SummaryBeanRegistryTest`, `OutboxReapTest`, `PostgresTreeIT`, and the two new
contract tests on both engines (40 tests).

| | the break | red |
|---|---|---|
| MB1 | ONE watermark for every entity: every registered bean's bookmark counts in every entity's minimum | 5 — the per-entity test (unit, and the IT on both engines), the trouble test, the retired-key test |
| MB2 | an entity without beans trimmed: the store's delete loses its `entity_type` filter, so another entity's minimum trims it | 4 — both new contract tests on PostgreSQL and on MySQL (the fake is not the code under test, so the unit tests cannot see it) |
| MB3 | the `cdr` entity no longer trimmed | 11 — every reaper test, unit and IT, both engines, `PostgresTreeIT` |
| MB4 | a trouble said once per SCHEMA again, not per (schema, entity) | 2 — the new trouble test and the S16 test |
| MB5 | the retired key not said at start | 1 |

## 4 · The runs

| run | where | numbers |
|---|---|---|
| fast runner (red-first, green, mutants) | plain `javac` + the JUnit launcher on the classpath Maven resolved once (`dependency:build-classpath`, one short hold of the lock); main and test sources compiled fresh every run (no stale class can survive a mutant) — NOT Maven; every number of record is Maven's | green: 83/83 (the touched classes, the new ITs on both engines, `PostgresTreeIT`) |
| fast runner, every unit class (a preview of the gate) | the worktree | 442 found, 442 passed (one class reads a relative path: run from the project directory, as Maven does) |
| **the gate**: Maven `mvn -o -B verify` on a git-archive clean copy of 1fe828d, both labs | `~/harden1-work/logs/gate-b-1fe828d.log` | **unit 442 · 0 · 0 · 0; ITs 92 · 0 · 0 · 3** — the four new contract tests present and passed in failsafe's reports (both engines); no log line holds the MySQL password; the head repository 0 files |

Maven ran offline under `flock /home/mustafa/telcobright-projects/.one-maven.lock`, with an EMPTY head repository
(`~/harden1-work/m2-head-ss`) and the read-only tails `~/.m2/arch-ss-review-repo`, `~/.m2/summary-service-repo`,
`~/.m2/repository` (SS-0008's way): nothing written to `~/.m2`.

## 5 · The labs (mine; REMOVED 2026-10-25 05:30 +06; made again for B4 and REMOVED 06:30)

| | |
|---|---|
| PostgreSQL 16 | docker `h1b-pg16`, 127.0.0.1:7457 only, trust on loopback — no password exists; database `routesphere` and the four roles, statement for statement as `tools/lab/pg-lab.sh` |
| MySQL 5.7.44 | docker `h1b-mysql57`, 127.0.0.1:7357 only; root's password generated into a 0600 file in my work folder, set over STDIN once the real server answered, exported into the test process's environment only (`SUMMARY_IT_MYSQL_PASSWORD`, the ITs' own road) — never echoed, never in a commit; every log checked with `grep -c -F -f <the file>`: 0 |
| Kafka | none: `KafkaListenersIT` self-skips (its 3), as SS-0007 and SS-0008 ran it |

## 6 · Open points

| | |
|---|---|
| the bed's rendered profile | probably still sets `summary.outbox.entity-type: cdr` (the jar's did): harmless — one INFO at start. Removing the line is devops's, in a window; nothing breaks if it stays |
| the first pass on a WiFi tenant | CLOSED by B4 (§7): the backlog goes in bounded batches within a time budget per pass |
| `ad_cdr` rows | no bean reads them since the ad became a Call (SS-0008); by the rule they are never trimmed. Removing them, if wanted, is an operator's act |

## 7 · B4 — the trim deletes in BOUNDED batches (the coordinator's item, 2026-10-25 05:3x; my open point 1)

### 7.1 · As built

| where | what |
|---|---|
| `outbox/spi/OutboxStore` | `deleteUpTo(entity, maxId, limit)` — at most `limit` rows of the entity with `id <= maxId`, the lowest ids first; `countUpTo(entity, maxId)` — what a bounded trim left. The unbounded `deleteUpTo(entity, maxId)` stays (the tests' watermark helpers use it); the reaper no longer does |
| `runtime/internal/JdbcOutboxStore` | MySQL: `delete sa from summary_affected sa join (select id from summary_affected ignore index (primary) where entity_type=? and id<=? order by id limit ?) batch on batch.id = sa.id`. PostgreSQL: `delete from summary_affected where id = any (array(select id from summary_affected where entity_type=? and id<=? order by id limit ?))`. Both read the batch's ids through billing-core's `(entity_type, id)` index (`ix_entity`; `ix_summary_affected_entity`) and delete by primary key — why these and not the others: §7.3 |
| `outbox/api/OutboxReader` | `reap(schema, entity, beans, limit)` — ONE bounded batch, its own transaction (it replaces the unbounded `reap(schema, entity, beans)`, whose one caller was the reaper); `passedRowsLeft(schema, entity, beans)` — one read, for the INFO. Its per-batch INFO line is the old one plus `a batch of at most <n>` |
| `outbox/internal/OutboxReaper` | `summary.outbox.reap-batch` (10 000) and `summary.outbox.reap-pass-budget-ms` (5 000). Per pass: a budget from the pass's start; per (schema, entity): the FIRST batch always, then more while a batch comes back full and the budget lasts; a full last batch counts what it left. ONE INFO per pass that stops on the budget: `reaper: this pass stopped on its budget (summary.outbox.reap-pass-budget-ms=<ms>) — the next pass goes on with the rows left: schema <s>, entity <e>: <n>[; …]`. A batch below 1 or a negative budget refuses the start in words. A failure in the middle of a pair's batches: the committed batches stay counted, the trouble is said once (S16) |
| decision | the budget is the PASS's (as asked), but every (schema, entity) gets its first batch whatever is left of it: otherwise a big backlog early in the order (a schema, an entity) would take every pass's budget and starve the others — the `cdr` minute of rows among them. So a pass is bounded by the budget plus one batch per (schema, entity) |

### 7.2 · Tests — red first

| test | what it proves | on the code before B4 (the batch ignored: one big delete) |
|---|---|---|
| `OutboxReaperBatchesTest.a_backlog_of_25000_passed_wifi_flow_rows_is_cleared_in_three_transactions_of_at_most_10000_when_the_budget_allows` | the fake records each bounded batch: 10 000 + 10 000 + 5 000, each its own transaction of the reader; no budget INFO | RED |
| `….a_tiny_budget_leaves_rows_and_the_next_pass_goes_on_from_there` | budget 0: 10 000 a pass (the first batch), the lowest ids first; the INFO says 15 000 left, then 5 000; nothing said when nothing is left | RED |
| `….the_cdr_entitys_minute_of_rows_goes_in_one_transaction_every_pass_even_behind_a_wifi_flow_backlog_with_no_budget` | 30 cdr rows passed: ONE bounded batch of 30 (as before: one transaction, every passed row), even with budget 0 beside a 25 000-row wifi backlog | RED |
| `….a_batch_below_one_row_or_a_negative_budget_is_refused_in_words` | the start refuses | green (the refusal came with the keys) |
| IT contract, BOTH engines: `a_backlog_of_25000_passed_wifi_flow_rows_goes_in_batches_of_at_most_10000_the_lowest_ids_first` | the real statements: 10 000, 10 000, 5 000, 0 — and after the first, the lowest id left is 10 001 | RED on PostgreSQL and on MySQL (25 000 in one) |
| IT contract, BOTH engines: `a_tiny_budget_leaves_wifi_flow_rows_for_the_next_pass_and_the_cdr_minute_still_goes_in_the_first_batch` | the service's reaper, budget 0: pass 1 = the 30 cdr rows + 10 000 wifi rows, 15 000 left; pass 2 = 10 000; pass 3 = 5 000; the table empty | RED on PostgreSQL and on MySQL |

Red first: 7 red (3 unit + 2 per engine); the existing tests run beside them (`OutboxReaperTest`, `SummaryBeanRegistryTest`) green on that code. Green: the fast runner 142/142 (the new and
touched unit classes, `OutboxConsumerIT`, `PostgresOutboxConsumerIT`, `MySqlTreeIT`, `PostgresTreeIT` in full, both engines).

### 7.3 · Measured in my labs — one 10 000-row batch on each engine

Rows of ~4 KB (a 4 096-character text; PostgreSQL's of 128 distinct md5s, little to compress), `cdr` and `wifi_flow` interleaved
in one id sequence (odd / even) unless said; billing-core's DDL on each engine; the times are the statement's own (MySQL client,
psql `\timing`), each batch committed on its own unless said.

| engine, setting | table | statement | the batch(es) |
|---|---|---|---|
| PostgreSQL 16.13, docker defaults (`shared_buffers` 128 MB) | 60 000 rows, 324 MB | as built (`id = ANY (ARRAY(…))`) | 0.44 s · 0.84 s · 0.78 s (three in a row) |
| same | 100 000 `cdr` rows held below the watermark, then 10 000 `wifi_flow` | as built | 0.36 s |
| same, `EXPLAIN ANALYZE`, rolled back | 60 000 rows | `id IN (sub-select)` | 0.33 s — a hash semi-join over a SEQUENTIAL SCAN of the whole table (grows with the table) |
| same | same | `id = ANY (ARRAY(…))` (as built) | 0.37 s — index-only scan on `(entity_type, id)`, then the primary key |
| same | same | ctid batch | 0.27 s — but its sub-select walked the primary key across both entities |
| MySQL 5.7.44, production-like (buffer pool 2 GB, redo 2 × 512 MB, `io_capacity` 2 000) | 60 000 rows, 315 MB | as built (join to the ids read with `IGNORE INDEX (PRIMARY)`) | 0.59 s · 0.10 s · 0.09 s |
| same | same | plain `DELETE … ORDER BY id LIMIT 10000` | 0.46 s · 0.16 s · 0.21 s |
| same | 200 000 rows, 100 000 `wifi_flow`: ten batches | as built / plain | 0.18–0.69 s / 0.12–0.87 s per batch, neither growing |
| same | 100 000 held `cdr` rows, then 10 000 `wifi_flow` | as built / plain | 0.30 s / 0.41 s (the plain one walks the 400 MB of held rows, hot in the pool) |
| MySQL 5.7.44, the image's defaults (buffer pool 128 MB, redo 2 × 48 MB, `io_capacity` 200) | 100 000 held `cdr` rows, then 10 000 `wifi_flow` | as built / plain | **0.25 s / 4.32 s** — the plain one plans through the PRIMARY key (`EXPLAIN`: `key PRIMARY … Using where`) and reads every held row from disk |
| same | 60 000 rows, interleaved, the table 2.5 × the pool | as built / plain | 3.0 s · 13.2 s · 25.5 s / 1.8 s · 13.8 s — both I/O-bound: each batch dirties ~80 MB of pages, and the next waits for the page cleaner and purge. A box with a pool this small is slow at any delete of this size; the budget stops such a pass after its first batch per pair, and the next pass comes a minute later |

The choice: on MySQL not the plain `DELETE … ORDER BY id LIMIT ?` — 5.7 plans it through the PRIMARY key whatever the
statistics say (`entity_type` has cardinality 1 in InnoDB's sampled stats) and walks every row of another entity held below the
watermark; the join reads its ids through `(entity_type, id)` only, and costs the same where the plain one is fine. On
PostgreSQL the keyed `= ANY (ARRAY(…))` — index-driven on both sides; `IN (…)` turns into a scan of the whole table, and the
ctid batch's id read walks the primary key across entities.

### 7.4 · Mutants (fast runner, both labs; each original aside, the break, the tests, the original back with a FRESH mtime, byte equality asserted)

All of part B's mutants were run again on the B4 code (the tests per mutant: `OutboxReaperTest`, `OutboxReaperBatchesTest`,
`SummaryBeanRegistryTest`, `OutboxReapTest`, `PostgresTreeIT`, and the four new contract tests on both engines — 48 tests). MB2
now breaks the BOUNDED statements (the reaper no longer uses the unbounded one), one per engine.

| | the break | red |
|---|---|---|
| **MB6 (B4)** | **the batch ignored: the reader's batch issues the one big delete of every passed row** | **7** — the three batch tests of `OutboxReaperBatchesTest`, the two batch tests of the contract on PostgreSQL and on MySQL |
| MB1 | one watermark for every entity | 10 |
| MB2p | an entity without beans trimmed — PostgreSQL's bounded id read loses its `entity_type` filter | 3 — the per-entity, the no-wifi-bean and the tiny-budget contract tests on PostgreSQL |
| MB2m | the same on MySQL | 3 — the same three on MySQL |
| MB3 | the `cdr` entity no longer trimmed | 14 |
| MB4 | a trouble said once per SCHEMA again | 2 |
| MB5 | the retired key not said at start | 1 |

Three more reds appeared in those runs, one each in MB2p, MB2m and MB6, in tests none of those breaks can reach:
`PostgresTreeIT.billing_writes_first_summary_starts_second_every_row_is_summed` (`expected: <3> but was: <2>`; the test calls no
reaper), `SummaryBeanRegistryTest.a_ping_that_cannot_be_read_or_names_no_tenant_wakes_every_worker_as_before` and
`….every_schema_has_its_own_worker_and_its_own_bookmark_for_every_bean` (`each schema's summary holds its own tier's record only
==> expected: <1> but was: <2>`; the fakes record a rolled-back attempt's SQL too). Those mutant runs overlapped my lab
measurements (§7.3: 200 000 rows of 4 KB inserted into each engine on the same disk). Both classes, run three times on the
final code with nothing else running: 28/28 each time; and in the gate. I take them for timing tests that a loaded box can
upset — a note for the summary-service owner, not something B4 changed.

### 7.5 · The gate

Maven `mvn -o -B verify` on a git-archive clean copy of **f09a6b8** (the B4 head), both labs, one hold of the lock
(06:25:27–06:30:22 +06): **unit 446 · 0 · 0 · 0** (442 + `OutboxReaperBatchesTest`'s 4), **ITs 96 · 0 · 0 · 3** (92 + the two
batch tests on each engine: `PostgresOutboxConsumerIT` 45 + `PostgresTreeIT` 9, `OutboxConsumerIT` 36 + `MySqlTreeIT` 2,
`BuildBakesNoTenantIT` 1, `KafkaListenersIT` 3 skipped — no broker). BUILD SUCCESS; 0 ERROR lines; the four new contract tests
present and passed in failsafe's reports on both engines; no log line holds the MySQL password; the head repository 0 files.
No red.

## 8 · State (newest last; real times, `date`)

- 04:3x +06 — worktree `worktrees/reaper-entities` made from `kinds` ead3f2b.
- 05:03 — RED (fast runner, outside Maven; my labs PostgreSQL 16 127.0.0.1:7457 trust, MySQL 5.7.44 127.0.0.1:7357):
  the new tests on the code of before — 5 red: `OutboxReaperTest` (each entity to its own minimum; the trouble said per
  (schema, entity); the retired key said at start) and the contract's per-entity test on BOTH engines. The two "no wifi bean
  registered" tests are green on the code of before too — it trims `cdr` only — they guard the rule against a change.
- 05:08 — GREEN: `OutboxReaper` per (schema, entity); `SummaryBeanRegistry.registeredBeanNamesByEntity`; the retired key
  said once at start; the jar's three profiles no longer set it. Fast runner: 83/83 (the touched unit classes, the new IT
  tests on both engines, `PostgresTreeIT`).
- 05:15 — mutants MB1–MB5 all red (§3), each original back with a fresh mtime, equality asserted.
- 05:17 — docs: decisions §18 (+ §17f's line overwritten with the new fact), README, architecture.md, the deploy page
  (the key row, an upgrade row, a behaviour row).
- 05:17 — committed B1 9008c3a (code + tests) and B2 1fe828d (docs + this file); the gate queued for the lock.
- 05:2x — every unit class on the fast runner (a preview): 442/442. The report drafted (§0–§6); the gate's numbers PENDING.
- 05:30 — THE GATE (one hold of the lock, 05:23:43–05:30:05) on a git-archive clean copy of 1fe828d: unit 442 · 0 · 0 · 0,
  ITs 92 · 0 · 0 · 3 (both engines), BUILD SUCCESS; no log line holds the password; the head repository 0 files. The labs
  REMOVED (`h1b-pg16`, `h1b-mysql57`, their volumes, the password file); the clean copy removed; the Maven logs kept in
  `~/harden1-work/logs/`. The report finished. ALL ITEMS OF PART B DONE (this commit).
- 05:3x — B4 asked by the coordinator (bounded batches; my open point 1). Labs up again (a new password).
- 05:37–05:42 — PostgreSQL shapes compared on 60 000 lab rows (`IN` = a whole-table scan; `= ANY (ARRAY(…))` = index-only;
  ctid = the primary key across entities); MySQL: the plain `DELETE … ORDER BY id LIMIT` plans through the PRIMARY key and
  filters the entity. The statements chosen (§7.3).
- 05:47 — RED (fast runner): the B4 tests on the code with the batch ignored — 7 red (3 unit, 2 per engine); every other test green.
- 05:48–05:57 — GREEN: the bounded batch; the fast runner 142/142 (both engines, the IT classes in full).
- 05:59–06:04 — measured (§7.3); the measurement container removed.
- 06:10 — mutants on the B4 code: MB6 and MB1–MB5 (MB2 per engine) all red; three timing reds of untouched tests under the load
  of my measurements — those classes 3 × 28/28 alone (§7.4).
- 06:14 — docs (decisions §18, README, architecture, the deploy page's keys and rows). NEXT: commit B4, then the gate.
- 06:15 — B4 committed 4ca45a0, and f09a6b8 (the store's comment: the measured reason, not a growth I first assumed); the gate queued.
- 06:30 — THE GATE of B4 (one hold of the lock, 06:25:27–06:30:22) on a git-archive clean copy of f09a6b8: unit 446 · 0 · 0 · 0,
  ITs 96 · 0 · 0 · 3 (both engines), BUILD SUCCESS; no log line holds the password; the head repository 0 files. The labs
  REMOVED again (`h1b-pg16`, `h1b-mysql57`, their volumes and the measurement data in them, the password file; the measurement
  container already at 06:04); the clean copy removed. B4 DONE (this commit).
