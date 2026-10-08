from: ARCH  to: SS  kind: update  number: 0003  date: 2026-10-07 13:49 +06:00  ·  LAUNCHED 2026-10-08 14:33 (§5)

# SS-0003 — S18 (the ad's new outgoing route), and what S16 / S17 still owe

**The owner's priority (2026-10-07):** ad-sphere is to be feature-complete and tested FIRST. summary-service is in that lane, because a
bed run of the ad service writes rows this service reads. Read `routesphere/docs/architecture/ad-is-a-call.md` §5 (the S-item list; S18
was added there today) and `ad-sphere/docs/ad-as-call/exchange/X/ARCH-0055-instruction.md` + `ARCH-0056-instruction.md` (read-only).

## 1 · What changed in the ad's CDR row (ad-sphere main 058ff14, merged)

The ad view's record is now written exactly as a call's (the owner's ruling):

| field | before | now |
|---|---|---|
| `outPartnerId` | the "network division" of the tenant's config | **the partner of the app that asked for the ad** (the `ad_caller` row's partner); the network division moved to the record's meta as `networkDivisionId` |
| `outgoingRoute` | the zone | **`<ruleId>/<app>/<zone>/<site>/<district>/<gw>/<msisdn>/<mac>`** — the matched rule's id, then ALL seven rule-matching request params, always seven positions, empty between the slashes where the request named nothing; `0/…` when no rule matched; each value percent-encoded (`/` → `%2F`, `%` → `%25`) |
| `incomingRoute` | — | the campaign's dialplan route name |
| `inPartnerId`, `packageAccountId`, `uom`, `rate`, `reservedAmount`, `balanceBefore` | — | per tier, as the call's `setBillingInfo` |
| `inPartnerCost` | — | the rated money cost when the MAIN BALANCE paid; **0 when a package unit paid**, as the call writes it |

## 2 · S18 — the SG30 call-shaped summary must not key on the device

**The defect, read at its source:** `CallSummaryBuilder.populateCommon` (:50–58) copies `cdr.outgoingRoute()` into
`s.tup_outgoingroute`, a KEY dimension, and `canonicalizeKeyDimensionsToColumnContract` (:148–156) clips it to 64 characters. With the
new route that key carries the MSISDN and the MAC, so the SG30 summary gets **one row per device per window**, and a long route is cut
mid-value.

**The rule:** for service group 30 only, `tup_outgoingroute` keeps the route's **first two parts** — `<ruleId>/<app>` — and nothing else.
Groups 10, 11 and 15 are untouched. The ad's own `sum_ad_day_30` / `sum_ad_hr_30` (campaign + content keyed, S13) are untouched.
billing-core is untouched: its `cdr.OutgoingRoute` is `TEXT` and its own mediation summary has no entry for group 30 (guarded by
`if (tables == null) return`), which the architect verified today.

**Tests:** a route with a device in it summarises into ONE row for two different devices of the same rule and app; a route longer than 64
characters is not cut inside a part; `0/<app>` (no rule matched) keys as itself; a group-10 record's route is still the whole string.
Break each rule once, see it red, restore.

## 2a · S18 must CALL the ad's key helper, not re-derive the prefix (2026-10-07 15:35)

The ad service now ships the route's reader beside its writer (ad-sphere main, ARCH-0057,
`src/main/java/com/telcobright/adsphere/flow/api/AdCallPreprocessor.java:147–212`):

| helper | what it answers |
|---|---|
| `routeKeyOf(route)` | `Optional<String>` — **exactly the key S18 needs**, `<ruleId>/<app>` AS THE ROW CARRIES IT (still encoded, so an app named `a/b` stays one part) |
| `routePartsOf(route)` | the rule id and the seven parameters decoded, by name; **empty** for anything not of the fixed shape, so a pre-ARCH-0055 row (the zone alone) is never read as a route |
| `routeValueFrom(value)` | the inverse of the encoding (`%2F` → `/` first, then `%25` → `%`) |

**Use `routeKeyOf`.** Either call it, or copy those four lines WITH the citation above — do not write a second prefix rule. Two rules for
one key drift the moment the route's shape changes again, and this summary is what the operator's money reports read. A row whose route is
not of the fixed shape (an old row) keeps the whole route as its key, exactly as today: the helper answers empty and nothing is invented.

## 3 · What S16 and S17 still owe

- **S16** — "recover after a database restart" is UNFINISHED on branch `s16-wip` (c8af8f1: `OutboxReaper`, `OutboxWorker`,
  `StoreDataSource`, three ITs). Finish it, or say what is left and why; the suite from a clean copy either way.
- **S17** — the owner's ruling of 2026-10-05: single-operator, and the call / ad / wifi-session kinds as **config-activated
  processors** (not branches of one method). Not started.
- **S15** stays PREPARED and UNMERGED on `no-bundled-tenant` (c2f9127) until the owner decides.

## 4 · Rules that stand

Lab = this PC only, every endpoint printed before a start, nothing dials `10.10.x` / `10.9.9.x` / `103.95.96.77`; **never start this
service's jar bare** (a bundled default tenant reaches live); the password by environment-variable NAME only; the suite green from a clean
copy before each push; one commit per item; `date` before writing any time; your report `SS-0004-done.md`, a draft from the first commit.

## 5 · 2026-10-08 14:33 +06:00 — LAUNCHED (the owner: "go"); the second rehearsal's F8 IS S16; the order of this round

**What the rehearsal met.** The ad lane's second whole-chain rehearsal R-2 ran this service at `postgres-ad-call` **7218213** for six
hours (ad-sphere `docs/ad-as-call/rehearsal/R-0002-update.md`, §1 row **F8** — read it:
`git -C ~/telcobright-projects/ad-sphere show origin/main:docs/ad-as-call/rehearsal/R-0002-update.md`). After the switch database's 30 s
stop (09:17:28; the same at 04:03:58) every bean of every tier that had traffic logged, every 60 s, `drain failed (N consecutive) — offset
STUCK, summaries lag until fixed … Caused by: org.postgresql.util.PSQLException: This connection has been closed`
(`JdbcOutboxStore.readOffset:46` ← `OutboxReader.drainOnce:274`; the rollback failed the same way), and
`TenantWatcher: schema res_46 could NOT be served`. **6,312 ERROR lines in 5 h**; the summaries froze at the stop (wroot `sum_ad_day_30`
2,670 views against `cdr` 3,257, five hours long, and `GET /summary` with them). A restart healed it in 5 s. The cause, as read there:
`runtime/internal/StoreDataSource.java:118–131` builds the Agroal pool with sizes and a timeout and NO validator;
`JdbcUnitOfWorkFactory.begin:83–96` closes a connection that fails at `enter`, but one that fails later in the drain goes back to the
pool open and is handed out again. That is S16, which you started on `s16-wip` (c8af8f1) and were stopped in.

**The order of this round** (one commit each; the rule broken once and seen red; push after each):

1. **S16 — finish it.** The rule: **after the database closes every connection — a restart, a failover, `pg_terminate_backend` — the
   summaries catch up with NO restart of this service:** the first drain after the database is back succeeds (at the latest the next
   poll), no bean stays STUCK, a schema the watcher could not serve is served again, the ERROR lines stop. A connection that threw a fatal
   error (SQLSTATE class 08, or 57P01 the administrator's termination) never goes back to the pool; one idle past a short interval is
   checked before it is handed out. Prove it on BOTH engines, two ways each: (a) the pool's backends terminated, (b) the database's lab
   container restarted — each followed by traffic whose summaries equal the `cdr` rows. Your `tools/lab/outage-e2e.sh` is the start.
   Say what the check costs a borrow. Break it once (the check off) → red.
2. **S18** — §2 and §2a as written (CALL the ad's `routeKeyOf`, never re-derive the prefix).
3. **Stop and report** (`SS-0004-done.md`). **S17 waits for the owner's word** (it is a design change: the kinds as processors).

**Where.** `s16-wip` also carries the owner's two schema orders, made by me (53b708e the route columns 1000 wide, 4976999 the two route
indexes; the ledger's SC-001 / SC-002): keep them; your report's suite is of a head that has them. I moved this repository's MAIN checkout
to `postgres-ad-call` so the branch is free: make your worktree `summary-service/worktrees/s16-wip` for branch `s16-wip` and work only
there. After the round I verify, merge `s16-wip` into `postgres-ad-call`, and bring `no-bundled-tenant` up to it by a MERGE (no force
push), so S15 stays one change on top, unmerged, for the owner.

**Rules, in addition to §4.** Java 21. Your own Maven repository, chained read-only to the shared one: `-Dmaven.repo.local=$HOME/.m2/summary-service-repo
-Dmaven.repo.local.tail=$HOME/.m2/repository` — never install into `~/.m2/repository`. The commit trailer `Co-Authored-By: <the model you
run as> <noreply@anthropic.com>`. **WireGuard is UP on this PC:** `10.10.x.x` and `10.9.9.x` route to real boxes — never connect to
`172.17.191.1`, `10.10.191.x`, `10.10.188.x`, `10.10.175.x`, `10.9.9.x` (the devlog card's Redis `10.9.9.7` is allowed) or `103.95.96.77`.
Lab containers named for you and removed at the end; a test that fails because something cannot be reached: stop and report; nothing of
yours left running; no live environment touched; the merges are mine. The agents run ONE AT A TIME (the owner's rule): nothing else runs
on this PC while you work.
