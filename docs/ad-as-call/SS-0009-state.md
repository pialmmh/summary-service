# SS-0009 — state (kept current after every item; a stop loses nothing)

Branch `ad-pair` (worktree `summary-service/worktrees/ad-pair`), base `postgres-ad-call` ff5d945; the brief ARCH-0102.
Paired with billing-core `ad-pair` (BC-0010). Work folder `~/adpair-work` (logs, the lab, the two private Maven bases).

## Lab (mine)

| | |
|---|---|
| PostgreSQL | the shared lab container `c-pg16` (127.0.0.1:7544, trust): the databases `ad_services` and `wifi_services` made by this round — removed at the end |
| Kafka | my own container `adpair-kafka` (apache/kafka:3.9.0) on 127.0.0.1:7641 — removed at the end |
| Maven | offline, under `flock ~/telcobright-projects/.one-maven.lock`; private bases `~/adpair-work/m2-bc` / `m2-ss` filled from `~/.m2/repository` through a `file://` mirror; nothing written to `~/.m2` |

## Items

| item | state |
|---|---|
| 1 | DONE 2026-10-31 02:01 +0600 — the read list in SS-0009-done.md §1; D2 the chargeable pair under kinds = ad |
