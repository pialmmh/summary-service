from: SS (HARDEN-2)  to: ARCH  kind: done  date: 2026-10-25  branch: `tree-bearer` (from `postgres-ad-call` c76d098, the bed line)  ·  nothing pushed

# HARDEN-2 — summary-service sends a bearer to prime-context's tree roads

The brief: `~/arch-drafts/launch/HARDEN-2-tree-bearer-clients-brief.md`. The rules: `~/arch-drafts/launch/RULES-2026-10-25.md`.
Why: prime-context PC-0025 added `primecontext.tree-roads.guard = open | bearer` (default `open`); with `bearer` a tree-road call
without a token is answered 401. Before the owner flips it, summary-service must be able to send one.

## 0 · What to relay

| | |
|---|---|
| the branch | `tree-bearer`, from `postgres-ad-call` c76d098: **37e7b82** item 1 (code + tests + the state file) · **32e8056** item 2 (the docs, this report drafted) · the commit that carries this report's numbers (docs only). Not pushed; `postgres-ad-call` not touched |
| TWO clients, not one | the brief names `context/internal/ConfigManagerClient`; the service has a second caller of the same road — `tenancy/internal/PrimeContextTreeSource`, the root's tree in `summary.tenants.mode: tree`, which **the bed's profile uses** (PC-0025's flip row lists both). Both get the bearer: after the flip, the one left out would be refused (the tree read 401 = no new reseller schema is ever served) |
| the rule, as built | per block, the shape of `summary.store.password-ref`: `summary.tenants.prime-context.token-ref: env:<VAR>` (the tree) and `summary.contexts.<name>.token-ref: env:<VAR>` (a bean's context) → that block's `POST /get-specific-tenant-root` carries `Authorization: Bearer <value>`. Not set → no Authorization header: the calls of before, exactly; the environment is not read |
| the start | one line per block that asks a tree road, after the store's line: `tree roads: bearer from <VAR> (<key>)` or `tree roads: no bearer (<key> is not set)` — the NAME, never the value; print-only prints them as `SECRET tree roads: …`. A named variable that is not set or empty refuses the start in words (`REFUSING TO START: the environment variable <VAR> is empty — <key> names it as the token for prime-context's tree roads. …`), before anything is dialled (print-only: exit 4); a `token-ref` that is not `env:<NAME>` is refused and not shown |
| a 401 / 403 | the tree's error (logged by the watcher) and the context's WARN name the bearer that was shown — never the value |
| baseline (the bed line c76d098, a git-archive clean copy, Maven `verify`, both engines) | **unit 446 · 0 · 0 · 0, ITs 96 · 0 · 0 · 3** (16:08:48–16:13:46 +06) |
| the gate (a git-archive clean copy, Maven `verify`, both engines) | **unit 470 · 0 · 0 · 0, ITs 96 · 0 · 0 · 3**, BUILD SUCCESS — Maven `verify`, a git-archive clean copy of **32e8056** (all of the round's code; the head adds only this report's numbers), both labs, one hold of the lock 16:30:07–16:35:19 +06 (`~/harden2-work/logs/gate-ss-head-32e8056.log`). Unit 470 = the baseline's 446 + the 24 new (`TreeRoadBearerTest` 7, `PrimeContextTreeSourceTest` +3, `ContextRegistryBearerTest` 4, `TenantWatcherBearerTest` 4, `SummaryBootstrapTest` +6). ITs = the baseline's, class for class: `PostgresOutboxConsumerIT` 45 + `PostgresTreeIT` 9 (PostgreSQL), `OutboxConsumerIT` 36 + `MySqlTreeIT` 2 (MySQL), `BuildBakesNoTenantIT` 1, `KafkaListenersIT` 3 skipped (no broker). No red. 0 lines hold the lab password; the two test tokens are in no line of the Maven log and in no surefire / failsafe report; the head repository 0 files |
| red first | 18 of 21 red on a skeleton of the API (§2) |
| mutants | SM1–SM13, all red (§3) |
| for the flip | the bed's RENDERED profile gains `token-ref: env:<VAR>` under `summary.tenants.prime-context` and secreteer's file the variable — then a restart, and the start line says `tree roads: bearer from <VAR>` — BEFORE the guard is flipped (prime-context's `tools/deploy/window/pc25-tree-roads-guard.md`, G0). The jar's own radius2 profile carries the line COMMENTED (no behaviour change) |

## 1 · As built

| where | what |
|---|---|
| `treeroads/api/TreeRoadBearer` (new package: the tree roads' bearer, one rule for both clients) | `of(config, block, environment)` — no `<block>token-ref` → none; `env:NAME` → the variable's value, trimmed; not set / empty → `REFUSING TO START …` naming the variable and the key; not `env:NAME` → refused, not shown. `none(block)`, `applyTo(header)`, `said()`, `toString()` = the line |
| `tenancy/internal/PrimeContextTreeSource` | the constructor takes the bearer (the two-argument one is gone: every construction states its bearer); `treeRoadPost(url)` puts it on the one POST; a 401 / 403 appends the bearer's line to the IOException |
| `context/internal/ConfigManagerClient` | `fetchTenantRoot(baseUrl, tenant, bearer)`; the same POST helper; a 401 / 403 WARN ends with the bearer's line |
| `tenancy/api/TenantWatcher` | builds its source with `TreeRoadBearer.of(config, "summary.tenants.prime-context.", environment)`; a constructor over a given configuration and environment (the CDI one: `ConfigProvider` + `System::getenv`) |
| `context/api/ContextRegistry` | asks a context with `TreeRoadBearer.of(config, "summary.contexts.<name>.", environment)`; a constructor over a given configuration and environment (the CDI one: `ConfigProvider` + `System::getenv`) |
| `registry/internal/StartEndpoints.treeRoadBlocks` | the blocks of a start that ask a tree road: the tree's in tree mode, then each context an enabled bean names whose source is `config-manager` |
| `registry/internal/SummaryBootstrap` | `treeRoadBearerLines`: each block's bearer read at start (`environment`, `System::getenv`; a test gives its own), one line each after the store's line — or the refusal; print-only prints them as `SECRET` lines |
| the jar's radius2 profile | the key, COMMENTED, under `summary.tenants.prime-context` |
| the docs | decisions §19, README, `docs/ad-as-call/postgres-ad-profile.md` (the YAML, the start's lines, the refusals, the upgrade row, the two keys) |

## 2 · Tests — red first

| test | what it proves | on a skeleton of the API |
|---|---|---|
| `TreeRoadBearerTest` (7) | no key → no bearer, the environment not read; the header from the environment; the line (variable + key, never the value; `toString`); not set / `""` / `"  "` refused in words; six wrong references (the token among them) refused and not shown; trimmed; each block its own key and variable | 7 RED |
| `PrimeContextTreeSourceTest.the_bearer_its_block_names_rides_the_tree_road` | the stand-in (127.0.0.1) receives `Bearer <token>` | RED |
| `….with_no_bearer_named_the_tree_road_is_asked_with_no_authorization_header_as_before` | no header | green (a guard) |
| `….a_refusal_of_the_tree_road_says_which_bearer_was_shown_never_its_value` | 401, with and without a bearer | RED |
| `ContextRegistryBearerTest` (3 + 1) | through the registry and the real client: the context's bearer rides its road; none named → no header (green, a guard); a 401 WARN names the bearer, never the value; (+ the CDI wiring, below) | 2 RED |
| `TenantWatcherBearerTest` (3 + 1) | the service's watcher in tree mode (a profile → the real source): the tree read with the bearer; none named → no header (green, a guard); an empty variable refuses the watcher; (+ the CDI wiring) | 2 RED |
| `SummaryBootstrapTest` (+5) | the start's lines for both blocks after the store's line, never a value; an empty variable refuses the start before any bean is registered, nothing dialled; no key → `no bearer` for the context, nothing said of a tree in one-schema mode, no variable read; print-only `SECRET tree roads: …` exit 0 / the refusal exit 4; **a capture of EVERY class's log lines** (the root logger, each line with its exceptions' messages) across the start, a tree read refused 401 by a stand-in (the watcher's ERROR and its exception) and a context read refused 401 (the WARN): the start's line and each refusal's words present, the token in no line — and the stand-in DID receive it on both roads | 5 RED |
| three wiring tests, added after the first green | the service's own `TenantWatcher(registry)`, `ContextRegistry(client)` and the bootstrap's default environment read the PROCESS environment (`token-ref: env:PATH` — a variable every process has, not a secret; a guard asserts the stand-in on 127.0.0.1 is the only url dialled) | each shown red by its mutant (SM11–SM13) |

Existing tests changed: the two `ConfigManagerClient` stand-ins (`SummaryBootstrapTest`, `SummaryBootstrapKindsTest`) override the new
signature; `PrimeContextTreeSourceTest`'s five calls state `NO_BEARER`; `testkit/LogCapture` gained `ofEverything()` and
`linesWithTheirExceptions()` (the existing `lines()` unchanged).

## 3 · Each rule broken once (fast runner; each original copied aside, the break, the tests, the original back with a FRESH mtime, sha256 + cmp equal)

The tests per mutant: `TreeRoadBearerTest`, `PrimeContextTreeSourceTest`, `ContextRegistryBearerTest`, `TenantWatcherBearerTest`,
`SummaryBootstrapTest`, `StartEndpointsTest` (56).

| | the break | red |
|---|---|---|
| SM1 | the tree road's header never sent | 4 |
| SM2 | the context road's header never sent | 4 |
| SM3 | the value logged: the start's line carries the token | 8 |
| SM4 | an empty variable accepted | 4 |
| SM5 | a default variable read when no key is named | 9 (4 of them tests of before: the default profile's start now refuses) |
| SM6 | the watcher builds its tree source without the block's bearer | 4 |
| SM7 | the context registry asks without the context's bearer | 4 |
| SM8 | the start neither says nor checks the bearers | 5 |
| SM9 | a 401's words on the tree road carry the Authorization header (the watcher logs them) | 2 |
| SM10 | the contexts' blocks left out of the start's check | 4 |
| SM11 | the service's watcher reads no environment | 1 |
| SM12 | the service's context registry reads no environment | 1 |
| SM13 | the service's start reads no environment | 1 |

## 4 · The runs

| run | where | numbers |
|---|---|---|
| fast runner (red-first, green, mutants) | plain `javac` + the JUnit launcher on the classpath Maven resolved once; main and test sources compiled fresh every run — NOT Maven; every number of record is Maven's | green 71/71, then every unit class 470/470 (446 + 24) |
| **the baseline** | Maven `mvn -o -B verify`, a git-archive clean copy of c76d098, both labs | **unit 446 · 0 · 0 · 0; ITs 96 · 0 · 0 · 3** |
| **the gate** | Maven `mvn -o -B verify`, a git-archive clean copy of 32e8056, both labs | **unit 470 · 0 · 0 · 0; ITs 96 · 0 · 0 · 3**, BUILD SUCCESS (16:30:07–16:35:19) |

Maven ran offline under `flock /home/mustafa/telcobright-projects/.one-maven.lock`, with an EMPTY head repository and the read-only
tails `~/.m2/arch-ss-review-repo`, `~/.m2/summary-service-repo`, `~/.m2/repository`: nothing written to `~/.m2`.

## 5 · The labs (mine; REMOVED 2026-10-25 16:36 +06 — the containers, their volumes, the password file)

| | |
|---|---|
| PostgreSQL 16 | docker `h2-pg16`, 127.0.0.1:7461 only, trust on loopback — no password exists; database `routesphere` and the four roles, statement for statement as `tools/lab/pg-lab.sh` |
| MySQL 5.7.44 | docker `h2-mysql57`, 127.0.0.1:7361 only; root's password generated into a 0600 file in my work folder, set over STDIN, exported into the test process's environment only (`SUMMARY_IT_MYSQL_PASSWORD`, the ITs' own road) — never echoed, never in a commit; every gate log checked with `grep -c -F -f <the file>` |
| Kafka | none: `KafkaListenersIT` self-skips (its 3), as SS-0007/SS-0008/HARDEN-1 ran it |

## 6 · Open points

| | |
|---|---|
| the flip's order | key + variable on the box → restart → read the start line → only then prime-context's flip. With the key set and the guard still `open`, prime-context answers as before (a bearer is not checked in open mode — its PC-0026 open point 2): the order is safe; `treeRoads.unauthenticated` stops naming this service's address once the line is there |
| which token | a service token prime-context mints for summary-service (its PC-0025: a service's own token reads its tenant's root and the roots its deployment lists), or the platform token. The code sends what the variable holds |
| a rotated token | read at start, kept for the run: a rotation needs a restart |
| the context block on the bed | the bed's profile names no context (its beans name none); the voice deployment's `tcbl/dev` names `mediationContext` against config-manager — its `token-ref` stays unset there (config-manager has no guard) |
