# SS-0007 — the WiFi session sums split by BRAS (`tup_gw` from the CDR's `InMgwId`), on the owner's R4

**To:** ONE local agent · repo `~/telcobright-projects/summary-service`, worktree `worktrees/wifi-gw-key`, branch `wifi-gw-key`, cut from
`origin/postgres-ad-call` (3829e18 at drafting — re-read at launch) · **From:** the routesphere architect · **Date:** 2026-10-17 00:55 +0600 (launched). Its BED install waits for the WiFi architect's §8 step 7 and his "go" (agreed 2026-10-17).

**Why:** the owner, 2026-10-16 (word for word, R4 of the WiFi design): "counters and all logs must include bras id". The WiFi architect's
spec (wifi master 5c6deed2, `docs/agents/design/multibras-central-gatekeeper-2026-10-16.md` §5 S10, §9 Q4 "yes by default"): the
central gatekeeper writes the session's BRAS into the meta `gw`; billing-core ALREADY maps meta `gw` → cdr `InMgwId`
(`WifiColumns.java:84`, a BC-0008 cell) — no billing-core change; the flow windows and `sum_wifi_dst_*` already key by `gw`; the
SESSION sums `sum_wifi_hr_101` / `sum_wifi_day_101` do not — they must split by BRAS, and "an empty key for the old rows is fine; name it
in the record".

## 0 · Facts (read by the architect 2026-10-17)

- `summarybeans/wifi/model/WifiSessionSummary.java`: `INSERT_COLUMNS` (:31-33), the key fields (:42-52), `tupleKey()` (:79-90).
  `summarybeans/wifi/internal/WifiSessionSummaryBuilder.java:56-80` reads the key from the record's COLUMNS (never its meta — the WiFi
  architect's rule a). `SumWifiSessionDdl.java` is the table's spec: `.keptUpToDate()`.
- **The migration exists already:** a `.keptUpToDate()` table is brought up to its spec at first use by `bean/spi/TableDdl.bringUpToDate` —
  a column it lacks is ADDED with its default (every dimension is NOT NULL, default `''` or 0), so the rows already there read `''`;
  nothing is dropped, renamed or re-typed; the second start changes nothing. **Indexes are NOT added to an existing table** (only columns).
- The destination sums already key by `tup_gw` with `GW_WIDTH = 64` (`WifiDestinationSummaryBuilder.java:26`) — use the same width
  (the bras id rule is `^[a-z][a-z0-9-]{1,31}$`, 32 at most).
- **The CDR record is shared:** `summarybeans/call/model/Cdr.java` (the call AND the WiFi beans) has NO `inMgwId` today;
  `CdrBlobEntry` reads billing-core's outbox rows into it; `new Cdr(` appears in the tests only via `testkit/CdrTestSupport.java`.
- Reports live in `docs/ad-as-call/` (`SS-0006-done.md` = the last; its commands for the unit suite, the ITs and the PG + MySQL labs).

## 1 · The items (one commit each; push `wifi-gw-key` after each)

| # | Item | The proof |
|---|---|---|
| 0 | **Baseline** as SS-0006 ran it (unit + ITs, both engines' labs; offline, private `~/.m2/ss7-repo`) | the numbers |
| 1 | **The record reads `InMgwId`:** `Cdr` gains `inMgwId` (as billing-core writes it; `''` when absent), `CdrBlobEntry` fills it; every construction updated; the call beans' keys untouched | the whole suite unchanged; a blob test reads `InMgwId` from a billing-core-shaped outbox row |
| 2 | **The session sums key by `tup_gw`** = `InMgwId` clipped to 64: the spec column (a dimension, default `''`), `INSERT_COLUMNS`, the field, `tupleKey()`, the builder | two sessions of one site on two BRAS → two rows; on one BRAS → one row; a record without `InMgwId` → `tup_gw = ''`; **the migration on BOTH engines:** a table made by 3829e18's spec, holding rows → the new version's first use adds `tup_gw` and the old rows read `''`; new rows carry their BRAS; a second start changes nothing. Mutants: `tup_gw` left out of `tupleKey()` (two BRAS merge → red); the builder reading another column (red); the column with no default (the first use refuses → red) |
| 3 | **The record of the change:** the WiFi beans' doc (and the report) says rows summarised before this version keep `tup_gw = ''`; the D1 replay, if the owner orders it after the central gatekeeper, fills the split for replayed records | — |
| 4 | **Report** `docs/ad-as-call/SS-0007-done.md` | the numbers before/after, the migration proof on both engines, the mutants, what the architect's cut needs | — |

**Not this round:** the destination sums; billing-core; the voice tables; any index on the old tables; the bed (the architect cuts the
artifact; devops installs at the WiFi architect's step 7 or later).

## 2 · Rules that stand

As SS-0006 §2 (`~/arch-drafts/launch/SS-0006-instruction.md`): Java 21; Maven offline on a private repository; never write the shared
`~/.m2` repositories; labs on 127.0.0.1 only, removed at the end; never start the service jar bare (its bundled tenants reach live);
mutants' originals kept, red seen, restored; `State:` lines; one commit per item after its run; the merge is the architect's; trailer
`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
