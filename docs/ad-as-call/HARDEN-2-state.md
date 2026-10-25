# HARDEN-2 — summary-service — state (kept current after every item; a stop loses nothing)

The brief: `~/arch-drafts/launch/HARDEN-2-tree-bearer-clients-brief.md`. The rules: `~/arch-drafts/launch/RULES-2026-10-25.md`.
Branch `tree-bearer` (worktree `summary-service/worktrees/tree-bearer`), from `postgres-ad-call` c76d098 (the bed line).
Nothing pushed. The report: `docs/ad-as-call/HARDEN-2-done.md`.

## Work folder (mine): `~/harden2-work/`

| | |
|---|---|
| fast runner | `jt-ss.sh <tree> <tests…>` — plain javac + the JUnit launcher on the classpath Maven resolved once (`ss-cp.txt`); main and test sources compiled FRESH every run. NOT Maven: red-first and mutants without the lock. Every number of record is Maven's |
| mutants | `mutants.py ss` — original copied aside, the break, the named tests, the original back with a FRESH mtime, byte equality asserted |
| the gate | `gate-ss.sh <commit> <label>` — ONE hold of the lock: a git-archive clean copy, `mvn -o -B verify` (unit + ITs) on BOTH engines, an EMPTY head repository with read-only tails (`~/.m2/arch-ss-review-repo`, `~/.m2/summary-service-repo`, `~/.m2/repository`): nothing written to `~/.m2` |

## Labs (mine; 127.0.0.1 only; to be REMOVED at the end)

| | |
|---|---|
| PostgreSQL 16 | docker `h2-pg16`, 127.0.0.1:7461, trust on loopback — no password exists; database `routesphere` and the four roles (`tools/lab/pg-lab.sh`'s statements) |
| MySQL 5.7.44 | docker `h2-mysql57`, 127.0.0.1:7361; root's password generated into a 0600 file in my work folder, set over STDIN, exported only into the test process's environment (`SUMMARY_IT_MYSQL_PASSWORD`); never printed; every gate log checked with `grep -c -F -f <the file>` |
| Kafka | none: `KafkaListenersIT` self-skips (3), as SS-0007/SS-0008/HARDEN-1 ran it |

## Baseline — the bed line c76d098, a git-archive clean copy, `mvn -o -B verify`, both engines

| run | tests · failures · errors · skipped | when |
|---|---|---|
| unit | **446 · 0 · 0 · 0** | one hold of the lock 16:08:48–16:13:46 +06 (`~/harden2-work/logs/gate-ss-baseline-c76d098.log`), BUILD SUCCESS |
| ITs | **96 · 0 · 0 · 3** — `PostgresOutboxConsumerIT` 45 + `PostgresTreeIT` 9 (PostgreSQL 54), `OutboxConsumerIT` 36 + `MySqlTreeIT` 2 (MySQL 38), `BuildBakesNoTenantIT` 1, `KafkaListenersIT` 3 skipped (no broker) | the same run; 0 lines hold the lab password; the head repository 0 files |

## Items

| # | state | commit | proof |
|---|---|---|---|
| 0 | DONE | — | worktree made 15:4x; labs up 16:08; the baseline queued 16:09 |
| 1 | DONE — the bearer on BOTH tree-road clients: `summary.tenants.prime-context.token-ref` (the tree, `PrimeContextTreeSource`) and `summary.contexts.<name>.token-ref` (a bean's context, `ConfigManagerClient`); the start's lines; an empty variable refuses the start | (this commit) | red-first on a skeleton: 18 of 21 red (the 3 green: no header when none is named — today's behaviour, guards); green: fast runner 71/71, then every unit class 470/470 (446 + 24); mutants SM1–SM13 all red (below) |
| 2 | the docs (README, decisions, the profile page, the bed profile's comment) and the report | | |
| 3 | the gate: unit + ITs, both engines, a git-archive clean copy of the head; the labs removed | | |

## State lines (newest last; real times, `date`)

- 15:49 +06 — worktree `worktrees/tree-bearer` from `postgres-ad-call` c76d098. Two tree-road clients found (the brief names one):
  `context/internal/ConfigManagerClient` (a bean's context, `summary.contexts.<name>.base-url`) AND `tenancy/internal/PrimeContextTreeSource`
  (the root's tree in `summary.tenants.mode: tree`, `summary.tenants.prime-context.base-url` — the bed's profile uses it). Both POST
  `/get-specific-tenant-root`; PC-0025's flip row lists both. Both get the bearer: after the flip, the one left out would be refused.
- 16:08 — labs up (above). 16:09 — the baseline's gate queued for the lock.
- 16:14 — RED (fast runner, `~/harden2-work/logs/ss-red.log`): the new tests on a skeleton of the API (the signatures in place, nothing
  done) — 18 of 21 red: TreeRoadBearerTest 7 of 7, PrimeContextTreeSourceTest 2 of 3, ContextRegistryBearerTest 2 of 3, TenantWatcherBearerTest
  2 of 3, SummaryBootstrapTest 5 of 5. The 3 green are the "no variable named = no Authorization header" guards (today's behaviour).
- 16:16 — GREEN: `treeroads/api/TreeRoadBearer` (one rule for both clients); `PrimeContextTreeSource` and `ConfigManagerClient` send it on
  their one POST and say a 401 / 403 with the bearer's variable; `TenantWatcher` (block `summary.tenants.prime-context.`) and
  `ContextRegistry` (block `summary.contexts.<name>.`) read it from the environment by the variable's name (each a constructor over a
  given configuration and environment, for tests and embedders); `StartEndpoints.treeRoadBlocks` names the blocks of a start that ask a
  tree road; `SummaryBootstrap` says one line per block after the store's line and REFUSES the start on a named variable that is not set
  or empty — before anything is dialled; print-only prints them as `SECRET tree roads: …` (a refusal → exit 4). The two-argument
  `PrimeContextTreeSource` constructor is gone (every construction states its bearer); `ConfigManagerClient.fetchTenantRoot` takes the
  bearer (the two test stand-ins follow). Fast runner 71/71; every unit class 467/467.
- 16:18 — three wiring tests added (the service's own `TenantWatcher(registry)`, `ContextRegistry(client)` and the bootstrap's default
  environment read the PROCESS environment: `env:PATH`, a variable every process has and no secret; a guard asserts the stand-in on
  127.0.0.1 is the only url dialled) — each shown red by its mutant (SM11–SM13).
- 16:18–16:22 — mutants (`~/harden2-work/logs/ss-mutants.out`; the 56 tests of the six touched classes), each original restored byte for
  byte (sha256 + cmp) with a fresh mtime:

  | | the break | red |
  |---|---|---|
  | SM1 | the tree road's header never sent | 4 |
  | SM2 | the context road's header never sent | 4 |
  | SM3 | the value logged: the start's line carries the token | 8 |
  | SM4 | an empty variable accepted | 4 |
  | SM5 | a default variable read when no key is named | 9 (incl. 4 tests of before: the default profile's start now refuses) |
  | SM6 | the watcher builds its tree source without the block's bearer | 4 |
  | SM7 | the context registry asks without the context's bearer | 4 |
  | SM8 | the start neither says nor checks the bearers | 5 |
  | SM9 | a 401's words on the tree road carry the Authorization header (the watcher logs them) | 2 — the whole-log test, the 401's words |
  | SM10 | the contexts' blocks left out of the start's check | 4 |
  | SM11 | the service's watcher reads no environment | 1 — its wiring test |
  | SM12 | the service's context registry reads no environment | 1 — its wiring test |
  | SM13 | the service's start reads no environment | 1 — its wiring test |
- 16:24 — every unit class green again on the restored tree: 470/470. Item 1 committed.
