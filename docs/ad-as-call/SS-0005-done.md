from: SS        to: ARCH        kind: done        number: 0005
date: 2026-10-11 04:22 +06:00
branch: wifi-101 (from postgres-ad-call 0d85677 — the brief itself, over S16/S18 merged in 45b01af)
head: 5248f32 (item 3); this note is the commit after it — pages only
subject: ARCH-0077-S — the domestic WiFi session (service group 101) summed as a domestic call under suffix 101: the populateServiceGroup branch (item 1), the sum_voice_*_101 table set on both engines (item 2), the two config-instantiated beans in the bed and the lab profile with the lab run (item 3); every rule broken once and seen red; the suites from a clean copy, per engine

# SS-0005 · done — ARCH-0077-S items 1–3

## 0 · What to relay

| | |
|---|---|
| the branch | `wifi-101`, pushed after each item: **abcf178** = item 1, **f961875** = item 2, **5248f32** = item 3. `postgres-ad-call` and `main` are not touched; the merge is yours |
| item 1 | DONE (§1). `CallSummaryBuilder.populateServiceGroup` has the 101 branch: the 30 branch's customer-direction stamp WITHOUT the ad's rule/app route key, behind the VOICE's `ChargingStatus` early return. Red first: a 101 record threw `no summary mapping for service group 101` (13 of 14) |
| item 2 | DONE (§2). `sum_voice_day_101` / `sum_voice_hr_101` are in the shipped MySQL reference copy as copies of the template; the service makes the pair itself at the 101 beans' first use on MySQL and on PostgreSQL (`SumVoiceDdl`, unchanged — one description, another name); proven on both labs from the catalog: column for column, width for width the 30 set's table |
| item 3 | DONE (§3). `dailyCallSummarySg101` / `hourlyCallSummarySg101` in `profile-radius2.yml` and `profile-lab.yml` beside the 30 ones (eight beans). The lab run with the packaged jar on both engines: the sessions land in the 101 tables only — minutes and money two rows, the zero-second sessions counted and not costed — the 30 tables and the ad tables unchanged by them, `sum_chargeable_*` carrying the 101 legs |
| TF_min beside BDT | the unit rides into the summary as the row carries it: `tup_customercurrency` = `idBilledUom`, a KEY column — a `TF_min` row and a `BDT` row, never one, never converted (§1.2, §4 F1). The same in `sum_chargeable_*` (`tup_billeduom`). No column was invented |
| the suites | from a clean copy of 5248f32 (`git archive`, a fresh private repository chained read-only, offline; the two labs up, no Kafka): unit **320 / 0 / 0 / 0**, ITs **77 / 0 / 0 / 3 (the 3 = KafkaListenersIT, no broker in my lab)** (§5) |
| to decide | §4: F1 (the unit as the row carries it — nothing to convert, say if a conversion is wanted somewhere else), F2 (billing-core's 101 row carries the minutes in `PackageAmount` / `IdPackageAccount`, columns the blob's `Cdr` model does not read — the SG10 package stamps are 0 for it), F3 (a 0-second session opens no `TF_min` row of 0 — the answer to BC-0007's assumption 2 from this side), F4 (the two lab scripts' bean count) |

## 1 · Item 1 — the 101 branch (abcf178)

### 1.1 · As built

| where | what |
|---|---|
| `summarybeans/call/internal/CallSummaryBuilder` | `SERVICE_GROUP_WIFI = 101`; `populateServiceGroup` dispatches a 101 chargeable to `stampWifiSession` — the top-level chain gained one named line. The branch: `if (!charged) return;` (the voice rule, as 10 and 11 have it), then `tup_matchedprefixcustomer` from the row (a session has none → `''`), `tup_customerrate = unitPriceOrCharge` (the reference rate), `tup_customercurrency = idBilledUom`, `customercost = BilledAmount`, `longDecimalAmount2` / `intAmount1` = SG10's package stamps from `AdditionalSystemCodes` / `AdditionalPartyNumber` when the row carries them. No supplier, tax or VAT column. No route key: a route a producer sends stays whole (S18 is the 30 branch's alone) |
| `testkit/WifiSessionTestSupport` (test) | the blob entry billing-core writes for a session (BC-0007; the contract page's §2a sample): the voice `cdr` row with `ServiceGroup` 101, partner 7, `W1`, `site-mirpur-10`, 1800 s, `InPartnerUom` TF_min, `PackageAmount` 30, `IdPackageAccount` 5101, `CustomerRate` 0.10, and ONE customer chargeable `101 / 30 / TF_min / 30 / 1800 TF_s / 0.10`; `paidInMoney` (3.00 BDT, no bucket), `ofZeroSeconds` (0 s, `ChargingStatus` 0, 0 TF_min, the answer time kept); the two 101 beans as the profiles name them |
| `summarybeans/call/CallSummaryGroup101Test` (test) | 14: the tables; the row by payer and site; the charge (rate at 6 places, unit, amount, empty prefix); a money session; minutes and money two rows; the zero-second session counted, connected, not charged, not costed, no unit; it keys as an uncharged call beside the minutes row; nothing bought, no tax; the route whole; the package stamps when carried and 0 otherwise; not behind the early return; the hour bucket; the 101 bean and the 30 / 10 / ad beans leave each other's records alone; ten sessions one row |

### 1.2 · What `tup_customercurrency` TF_min beside BDT means (your question; billing-core's F1)

The unit rides into the summary **as the row carries it**: `tup_customercurrency` is `idBilledUom` and a KEY column of
`sum_voice_*`, so a tier that paid 30 minutes from a bucket is a `TF_min` row with `customercost` 30 and a tier that
paid 3.00 in money is a `BDT` row with `customercost` 3.00 — in the same table, never summed into one row, never
converted (the 30 branch's rule since SS-0001, now the 101 branch's too). A reader that wants minutes reads the
`TF_min` rows; one that wants money reads the `BDT` rows. `sum_chargeable_*` keys the same way on `tup_billeduom`. No
column was invented; nothing is said about a conversion rate because there is none in the row.

### 1.3 · The reds (each a one-text swap in `CallSummaryBuilder`, the class run, the file put back from the index)

| the break | red (of 14) |
|---|---|
| red first: no 101 branch | **13** — `IllegalArgumentException: no summary mapping for service group 101` |
| the early return gone (a 0-s session costed) | 3 — counted-not-costed, keys-as-uncharged, no stamps behind the return |
| the unit not stamped (every row BDT) | 4 — two rows, the charge, the mixed batch, the uncharged row |
| the amount from the cdr's money, not the chargeable | 5 |
| the rate not stamped | 1 |
| the ad's rule/app route key applied to a session | 1 |
| the package stamps dropped | 1 |
| a supplier column stamped | 1 |
| the prefix from the chargeable, not the row | 1 |
| the branch keyed on 100 | 13 (the throw again) |

Unit suite after the item: **316 / 0 / 0 / 0** (302 + 14).

## 2 · Item 2 — the table set with suffix 101, both engines (f961875)

### 2.1 · As built

| where | what |
|---|---|
| `db/sum_voice.provisional.sql` | the 101 pair as `LIKE sum_voice_day_03`, beside 01/02/03; the head names the suffixes in use (03 SG10, 02 SG11, 30 the ad view — self-provisioned only, no copy there — 101 the WiFi session) |
| `SumVoiceDdl` | unchanged: `table(name)` describes every suffix's table once; the 101 beans hand it `sum_voice_day_101` / `sum_voice_hr_101` (`CallSummaryBean.table()`), and the service makes them at the bean's first use — MySQL one CREATE with the full daily partition set inside it, PostgreSQL the plain table and its three indexes in one transaction |
| `summarybeans/call/internal/SumVoiceProvisionalFileTest` (test) | 3: the file lists the pair; the rendered MySQL DDL of each 101 table has the template's 48 columns (id + 47) in its order with its types and widths; the 101 and the 30 descriptions are one text on both engines, the name apart |
| `it/OutboxConsumerContract` (both engine ITs) | `the_101_table_set_is_made_on_this_engine_exactly_as_the_30_set_is_and_as_described`: the three tables made by `ensureProvisioned`, then read from the catalog (`columnWidths`) — the 101 day and hour tables equal the 30 set's column for column, width for width; `TableDdl.bringUpToDate` has nothing to do; made empty |
| `it/OutboxConsumerIT`, `it/MySqlTreeIT` | the MySQL ITs take their password by the NAME of a variable — `SUMMARY_IT_MYSQL_PASSWORD` in the environment — never on a command line (the `-D` form stays for a lab that has none); the fixture drops the 101 tables with the others |

### 2.2 · The reds

| the break | where red |
|---|---|
| the reference copy loses the 101 pair | unit: `the_reference_copy_lists_the_101_pair…` |
| `SumVoiceDdl` drops a column (`decimalAmount3`) | unit: the template comparison (47 ≠ 48) |
| `SumVoiceDdl`'s routes 64 wide again (the file says 1000) | unit: the template comparison |
| the provisioning skips every table (`ensureProvisioned`) | IT, **both engines**: `expected: <48> but was: <0>` |
| a MySQL word in the PostgreSQL CREATE (`ENGINE=InnoDB`) | IT, **PostgreSQL alone** (`write failed: CREATE TABLE IF NOT EXISTS …`); MySQL green |
| the MySQL primary key without the partition column | IT, **MySQL alone** (the partitioned CREATE refused); PostgreSQL green |

After the item: unit **319 / 0 / 0 / 0**; `PostgresOutboxConsumerIT` **34 / 0**, `OutboxConsumerIT` **26 / 0** (the whole classes, on the two labs).

## 3 · Item 3 — the two beans in the bed profile and the lab profile; the lab run (5248f32)

### 3.1 · As built

| where | what |
|---|---|
| `config/tenants/btcl/radius2/profile-radius2.yml` (the bed), `config/tenants/btcl/lab/profile-lab.yml` (the lab) | `dailyCallSummarySg101 {window: daily, service-group: 101, table-suffix: "101"}` and `hourlyCallSummarySg101 {window: hourly, …}` under `summary.beans`, both names in `enabledSummary` beside the 30 ones — eight beans, the same list in both files; the head says what group 101 is |
| `config/internal/WifiProfileTest` (test) | 23 (+2): the lab profile lists the eight (and nothing else); the four call beans are made from the profile alone (name, table, group); the bed profile lists the same eight and makes the 101 pair the same way, key for key |
| `it/OutboxConsumerContract` (both engine ITs) | `one_outbox_row_of_wifi_sessions_feeds_the_101_tables_and_the_chargeable_table_and_leaves_the_30_tables_alone`: two outbox rows (a view; two bucket sessions + a money session + a zero-second session + a view) drained by the 30, the two 101, the chargeable and the ad beans from their own bookmarks — `sum_voice_day_101` three rows `TF_min 2|2|60.000000`, `BDT 1|1|3.000000`, `'' 1|0|0.000000`, the hour table the same four sessions; `sum_voice_day_30` the two views and no `TF_min` row; `sum_ad_day_30` two views; `sum_voice_day_03` nothing; `sum_chargeable_day` `101/TF_min 3|60.00000000`, `101/BDT 1|3.00000000`, `30/BDT 2|1.00000000`; a re-drain changes nothing |
| `tools/lab/wifi-101-e2e.sh` (new) | the story below, both engines, the packaged jar through `run-lab.sh btcl/lab`; the labs' ports and the MySQL password's variable NAME by environment (`SS_LAB_PG_PORT`, `SS_LAB_MYSQL_PORT`, `SS_LAB_MYSQL_PASSWORD_VAR`), the lab's own containers by default |
| `testkit/LabOutboxRow` (test) | takes `ad` (as before) or `wifi-minutes` / `wifi-money` / `wifi-zero`: one record as billing-core writes it, for the script |
| `docs/ad-as-call/postgres-ad-profile.md` | the eight beans in the profile text and the tables the service makes; the keys table; "8 for each tier"; the new lab script in §7 |
| `tools/lab/outage-e2e.sh`, `tools/lab/upgrade-e2e.sh` | the lab profile's bean count they waited on: `is served: 6 of 6` → `8 of 8`, six bookmarks → eight, the ERROR-line ceiling 18 → 24 (§4 F4) |

### 3.2 · The lab run — `tools/lab/wifi-101-e2e.sh both`, the packaged jar, 2026-10-11 03:57–03:59 (+06), both HOLD

One tenant (`summary.tenants.mode=single`), the lab profile's eight beans and 5 s poll; every record written is ONE `cdr`
row and ONE outbox row in one transaction, so "the sessions summed" is read against `count(cdr) where servicegroup = 101`.
The service was started by `run-lab.sh` (the endpoints read first: the store on 127.0.0.1, the ping broker 127.0.0.1:7692 —
not in this lab, so the workers drained on the poll — HTTP 127.0.0.1:7671); on MySQL the SECRET line read `from the
environment variable LAB_MYSQL_PASSWORD (summary.store.password-ref)` — the name, never a value.

| step | PostgreSQL 16 (127.0.0.1:7548) | MySQL 5.7.44 (127.0.0.1:3306) |
|---|---|---|
| 1 · served | `is served: 8 of 8`; `dailyCallSummarySg101 → sum_voice_day_101`, `hourlyCallSummarySg101 → sum_voice_hr_101` registered | the same (the eight tables made with the 60-day horizon) |
| 2 · 5 ad views (group 30) first | 8 of 8 beans at the last row in 5 s; `sum_voice_day_30` = `61\|BDT\|5\|2.500000`, the hour table the same, `sum_ad_day_30` 5 views | the same |
| 3 · 12 sessions of group 101: 7 from the bucket (30 TF_min each), 3 in money (3.00 BDT each), 2 of zero seconds | 8 of 8 at the last row in 4 s | in 5 s |
| 4 · the 101 tables (calls\|charged\|cost) | day and hour alike: `TF_min 7\|7\|210.000000`, `BDT 3\|3\|9.000000`, no unit (the 0-s sessions) `2\|0\|0.000000`; 3 rows; `sum(totalcalls)` 12 = the `cdr` rows of group 101 | the same |
| 5 · the 30 tables and the ad tables | `sum_voice_day_30` and `_hr_30` byte for byte as they stood after step 2; 5 calls; `sum_ad_day_30` / `_hr_30` 5 views | the same |
| 6 · `sum_chargeable_day` / `_hr` (legs\|amount) | `101/TF_min 9\|210.00000000` (the 7 bucket sessions and the 2 zero-second legs of 0 TF_min), `101/BDT 3\|9.00000000`, `30/BDT 5\|2.50000000` | the same |
| ERROR lines of the service | 0 | 0 |

(On MySQL the outbox's `max(id)` ran to 19 for 17 rows — its bulk auto-increment gaps, as SS-0004 noted; the sums equal the `cdr` rows exactly.)

### 3.3 · The reds (one text swapped, the test or the IT run, the file put back from the index)

| the break | where red |
|---|---|
| the lab profile loses the two 101 beans | `WifiProfileTest`: the eight-bean list (`[…Sg30, …Chargeable…]` without 101), and the bed's "same beans" |
| the bed profile's daily 101 bean filters group 100 | `WifiProfileTest`: the bed test — `expected: <101> but was: <100>` |
| the lab profile's daily 101 bean writes the 30 table (`table-suffix: "30"`) | `WifiProfileTest`: `expected: <sum_voice_day_101> but was: <sum_voice_day_30>`, and the bed's "same keys" |
| every call bean keeps a 101 record (`CallSummaryBean.buildBatch`) | IT, **both engines**: "the 30 table: the two views and not one session ==> expected: <2> but was: <6>" |
| the ad bean takes a session (`AdCallCdr.isAdView`) | IT, **both engines**: "the ad table sees no session ==> expected: <2> but was: <6>" |
| the chargeable bean leaves the 101 legs out | IT, **both engines**: the 101 chargeable row empty |

Unit suite after the item: **320 / 0 / 0 / 0 (319 + the profile test's net one)**.

## 4 · Findings — yours to decide

| # | finding | proposal |
|---|---|---|
| F1 | **TF_min beside BDT** (billing-core's F1, your question): the summary keeps the unit as the row carries it — `tup_customercurrency` (`sum_voice_*`) and `tup_billeduom` (`sum_chargeable_*`) are key columns, so the minutes and the money of one tier are two rows of one table, never added, never converted. Nothing was invented. A reader that wants one figure needs a rate the row does not carry | none here. If a conversion is ever wanted, it is a reader's (ad-sphere's / the BSS's) with a rate from the package, not a summary column |
| F2 | **the package stamps are 0 for billing-core's 101 row.** The brief's SG10 stamps read the legacy fields `AdditionalSystemCodes` / `AdditionalPartyNumber`; billing-core's 101 row carries the minutes and the bucket in `PackageAmount` / `IdPackageAccount` (the six new `cdr` columns of the ratified wire), which this service's blob `Cdr` model does not read. So `longDecimalAmount2` and `intAmount1` are 0 for every session the bed will see; the minutes are in `customercost` of the `TF_min` row all the same, and the bucket is nowhere in the summary | yours: (a) leave it (the minutes are in `customercost`; the bucket is on the `cdr` row), or (b) let the blob model read `PackageAmount` / `IdPackageAccount` and the 101 branch stamp them when the legacy fields are empty — two lines in `Cdr` and two in `stampWifiSession`, for a next round, with billing-core's say on whether the ad's 30 wants the same |
| F3 | **a session of 0 seconds opens no `TF_min` row of 0** — the answer to BC-0007 §3's assumption 2 from this side: behind the voice's early return nothing is stamped, so the zero-second session is a row with no unit (`tup_customercurrency` `''`), `totalcalls` 1, `successfulcalls` 0, `customercost` 0 — as an unanswered call of group 10 is. In `sum_chargeable_*` its leg IS a `TF_min` row of 0 (that table carries every leg as it is), so the chargeable tables show `legs` 9 where the voice table shows 7 charged sessions | none; said so that nobody reads the two counts as a disagreement |
| F4 | **the lab scripts pinned the lab profile's bean count.** `tools/lab/outage-e2e.sh` and `tools/lab/upgrade-e2e.sh` waited for `is served: 6 of 6` and for six bookmarks; with the two 101 beans the lab profile has eight, so both now say 8 (and the ERROR-line ceiling 18 → 24). Not re-run this round (S16's story is not mine to re-prove); the edits are the counts and their comments only | none; say if you want S16's story re-run on the eight-bean profile before the bed |
| F5 | **the MySQL ITs' password by variable name.** The suite read it from `-Dsummary.it.mysql.password` alone — a value on a command line. It now reads `SUMMARY_IT_MYSQL_PASSWORD` from the environment first (the `-D` form stays for a lab with no password). The clean-copy run and every lab run of this round handed it variable to variable | none |

## 5 · The suites, from a clean copy of 5248f32

From a clean copy of 5248f32 — `git archive` into a fresh folder; the FRESH private repository `~/.m2/wifi-101-ss-clean-repo`
chained read-only onto `~/.m2/repository`; `mvn -o verify` (the jar packaged, every IT class); PostgreSQL 16 on 127.0.0.1:7548
and the local MySQL 5.7.44 on 127.0.0.1:3306 with its password by the NAME of a variable; no Kafka — 04:15:57 to 04:22:01 (+06):

| | tests · failures · errors · skipped |
|---|---|
| unit (surefire) | **320 · 0 · 0 · 0** (302 at the base + 14 of item 1 + 3 of item 2 + 1 of item 3) |
| ITs (failsafe), all classes | **77 · 0 · 0 · 3** |
| PostgreSQL | **44 · 0 · 0 · 0** — `PostgresOutboxConsumerIT` 35 (33 + the two of this round), `PostgresTreeIT` 9 |
| MySQL | **29 · 0 · 0 · 0** — `OutboxConsumerIT` 27 (25 + 2), `MySqlTreeIT` 2 (the password by `SUMMARY_IT_MYSQL_PASSWORD`) |
| engine-neutral | `BuildBakesNoTenantIT` 1; `KafkaListenersIT` 3 **skipped** — no broker in my lab (§6): with the lab's Kafka up they are the 3 that SS-0004's 73 held |

Before (the base's clean copy in SS-0004, with Kafka): unit 302, ITs 73 / 0. Seen on the way, not of this round's code: S16's
idle-check timing printed by the suite read PostgreSQL p50 131 / p99 1817 / max 2947 µs, MySQL 139 / 5977 / 12980 µs — higher
than SS-0004's (a busier box tonight, with other agents' labs on it); the test measures and prints, it does not assert a bound.

## 6 · The rules, kept

Java 21 (Corretto 21.0.9); Maven 3.9.10 with `-Dmaven.repo.local=$HOME/.m2/wifi-101-ss-repo -Dmaven.repo.local.tail=$HOME/.m2/repository`,
offline (`-o`) for every build; the head repository stayed EMPTY (everything came from the chained tail, read-only); the
clean copy had its own fresh `wifi-101-ss-clean-repo`; nothing installed into `~/.m2/repository`. The jar was never
started bare: every start by `tools/lab/run-lab.sh btcl/lab` with the lab profile, the endpoints printed and read first,
HTTP on 127.0.0.1:7671. The labs: my own `ss-pg16-lab` (postgres:16, 127.0.0.1:7548, trust — no password exists; ports
7543–7547 not touched), **removed at the end**; the local MySQL 5.7.44 at 127.0.0.1:3306 as root with the password by the
NAME of its variable (`set -a; . ~/.lab-env/mysql.env; set +a`, then `SUMMARY_IT_MYSQL_PASSWORD="$LAB_MYSQL_PASSWORD"` /
`SS_LAB_MYSQL_PASSWORD_VAR=LAB_MYSQL_PASSWORD` → `summary.store.password-ref=env:LAB_MYSQL_PASSWORD`) — on no command line, in no
file of the repository, not in this note; its databases of this round (`wifi101_summary_it*`, `wifi101_clean_it*`,
`lab_wifi101`) **dropped at the end**; the owner's `summary_it` database there was not touched. No Kafka was started
(the lab's default port 7692 may be another agent's tonight): `KafkaListenersIT` self-skipped, the service's ping
listener said once that its broker is not there and the workers drained on the poll. Nothing dialled off this box
(WireGuard up; 172.17.191.1, 10.10.191.x, 10.10.188.x, 10.10.175.x, 10.9.9.x, 103.95.96.77 never reached). Nothing left
running. `date` before every time written. The worktree `summary-service/worktrees/wifi-101` stays for your merge; the
main checkout on `postgres-ad-call` and `main` were not touched; no other agent's worktree was entered.
