from: SS        to: ARCH        kind: done        number: 0004
date: 2026-10-08 19:42 +06:00 (first written 19:39 after the S16 push; brought up to date after S18's clean copy and push)
branch: s16-wip (from postgres-ad-call 7218213, over the WIP c8af8f1 and your 53b708e / 4976999 / 2ff870e)
head: 3dddcb7 (S18) over 7535ae2 (S16); this note is the commit after it — pages only
subject: SS-0003 §5 — S16 (the second rehearsal's F8: the summaries catch up after the database closed every connection, with no restart) and S18 (the group-30 key is <ruleId>/<app>), each one commit, each broken once and seen red, each pushed after a green clean copy; S17 waits for the owner

# SS-0004 · done — S16 and S18

## 0 · What to relay

| | |
|---|---|
| the branch | `s16-wip`, pushed: **7535ae2** = S16, **3dddcb7** = S18. `postgres-ad-call` and `no-bundled-tenant` are not touched; the merges are yours (§5 of SS-0003) |
| S16 | DONE (§1). The pool drops a connection after a fatal error (class `08`; `57P01`/`57P02`/`57P03` on PostgreSQL) and asks one idle longer than 1 s before it hands it out; a worker tries again at every poll and says the trouble once. Red seen with the check off, on both engines. The lab story holds on both engines three ways each: the sessions ended by the server, the database's container restarted, the service started while the database was down — every summary equal to the `cdr` rows, no restart, the ERROR lines stop |
| the check's cost | one round trip (`isValid`) on a borrow after more than 1 s idle: **p50 39–66 µs, p99 95–179 µs, max 196–336 µs** on this box, both engines (§1.3). A backlog's back-to-back steps pay nothing |
| S18 | DONE (§2). The group-30 call summary keys `tup_outgoingroute` on the route's first two parts, by the ad service's own reader copied with its citation (this service has no dependency on ad-sphere's jar). Red seen: 4 of 16 with the key line off |
| the suites | from a clean clone of each pushed head, both engines (§3): S16 7535ae2 unit **296 / 0 / 0 / 0**, ITs **73 / 0 / 0 / 0**; S18 3dddcb7 unit **302 / 0 / 0 / 0**, ITs **73 / 0 / 0 / 0** |
| to decide | §4: the poll-rate retry (no backoff) for a poison row; the two constants in code; a `connectTimeout` for a deployment's MySQL URL; the helper copied, not called; S17 |

## 1 · S16 — after the database closed every connection, the summaries catch up with no restart

### 1.1 · What was wrong, read at its source

`StoreDataSource.open` built the Agroal pool with sizes and an acquisition timeout and nothing else. After the switch
database's 30 s stop every connection the pool held was dead; the pool handed them out again and again — a drain over
one fails at its first statement (`This connection has been closed`, `08003`), `JdbcUnitOfWorkFactory.begin` closes it
on an `enter` failure, and a close RETURNS a connection to a pool that has no rule to drop it. Every worker of every tier:
one ERROR line per try, for ever (R-0002 F8: 6,312 in five hours, the summaries frozen until a restart).

### 1.2 · The rule, built (7535ae2)

| where | what |
|---|---|
| `runtime/internal/StoreDataSource` | the pool's two rules: `exceptionSorter(fatalErrorsOf(dialect))` — a connection that threw SQLSTATE class `08` (both engines), or `57P01` / `57P02` / `57P03` on PostgreSQL, is dropped when it is given back, never handed out again; `idleValidationTimeout(1 s)` with `defaultValidatorWithTimeout(5 s)` — a connection idle longer than 1 s is asked (`isValid`) before it is handed out, one that is not is dropped and another opened. The start's `store:` line says both |
| `registry/internal/OutboxWorker` | while failing, a worker tries again at EVERY poll (the 30 s backoff is gone: "the first drain after the database is back succeeds, at the latest the next poll"); a ping wakes it at once. The trouble is said ONCE (an ERROR with its cause) and ONCE when it writes again (an INFO: how long, how many tries); the tries between are DEBUG lines |
| `outbox/api/OutboxReader.rollbackQuietly` | the per-try WARN with a stack is a DEBUG line — the worker says the failure, once |
| `outbox/internal/OutboxReaper` | from the WIP: a schema that fails is said once (WARN) and once when it trims again (INFO) |
| `tenancy/api/TenantWatcher` | unchanged — its retry (15 s, growing to the refresh) is what serves a schema it could not serve, once the pool is sane; proven in §1.4 (c) |

What is NOT done: no change of `JdbcUnitOfWorkFactory` (its close on an `enter` failure is right now that a close drops a
flushed connection); no profile key for the two constants (§4).

### 1.3 · Proof in the suite — the rule broken once, red, then green

`OutboxConsumerContract` (the same test on both engines, over the SERVICE's own pool made by `StoreDataSource.open`):

- `a_store_that_ended_every_session_is_written_again_by_the_first_drain_with_no_restart` — (1) every session ended by the
  server (`pg_terminate_backend` / `KILL`), the pool's connection idle past the check: the FIRST drain writes; (2) both
  of the pool's connections just used, ended within the check, the drains borrow at once: each dead connection is
  handed out at most once (that drain fails with the fatal error, the connection is dropped), then it writes — **2 of 2**
  failed before one wrote, on both engines, never a third.
- **Red** (the idle check and the sorter commented out, the test unchanged): both engines failed at the first drain of
  (1), `SummaryStoreException: could not begin summary unit of work` — MySQL's cause `EOFException: Can not read response
  from server … connection was unexpectedly lost`, PostgreSQL the same line — F8's shape. Restored: green.
- `what_the_idle_check_costs_a_borrow` — 500 timed `isValid` calls after a warm-up, printed by the suite:

| run (this box, loopback) | PostgreSQL p50 / p99 / max | MySQL p50 / p99 / max |
|---|---|---|
| the worktree | 66 / 139 / 202 µs | 45 / 135 / 207 µs |
| clean copy, first run | 40 / 109 / 261 µs | 71 / 179 / 336 µs |
| clean copy, second run | 39 / 168 / 326 µs | 39 / 95 / 196 µs |

When it is paid: on a borrow after more than 1 s idle — each worker's first borrow after a poll wait (six workers per
schema at a 5 s poll: six round trips per 5 s per schema, under 0.5 ms). The back-to-back steps of a backlog pay
nothing. A connection the server ended WITHIN the second is still handed out once: that drain fails (one ERROR line,
the bookmark unmoved), the connection is dropped, the next poll writes. The validator's 5 s bounds the check when a
server is half-dead (no reset).

`OutboxWorkerTest` (4): a worker whose store went away writes again by itself when it is back — six tries within 8 s of
a 1 s poll (a wait that grew would reach six at 15 s), written within 2 s of the store's return; the trouble said once
and once when it writes again, never a line per try (13 tries → one ERROR, one INFO); nothing said when there was none;
a second trouble said again once. `SummaryBeanRegistryTest`: the reaper the same.

### 1.4 · Proof on the box — `tools/lab/outage-e2e.sh`, the packaged jar, both engines, three ways each

One tenant (`summary.tenants.mode=single`), the profile's 5 s poll, six beans; every view written is ONE `cdr` row and
ONE outbox row in the same transaction, so "the summaries equal the cdr rows" is read literally: `count(cdr)` =
`sum(views)` of `sum_ad_day_30` = of `sum_ad_hr_30`, and all six beans at the last outbox row. The service is never
restarted in (a) and (b). Run 2026-10-08 19:23–19:28 (`target/lab-e2e/outage-story.txt`, not committed):

| | PostgreSQL 16 | MySQL 5.7 |
|---|---|---|
| 1 · served, 20 views | equal in 4 s | equal in 4 s |
| 2 · (a) the server ENDS the service's sessions under a 300-row drain, twice | 6 + 0 ended; **equal 5 s later** (370 = 370 = 370); 6 ERROR lines (one per worker, all six mid-drain); 6 new sessions | 3 + 1 ended; **equal 6 s later** (370 = 370 = 370); 4 ERROR lines |
| 3 · (b) a 3,000-row backlog, the container STOPPED 40 s mid-drain | stopped 19:24:32, answering 19:25:12; **equal 15 s after** (3,420 = 3,420 = 3,420); the first worker wrote again at 19:25:1x | stopped 19:26:49, answering 19:27:31 (MySQL's own start); **equal 25 s after** (3,420 = 3,420 = 3,420) |
| 4 · the ERROR lines stop | 12 when equal, **12** fifteen seconds later; 12 `writes again` INFO lines, one per ERROR | 10 → **10**; 10 `writes again` |
| 5 · (c) the service STARTED while the database is DOWN | `could NOT be served` once; the database back at 19:25:47; **served 14 s later** by the watcher's retry (the same pid); 30 views, equal in 5 s | the same: served 14 s after the database answered; equal in 5 s |

In (c) a start writes two ERROR lines for the one trouble — the reader's `infra table provisioning failed` and the
watcher's `could NOT be served` — left as they are. On MySQL the outbox's `max(id)` runs past the row count (its bulk
auto-increment leaves gaps); the sums equal the `cdr` rows exactly.

## 2 · S18 — the group-30 key is `<ruleId>/<app>` (3dddcb7)

`CallSummaryBuilder.populateServiceGroup`, the group-30 branch only: `s.tup_outgoingroute = AdRouteKey.routeKeyOf(route)
.orElse(the whole text)`. `summarybeans/call/internal/AdRouteKey` is the ad service's own reader COPIED with its citation
and not re-derived (§2a): ad-sphere main ARCH-0057, `AdCallPreprocessor.java:147–212` — `routeKeyOf`,
`routePositionsOf`, `ruleIdOf`. This service does not depend on ad-sphere's jar (an application, not a library), so the
four lines live here, cited; a change of the route's shape is a change of both (§4). A route not of the fixed shape — a
row from before ARCH-0055 (the zone alone), fewer than eight positions, no rule id in front — keeps its whole text as the
key, as today; nothing is invented. Groups 10, 11 and 15, `sum_ad_*`, billing-core: untouched.

`CallSummaryGroup30Test` (16, six new): two devices of one rule and app are ONE row keyed `7/wifi`; a route longer than
64 characters keys on whole parts; `0/wifi//////` keys as `0/wifi`; an encoded app stays one part (`7/a%2Fb`); three
routes not of the shape keep their text; a group-10 record's route is still the whole string. **Red** with the key
line off: 4 of 16 — two devices gave 2 rows, the long route and `0/wifi//////` and `7/a%2Fb/dhaka-01/////` stayed whole.
Restored: green. The test view gains `route(...)`; its chargeable's group follows the view's `serviceGroup`.

## 3 · The suites, from a clean clone of each pushed head (`mvn -o verify`, the three labs up)

| head | unit (surefire) | ITs (failsafe) | per IT class |
|---|---|---|---|
| S16 7535ae2 | **296 / 0 / 0 / 0** | **73 / 0 / 0 / 0** | BuildBakesNoTenantIT 1 · KafkaListenersIT 3 · MySqlTreeIT 2 · OutboxConsumerIT (MySQL) 25 · PostgresOutboxConsumerIT 33 · PostgresTreeIT 9 |
| S18 3dddcb7 | **302 / 0 / 0 / 0** (the six new of S18) | **73 / 0 / 0 / 0** | the same six classes, the same counts; the idle check that run: PostgreSQL 42 / 153 / 541 µs, MySQL 37 / 76 / 766 µs |

Seen on the way, both not of this round's code: (i) the first clean copy of the first S16 commit had my own
`OutboxWorkerTest` race red once (12 tries counted for 13: the first ping landed before the worker's start-own drain) —
the test now waits for that first try before it pings, three runs green, the commit amended before the push; (ii) the
first full run of 7535ae2 had `KafkaListenersIT.what_landed_before_the_ping_listener_could_hear…` red once: its wait is
60 s for a new consumer group's partitions on the lab broker, and that class took 78 s that run; green alone, green in
the full rerun (22 s). A fake database, no pool and no worker of S16 in it. The MySQL ITs' default URL is now the lab's
`127.0.0.1:7633` (it was the box's `3306`, where the suite would have made `summary_it` on the owner's own MySQL and
skipped for the password); `-Dsummary.it.mysql.url` still overrides.

## 4 · What you decide

1. **No backoff while failing.** A worker now tries at every poll (5 s in the bed's profile) until it writes — the
   rule's "at the latest the next poll". A poison row that cannot be quarantined now costs one failed transaction per
   poll per bean (it was one per 30–60 s), quiet in the log (said once). Say if a cap is wanted; the text is one line.
2. **Two constants in code,** `CHECK_AFTER_IDLE = 1 s` and `VALIDATION_SECONDS = 5` — not profile keys. Say if a key is
   wanted (`summary.store.check-after-idle-seconds`); I left them fixed to keep the profile small.
3. **A deployment's MySQL URL and a host that is DOWN** (not refusing): a connection attempt then hangs until the
   driver's connect timeout — PostgreSQL's default 10 s, MySQL's 0 (none). The lab's `docker stop` refuses at once, so
   this was not met. The profile's MySQL URL would want `connectTimeout=10000` (the PostgreSQL one needs nothing); a
   profile change, yours or the deployment's.
4. **The helper is copied, not called** (§2a's second way): calling it needs ad-sphere to publish a small library
   artifact of `AdCallPreprocessor`'s route readers, which does not exist. Yours to ask of ad-sphere if you want one rule
   in one jar.
5. **S17** (the kinds as config-activated processors) waits for the owner's word, as §5 says.

## 5 · The rules, kept

Java 21 (Corretto 21.0.9); Maven 3.9.10 with `-Dmaven.repo.local=$HOME/.m2/summary-service-repo
-Dmaven.repo.local.tail=$HOME/.m2/repository`, offline; nothing installed into `~/.m2/repository`. The jar was never
started bare: every lab start by `tools/lab/run-lab.sh btcl/lab` (the endpoints printed and checked first, 127.0.0.1
only, HTTP 7671 — not 7093 / 7126). Containers `ss-pg16-lab`, `ss-mysql57-lab`, `ss-kafka-lab` on 127.0.0.1:7643 /
7633 / 7692, removed at the end (`tools/lab/pg-lab.sh down`); no password exists in them (trust / empty root) and none
is in a file or on a command line. Nothing dialled off this box. Nothing left running. The worktree
`summary-service/worktrees/s16-wip` stays for your merge; the main checkout on `postgres-ad-call` was not touched.
