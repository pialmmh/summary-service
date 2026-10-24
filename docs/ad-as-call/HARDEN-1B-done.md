from: SS (HARDEN-1 B)  to: ARCH  kind: done  date: 2026-10-25  branch: `reaper-entities` (from `kinds` ead3f2b)  ·  nothing pushed

# HARDEN-1 B — summary-service: the reaper trims every entity that has beans, each by its own beans' bookmarks

The brief: `~/arch-drafts/launch/HARDEN-1-billing-summary-brief.md`, part B. The rules: `~/arch-drafts/launch/RULES-2026-10-25.md`.

## 0 · What to relay

| | |
|---|---|
| the branch | `reaper-entities`, from `kinds` ead3f2b: **9008c3a** B1 (the code, the tests, the jar's profiles) · **1fe828d** B2 (the docs, the state file) · the commit that carries this note's final form. Not pushed; `kinds`, `master-merge` and `postgres-ad-call` not touched |
| the rule, as built | every reaper pass trims EACH entity that has ≥ 1 registered bean, each by `min(last_offset)` over THAT entity's beans only; an entity with no registered bean is never trimmed (SS-0008 D1); each (schema, entity) its own transaction; the once-said WARN / INFO of S16 now per (schema, entity); `summary.outbox.entity-type` retired — set, ONE INFO at start says it is ignored and why |
| what a deployment sees | the first pass after this jar deletes the `wifi_flow` rows every WiFi destination bean has passed (never trimmed until now: on a WiFi tenant that has run a while, one large `DELETE` per tier schema, in its own transaction). The `cdr` trims are exactly as before. A rendered profile that still sets `summary.outbox.entity-type` (the bed's, probably) gets the INFO; the line can go (the deploy page says so) |
| the gate (Maven `verify`, a git-archive clean copy of 1fe828d, both engines) | **unit 442 · 0 · 0 · 0, ITs 92 · 0 · 0 · 3**, BUILD SUCCESS (05:23:43–05:30:05 +06) — the baseline of `kinds` (unit 436 · 0 · 0 · 0, ITs 88 · 0 · 0 · 3) + `OutboxReaperTest`'s 6 + the 2 contract tests on each engine. ITs: `PostgresOutboxConsumerIT` 43 + `PostgresTreeIT` 9 (PostgreSQL 52), `OutboxConsumerIT` 34 + `MySqlTreeIT` 2 (MySQL 36), `BuildBakesNoTenantIT` 1, `KafkaListenersIT` 3 skipped (no broker, as SS-0007/SS-0008). No red; 0 ERROR lines |
| red first | 5 red on the code of before (§2) |
| mutants | MB1–MB5 (the brief's three + two), all red (§3) |

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

## 5 · The labs (mine; REMOVED 2026-10-25 05:30 +06)

| | |
|---|---|
| PostgreSQL 16 | docker `h1b-pg16`, 127.0.0.1:7457 only, trust on loopback — no password exists; database `routesphere` and the four roles, statement for statement as `tools/lab/pg-lab.sh` |
| MySQL 5.7.44 | docker `h1b-mysql57`, 127.0.0.1:7357 only; root's password generated into a 0600 file in my work folder, set over STDIN once the real server answered, exported into the test process's environment only (`SUMMARY_IT_MYSQL_PASSWORD`, the ITs' own road) — never echoed, never in a commit; every log checked with `grep -c -F -f <the file>`: 0 |
| Kafka | none: `KafkaListenersIT` self-skips (its 3), as SS-0007 and SS-0008 ran it |

## 6 · Open points

| | |
|---|---|
| the bed's rendered profile | probably still sets `summary.outbox.entity-type: cdr` (the jar's did): harmless — one INFO at start. Removing the line is devops's, in a window; nothing breaks if it stays |
| the first pass on a WiFi tenant | deletes the `wifi_flow` backlog the WiFi destination beans have passed, in one statement per tier schema. On the bed this is small; on a long-running WiFi tenant it is one large delete (PostgreSQL: one transaction, then autovacuum). If that is a worry, a bounded delete (`… AND id <= ? LIMIT n` on MySQL, a ctid batch on PostgreSQL) is a small follow-up — not done here |
| `ad_cdr` rows | no bean reads them since the ad became a Call (SS-0008); by the rule they are never trimmed. Removing them, if wanted, is an operator's act |

## 7 · State (newest last; real times, `date`)

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
