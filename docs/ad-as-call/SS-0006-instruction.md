# SS-0006 — the WiFi summary beans: the session per hour/day (group 101) and the volume per destination (the `wifi_flow` entity)

**To:** ONE local agent · repo `~/telcobright-projects/summary-service`, worktree `worktrees/wifi-beans`, branch `wifi-beans`, cut from
`origin/postgres-ad-call` 7d63ae6 (SS-0005) · **From:** the routesphere architect · **Date:** 2026-10-16 16:1x +06

**Why — the owner, 2026-10-16:** 02:5x "summary beans for WiFi per hour/day like a call, including the average session duration and the data
volume per destination IP"; 03:5x "volume and session duration in every session's CDR, free WiFi too"; 10:1x the facts live in the call table's
own columns (billing-core BC-0008, merged e958565). · "keep documenting the features, then building and testing."

**The spec (read first, in full):** the WiFi architect's `wifi-cdr-bytes-and-summary-beans-spec-2026-10-16.md` §3 (the two bean pairs) and
§4 (the proofs) — the current text is on the `wifi` repo's `origin/master` at `docs/agents/design/…` (`~/telcobright-projects/wifi`,
READ-ONLY for you: `git -C … show origin/master:<path>`); a slightly older copy at `~/arch-drafts/shared/wifi-cdr-spec-v2.md`. The two notes
the WiFi architect added after it (2026-10-16 ~12:xx) are RULES here: **(a) the 101 bean's keys are named BY COLUMN, never the meta —
`tup_site` = `InTrunkAdditionalInfo`, `tup_zone` = `OutTrunkAdditionalInfo`, `tup_granttier` = `MatchedPrefixY` (and `tup_category` =
`Category`), `tup_packagecode` = `MatchedPrefixCustomer`; (b) `ValidFlag` 0 rows (unmeasured bytes) are STILL summed — a report filters on it;
say so in the bean's javadoc and the report.**

**What billing-core now writes (merged, READ-ONLY for you at `~/telcobright-projects/telcobright-billing-core`, branch `postgres-ad-call`
e958565 — cite file:line):** the 101 row's columns by `mediation/servicegroups/WifiColumns.java` (the dictionary) — bytes up/down in
`XAmount`/`YAmount` as exact integers, the budget in `ZAmount`, site/zone in `InTrunkAdditionalInfo`/`OutTrunkAdditionalInfo`, the grant tier
word in `MatchedPrefixY` and its number in `Category` (free 1, paid 2, admin 3, test 4), the package code in `MatchedPrefixCustomer`, the MAC in
`TerminatingCallingNumber`, the device IP in `OriginatingIP`, granted seconds in `Duration1`, the RADIUS cause in `ReleaseCauseSystem`, who ended
it in `ReleaseDirection`, measured in `ValidFlag`, the expiry in `OtherDate`; the `wifi_flow` outbox rows by `mediation/cdr/WifiFlowOutbox.java`
+ `WifiFlowOutboxRow.java`: `summary_affected` `entity_type='wifi_flow'`, `op='add'`, `data` = base64(gzip(JSON ARRAY of the rows one message
part inserted, ≤ 5 000 per outbox row)), element keys = the `wifi_flow_window` columns `window_start, window_sec, gw, zone, site, seg, mac,
remote (dotted IPv4), port, proto, packets_up, packets_dn, bytes_up, bytes_dn, produced_at` (instants ISO UTC; a null left out; the decoder
`WifiFlowOutbox.Decode` is the reference for your parser); the ping on `cdr_summary_ping_<root>` is `{"tenant","entity":"wifi_flow","rows"}`.
The contract page `docs/cdr-kafka-ingest-contract.md` §2a (the dictionary) and §2b (the entity) say the same in words.

---

## 0 · What the architect read (summary-service 7d63ae6, 2026-10-16) — rulings in **bold**

- **The engine is INCREMENTAL only:** a bean's `buildBatch(bytes, tier)` turns ONE outbox row's carried records into deltas; the engine
  merges them into the cached window rows (`SummaryEntity.merge/multiply`), writes absolute values. A bean never re-reads a source table.
  `REPLACE` throws. The bookmark is per (`entity_type`, bean name) in `summary_offset`; a NEW entity type's beans start at 0 on every schema
  (`OutboxReader.seedBookmarks`, "no bookmark at all for the entity"); a bean of an EXISTING entity switched on later starts at the outbox HEAD.
- **The patterns to copy, not re-invent:** the ad bean (`summarybeans/ad`: `AdSummaryBean` — `entityType`, `table`, `tableSpec`,
  `insertColumnsCsv`, `bucketColumn`, `buildBatch`, `mapRow`; `AdSummary` the entity — `tupleKey`, `merge`, `multiply`, `cloneWithFakeId`,
  `insertValues`, `updateAssignments`, `bucketLiteral`; `SumAdDdl` — `SummaryTableSpec.table(...)…partitionedByDayOn(BUCKET).keptUpToDate()`,
  the service makes the table on BOTH engines); the call bean's record filter and its 101 branch (`CallSummaryBean`, `CallSummaryBuilder`,
  the customer chargeable of group 101, `ChargingStatus` early return, `tup_customercurrency` keeps TF_min and BDT apart); the config
  instantiation (`registry/internal/SummaryBootstrap.configInstantiated` → `CallSummaries.forWindow`); the blob mapper (`CdrBlobMapper`:
  case-insensitive, unknown properties ignored) and its `Cdr` model (`summarybeans/call/model/Cdr.java` — it does NOT carry XAmount,
  YAmount, InTrunkAdditionalInfo, OutTrunkAdditionalInfo, Category, ValidFlag, TerminatingCallingNumber, ReleaseCauseSystem, ReleaseDirection,
  PackageAmount, IdPackageAccount today).
- **Rulings for this round:** (1) a NEW package `summarybeans/wifi` with two entity classes and two bean classes — `WifiSessionSummary` /
  `WifiSessionSummaryBean` (entity `cdr`, group 101) and `WifiDestinationSummary` / `WifiDestinationSummaryBean` (entity `wifi_flow`) — each
  bean config-instantiated per window like the call beans; (2) the bootstrap gains ONE optional key `summary.beans.<name>.kind:
  wifi-session | wifi-dst`; absent = the call bean exactly as today (nothing existing changes); (3) the tables are the service's own
  (`SummaryTableSpec`, `partitionedByDayOn(tup_starttime)`, `keptUpToDate()`, both engines), named `sum_wifi_hr_101` / `sum_wifi_day_101`
  and `sum_wifi_dst_hr` / `sum_wifi_dst_day`; (4) `avg_session_sec` is a DERIVED column — recomputed in the entity's `merge` (and
  `multiply`) as `total_duration_sec / sessions_connected` (0 when none), NEVER added; (5) the destination bean's `tup_gw` (the gateway)
  stands where the spec's `tup_switchid` would be (a flow row has no switch id); (6) `tup_causefamily` = a word from the cdr's `HangupCause`:
  `idle | time | volume | balance | deauth | shutdown | other` — read the gateway's end words at their source (`wifi` origin/master
  `wifi-sphere/…/internal/session/v2/EndFacts.java` `stopWord`, `SessionCause`) and pin the mapping with the REAL words; an unknown word →
  `other`; (7) the time bucket of a flow row = `window_start` on the root tenant's zone like the voice beans (`DdlPartitions.tenantZone`);
  (8) no money column on the destination tables; no `distinct_macs` anywhere.

## 1 · The items (one commit each; every rule broken once and seen red; push the branch after each)

| # | Item | The rule | The proof |
|---|---|---|---|
| 0 | **Baseline** | The suite as it is: unit; the PostgreSQL ITs on your own container; the MySQL ITs by name. Numbers in the notes | — |
| 1 | **The blob reads the new columns** | `Cdr` (the blob model) gains `xAmount, yAmount, zAmount` (yAmount new; keep the existing ones), `inTrunkAdditionalInfo, outTrunkAdditionalInfo, category, validFlag, terminatingCallingNumber, originatingIp` (exists), `releaseCauseSystem, releaseDirection, duration1` (exists), `packageAmount, idPackageAccount` (SS-0005's F2 — closed here: the SG10 package stamps read them). Nothing else in the mapper changes | Golden: the existing voice and ad blob fixtures decode to the same values as before (capture at 7d63ae6); a 101 blob built from BC-0008's shape decodes every new field; an older blob without them → nulls, no error. Mutant: a field renamed → the 101 decode test red |
| 2 | **`sum_wifi_hr_101` / `sum_wifi_day_101`** | Entity `cdr`, the records whose customer chargeable is of group 101 (the call bean's filter, copied with citation). Keys: `tup_starttime` (the bucket, hour/day, Asia/Dhaka like the voice beans), `tup_switchid`, `tup_inpartnerid`, `tup_site`, `tup_zone`, `tup_granttier`, `tup_category`, `tup_packagecode`, `tup_customercurrency`, `tup_causefamily` — every key from a COLUMN (rule a). Sums: `sessions`, `sessions_connected` (`durationSec > 0`), `total_duration_sec`, `bytes_up` (XAmount), `bytes_dn` (YAmount), `customercost`; derived `avg_session_sec` (ruling 4). A 0-second session counts in `sessions`, not in `sessions_connected`, adds 0 to every sum. ValidFlag 0 rows summed (rule b). The table by `SummaryTableSpec` (bigint sums, decimal(18,6) customercost, the key widths from the ad bean's constants; PK (id, tup_starttime); indexes on tup_starttime and (tup_site, tup_starttime)) | A replayed hour of known 101 records → the known row per key (sessions, connected, total, avg, bytes, cost); one record dropped → every sum differs; a second session in the bucket → avg = total/connected, not the first avg + the second (the mutation: avg added instead of derived → red); TF_min and BDT key apart; a 30 record and a voice record → no row in these tables |
| 3 | **`sum_wifi_dst_hr` / `sum_wifi_dst_day`** | Entity `wifi_flow`; `buildBatch` parses the JSON ARRAY of flow rows (its own small mapper, case-insensitive, unknown ignored; the keys as `WifiFlowOutboxRow` names them). Keys: `tup_starttime` (window_start → the bucket on the root's zone), `tup_gw`, `tup_site`, `tup_zone`, `tup_remote` (dotted IPv4 text), `tup_proto`, `tup_port`. Sums: `bytes_up`, `bytes_dn`, `packets_up`, `packets_dn`, `flows` (rows). No money, no distinct_macs (rulings 5, 8). The table by `SummaryTableSpec` (PK (id, tup_starttime); indexes on tup_starttime and (tup_site, tup_remote, tup_starttime)) | A replayed hour of known windows → the known rows per (gw, site, zone, remote, proto, port); one window's rows dropped → the bytes differ; a second tenant's rows in the replay → no row in this tenant's table; two outbox rows of ONE part (the 5 000 split) → the same sums as one; a `wifi_flow` bean on a schema with existing `cdr` bookmarks starts at 0 (the seeding rule) — pinned |
| 4 | **Config, bootstrap, profiles** | `summary.beans.<name>.kind: wifi-session \| wifi-dst` (+ `window`; `wifi-session` fixes service-group 101 and table-suffix 101; `wifi-dst` has neither) in `SummaryBootstrap.configInstantiated` → `WifiSummaries.forWindow(...)`; an unknown kind refused at start in words; absent kind = unchanged. `profile-radius2.yml` and `profile-lab.yml` gain the four beans (enabled; the dst pair idles until billing-core's flow ingest is flipped on the bed). `config/README.md` names the kinds | The config tests; a profile with the four beans starts (the print-what-this-start-resolved road); mutant: the kind ignored → the bean built as a call bean → red |
| 5 | **End to end on the packaged jar, both engines** (the SS-0005 story's way) | 12 WiFi sessions as billing-core's BC-0008 rows write them (build the outbox blobs with BC-0008's own shape — read `WifiColumns` for the columns, `SummaryOutboxWriter.Encode`'s `[{Cdr,Chargeables}]` entries) → `sum_wifi_hr_101` / `sum_wifi_day_101` hold the known rows; the 30 and 101 VOICE tables byte-for-byte unchanged; 3 flow window messages (one split in two outbox rows) → the dst rows; the owner's two reports as queries: "average session duration per site per day" from `sum_wifi_day_101`, "top destinations per site per hour" from `sum_wifi_dst_hr` — each equal to a separate query over the raw rows of the same hour | The numbers; the two report queries in the report with their results |
| 6 | **Report `docs/exchange/SS-0006-done.md`** (or the folder SS-0005's report lives in) | Files; every red (test + line); the numbers per run from the CLEAN COPY (unit, PostgreSQL ITs, MySQL ITs); the mutants; the cause-word mapping as read (file:line); what devops must know (four new tables made by the service; the beans' names for `enabledSummary`); anything not run | — |

**Not this round:** billing-core; a replay of the 40 dead-lettered records (the owner's D1); the MySQL side of `wifi_flow` (billing-core
writes it on PostgreSQL only — the dst beans exist on MySQL and idle); `distinct_macs` (a report query on `wifi_flow_window`); any box.

## 2 · Rules that stand

- **Java 21.** Maven OFFLINE on a private repo: `mvn -f <worktree>/pom.xml -o -Dmaven.repo.local=$HOME/.m2/ss6-repo
  -Dmaven.repo.local.tail=$HOME/.m2/arch-ss-review-repo,$HOME/.m2/summary-service-repo,$HOME/.m2/repository …` (tails read-only; use the tails
  that exist). Never write `~/.m2/repository`. A dependency that cannot be resolved offline: say so in the notes and the report; never go online.
- **Labs, your own, on 127.0.0.1 only:** PostgreSQL 16 container `ss6-pg16-lab` on a free loopback port (7751 was free at 16:1x; the recipe
  = prime-context `docs/ad-as-call/postgres-tenancy.md` §4: trust on loopback, database `routesphere`, the roles) → `-Dsummary.it.pg.url=…`;
  the MySQL ITs on 127.0.0.1:3306 with `-Dsummary.it.mysql.url=…` and the password BY NAME in `SUMMARY_IT_MYSQL_PASSWORD` (the owner's lab
  note; set in your shell, never in a file, commit, log or report); the Kafka ITs skip without a broker, as before — a broker only if the
  story needs one, your own container, never the shared `onlab-kafka` (7592). Containers removed at the end.
- **House style:** short named orchestrators; one class per job; small files; test names that say the rule; copy the ad/call patterns
  with a citation rather than new shapes; `keptUpToDate()` on the new tables; no engine change (`SummaryEngine`, `OutboxReader` untouched
  beyond what item 4 needs — if you believe they must change, STOP that item and write it in the report).
- **Clean copy:** before the report, `git archive <branch head>` into `~/ss6-work/clean-<sha>/`, the whole suite from there (offline, the
  same private repo, your labs up). The report's numbers come from there.
- **Mutants:** the original copied to `~/ss6-work/mutants/<file>.orig` BEFORE the edit; the named test seen red; restored; green; all listed.
  A real defect: the red test first, then the smallest fix; a change to a public SPI, a table name/shape beyond this brief, or the outbox
  reading → STOP that item and write it in the report.
- **Timing:** a test that fails once is run alone five times; report the count; never raise a timeout silently.
- **Survive a stop:** `~/ss6-work/notes.md` with a `State:` line after every item; the report drafted after item 1 and kept current; on
  a resume: `git status` + `git diff` first, mutant originals back, your containers checked.
- **Git:** one commit per item; EVERY commit waits for its test run; push only `wifi-beans` (`git -C <worktree> push -u origin wifi-beans`);
  never `postgres-ad-call` or `master`; the merge is the architect's (`--no-ff` into `postgres-ad-call`). Absolute paths or `git -C`;
  never `cd` into the main checkout. Trailer on every commit: `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- **No secrets** anywhere. Do not run the devlog card. The `wifi` and `telcobright-billing-core` repos are READ-ONLY to you (`git show`).
- **The end:** your final message to the architect: the branch head; the clean-copy numbers per run; the reds (test + line); the mutants;
  the cause words as read (file:line); the defects and fixes or stops; the four tables' DDL as the service makes them (PostgreSQL + MySQL);
  what devops must know; anything not run and why.
