# SS-0009 — state (kept current after every item; a stop loses nothing)

Branch `ad-pair` (worktree `summary-service/worktrees/ad-pair`), base `postgres-ad-call` ff5d945; the brief ARCH-0102.
Paired with billing-core `ad-pair` (BC-0010). Work folder `~/adpair-work` (logs, the lab, the two private Maven bases).

## Lab (mine)

| | |
|---|---|
| PostgreSQL | the shared lab container `c-pg16` (127.0.0.1:7544, trust): the databases `ad_services` and `wifi_services` made by this round — KEPT for the architect's review (see BC-0010-state.md for the removal commands) |
| Kafka | my own container `adpair-kafka` (apache/kafka:3.9.0) on 127.0.0.1:7641 — KEPT for the review (removal: BC-0010-state.md) |
| Maven | offline, under `flock ~/telcobright-projects/.one-maven.lock`; private bases `~/adpair-work/m2-bc` / `m2-ss` filled from `~/.m2/repository` through a `file://` mirror; nothing written to `~/.m2` |

## Items

| item | state |
|---|---|
| 1 | DONE 2026-10-31 02:01 +0600 — the read list in SS-0009-done.md §1; D2 the chargeable pair under kinds = ad |
| 3 | DONE 2026-10-31 02:41 +0600 — the profile btcl/ad-radius2, ServiceCategory (summary.category → the groups), the cross-kind chargeable under any kind (D2), decisions §20, profile page §8; fast runner 58/58 |
| 4 | DONE 2026-10-31 03:58 +0600 (a fresh agent after the stop of ~03:05) — the pair's e2e (billing-core's harness, 2e8fe7d) HOLDS 41 ok / 0 FAIL; mutants red: MS1 3/4, MS2 1/16, MS3 1/5 (base-ss 20/20); worktree = HEAD by cmp. SS-0009-done.md §4 |
| 5 | DONE 2026-10-31 04:51 +0600 — in billing-core's hold (04:20:19–04:33:33): unit 481/0/0/0, ITs 96/0/0/3 on both engines (BUILD SUCCESS); the bed jar `~/artifacts/summary-service/32990ce-pinned/` sha256 7d4cb81f…c736, its base proven by bytes (116/116 libraries, 0 in-house); the e2e on the shipped bytes HOLDS 41/0. SS-0009-done.md §5 |

If resumed: read `~/c-work/gates/adpair-*.log` and the pids there FIRST (never start a second copy of a running one).
