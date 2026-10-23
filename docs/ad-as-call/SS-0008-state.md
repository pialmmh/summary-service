# SS-0008 — state (kept current after every item; a stop loses nothing)

Agent: summary-service SS-0008. Worktree `summary-service/worktrees/kinds`, branch `kinds` (from `postgres-ad-call` 72d5875 + the brief a4ed2ab).

## Lab (mine; removed at the end)

| what | where |
|---|---|
| PostgreSQL 16.13 | docker `ss8-pg16`, 127.0.0.1:7437, trust (no password exists); database `routesphere`, roles prime_context / ad_sphere / billing_core / summary_service (as tools/lab/pg-lab.sh makes them) |
| MySQL 5.7.44 | docker `ss8-mysql57`, 127.0.0.1:7346, root with an empty password (no password exists) |
| Kafka | none (KafkaListenersIT self-skips, as SS-0007 ran it) |
| Maven | `flock /home/mustafa/telcobright-projects/.one-maven.lock mvn -o …`, head repository `~/ss8-work/m2-head` (must stay empty), read-only tails `~/.m2/arch-ss-review-repo,~/.m2/summary-service-repo,~/.m2/repository`; nothing installed |
| work files | `~/ss8-work/` (clean copies, logs, mutant originals) — removed at the end |

## Items

| # | item | state | commit |
|---|---|---|---|
| 0 | baseline (unit + ITs both engines, the e2e scripts as SS-0007 ran them) + the sums pinned | DONE: clean copy of a4ed2ab — unit 399/0/0/0, ITs 86/0/0/3 (PG 40+9, MySQL 31+2, BuildBakes 1, Kafka 3 skipped); wifi-beans-e2e both 86 ok/0 FAIL, wifi-gw-upgrade-e2e both 80/0, wifi-gw-wayback-e2e both 96/0 (0 ERROR lines) = SS-0007's numbers; the pins: running | — |
| 1 | the kind contract `bean/spi/SummaryKind`; CallKind · AdKind · WifiKind, the beans moved under them | GREEN: red = KnownKindsTest does not compile on a4ed2ab (no contract, no kinds, no call/chargeable); green on a clean snapshot: unit 409/0/0/0 (+10), ITs 86/0/0/3; mutants next | (this commit) |
| 2 | activation per tenant `summary.kinds`; KIND_NOT_ENABLED | not started | — |
| 3 | unchanged sums (the pins identical after 1–2) | the harness committed first (tools/lab/kinds-pin-e2e.sh, testkit/LabPinCorpus); base pins running | (the commit before item 1) |
| 4 | the merge plan `master-merge` | not started | — |
| 5 | docs + report SS-0008-done.md | not started | — |

## Log (newest last)

- 00:34 labs started (ss8-pg16 :7437, ss8-mysql57 :7346); state file written.
- 00:35–00:56 the first baseline try waited 21 min for the lock and ran nothing (my helper split the MySQL URL at its `&`; no test ran, nothing written). Helper fixed (arguments as an array).
- 00:58 batch 1 queued (one lock session): base a4ed2ab verify + classpath; 3829e18 packaged (the older app of the upgrade/wayback e2e); item 1 red (base + KnownKindsTest only: must not compile); the worktree verify (item 1 green + the whole suite).
- Item 1 written in the worktree (uncommitted): bean/spi/SummaryKind + ServiceGroupReader; summarybeans/call/CallKind, ad/AdKind, wifi/WifiKind; registry/internal/KnownKinds; ConfigInstantiatedBeans asks KnownKinds for a shape; CallSummaryBean implements ServiceGroupReader; summarybeans/chargeable MOVED to summarybeans/call/chargeable (git mv + package lines). Test: registry/internal/KnownKindsTest.
- The pin harness written (uncommitted): tools/lab/kinds-pin-e2e.sh + src/test/.../testkit/LabPinCorpus.java.
- Item 2 drafted OUTSIDE the worktree (~/ss8-work/drafts: TenantKinds, KindGate, RowCensus, CrossKindReader, patches for OutboxReader / SummaryBootstrap / ChargeableSummaryBean / tests) — applied after item 1's commit.
- 01:04–01:20 batch 1 (one lock session): base verify 399/0/0/0 + 86/0/0/3; 3829e18 packaged; item 1 red (compile) and green (409 + 86/3); item 2 red (its tests do not compile on item 1: TenantKinds, KindGate, RowCensus, CrossKindReader missing); item 2 green verify running on ~/ss8-work/clean/item2-try (item 1 + item 2).
- 01:10–01:17 the baseline e2e on a4ed2ab's jar: 86 / 80 / 96 ok, 0 FAIL.
- 01:20 the pin script fixed twice before its first real run (a `local` that read itself; the database clients swallowed the corpus on stdin — now on fd 3). Base pin on PG: 9 outbox rows, 16 of 16 beans, 16 tables, 110 lines.
