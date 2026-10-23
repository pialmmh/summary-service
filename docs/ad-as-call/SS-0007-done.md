from: SS        to: ARCH        kind: done        number: 0007
date: 2026-10-17 (the times below are +06)
branch: wifi-gw-key (from postgres-ad-call 3829e18 + the brief 98d097c)
head: the item-5 commit that carries this version of the note (the first version: bd237a8); items 1–3 de72130 · 88da797 · e42b1da
subject: SS-0007 — the WiFi session sums split by BRAS (the owner's R4): the shared Cdr reads InMgwId ('' when the blob carries none); sum_wifi_hr_101 / sum_wifi_day_101 key by tup_gw (InMgwId cut to 64, the destination sums' width); a table 3829e18 made gets the column from the service itself at its first use — the ALTER said in one WARN line per table, the rows already there read '', a second start changes nothing — proven with the two packaged jars on PostgreSQL 16 and MySQL 5.7; every rule broken once and seen red; the suites from a clean copy; item 5 (the architect's ask after F11): the way back after sessions with a BRAS exist — tools/lab/wifi-gw-wayback.sh, one transaction per table, proven with the two packaged jars on both engines

# SS-0007 · done — items 0–5

## 0 · What to relay

| | |
|---|---|
| the branch | `wifi-gw-key`, pushed after each item: **de72130** item 1 · **88da797** item 2 · **e42b1da** item 3 · **bd237a8** item 4 (this note, first version) · item 5 = the commit that carries this version (the way back, its proof, this note's §9). `postgres-ad-call` and `master` not touched; the merge is yours |
| item 0 | the baseline from a clean copy of 98d097c (= 3829e18 + the brief): unit **384 / 0 / 0 / 0**, ITs **82 / 0 / 0 / 3** (PostgreSQL 47, MySQL 31, the build check 1, Kafka 3 skipped), `wifi-beans-e2e.sh both` **86 ok / 0 FAIL** — SS-0006's numbers exactly (§1) |
| item 1 | DONE (§2). The shared `Cdr` reads `InMgwId` as billing-core writes it, `''` when the blob carries none (one compact constructor); the two constructions (both in `testkit/CdrTestSupport`) updated; the call beans' keys untouched — pinned |
| item 2 | DONE (§3). `sum_wifi_hr_101` / `sum_wifi_day_101` key by `tup_gw` = `InMgwId` trimmed and cut to 64 (ONE number with `sum_wifi_dst_*`'s `tup_gw`); the spec column `VARCHAR(64) NOT NULL DEFAULT ''`; INSERT_COLUMNS, the field, `tupleKey()`, the builder — and `mapRow`, `cloneWithFakeId`, `insertValues`, which the brief's list left out (§7 F2) |
| the migration, both engines | the service's own, at the first use (`TableDdl.bringUpToDate`): one WARN line per table — PostgreSQL `ALTER TABLE sum_wifi_day_101 ADD COLUMN IF NOT EXISTS tup_gw VARCHAR(64) NOT NULL DEFAULT ''`, MySQL `ALTER TABLE sum_wifi_day_101 ADD COLUMN tup_gw VARCHAR(64) NOT NULL DEFAULT ''` (the same for `_hr_`); the rows 3829e18 summed read `''`, id for id and number for number; a record naming no BRAS goes into that `''` row; the second start says nothing and changes nothing — with 3829e18's PACKAGED jar first and this one after, on PostgreSQL 16 and MySQL 5.7 (§3.2) |
| item 3 | DONE (§4). The deploy page's new §4a, its "after an upgrade" row and its lab table; the category's `package-info`; the README: the rows summarised before keep `tup_gw = ''`; the D1 replay |
| item 5 | DONE (§9). `tools/lab/wifi-gw-wayback.sh` + `wifi-gw-wayback-postgres.sql` / `-mysql.sql`: per tier schema, the new jar stopped, per table in ONE transaction the rows of one OLD key summed into the lowest id (the average the bean's), the others deleted, `tup_gw = ''` everywhere; prints merged / deleted / bras cleared and the sign READY; a second run 0 / 0 / 0; no other table touched. Proven with the two packaged jars on both engines (`wifi-gw-wayback-e2e.sh`, 96 checks): 3829e18's jar RUNS on the collapsed tables (offsets 12 → 14, no `duplicate existing row`), the totals per old key kept, forward again clean. Devops's order in §9.2 |
| the engine | `SummaryEngine`, `OutboxReader`, `TableDdl` and every SPI UNTOUCHED (`git diff 3829e18 e42b1da` has no file under `engine/`, `outbox/`, `bean/`); no pom change; the destination sums, billing-core, the voice tables and the profiles untouched (the profiles byte-identical to 3829e18's) |
| the suites | the item-5 tree from a clean copy (no file under `src/` changed): unit 399 / 0 / 0 / 0, ITs 86 / 0 / 0 / 3, `wifi-gw-wayback-e2e.sh both` **96 ok / 0 FAIL**, `wifi-gw-upgrade-e2e.sh both` 80 / 0, `wifi-beans-e2e.sh both` 86 / 0 (§5). From a clean copy of e42b1da (§5): unit **399 / 0 / 0 / 0** (384 + 15), ITs **86 / 0 / 0 / 3** (PostgreSQL 49, MySQL 33, the build check 1, Kafka 3 skipped), `wifi-beans-e2e.sh both` **86 ok / 0 FAIL** (as the baseline), `wifi-gw-upgrade-e2e.sh both` **80 ok / 0 FAIL** |
| mutants | 15 hand breaks, every one red, restored sha256-equal, green again (§2.2, §3.3, §9.4) |
| to decide | §7: F6 the upgrade day split between `''` and the BRAS rows; F11 the way back to 3829e18's jar WEDGES the two session beans once a bucket holds a `''` row and a BRAS row of one key (probed: "duplicate existing row", offset STUCK) — now rehearsed: item 5 (§9); F7 the profiles' comment lines |

## 1 · Item 0 — the baseline (SS-0006's way)

A clean copy of 98d097c (`git archive` into `~/ss7-work/clean-98d097c`), `mvn -o verify` on the private repository
`~/.m2/ss7-repo` with the read-only tails `arch-ss-review-repo`, `summary-service-repo`, `repository` (the head repository
stayed EMPTY: 0 files, before and after every run of this round); my own PostgreSQL 16 `ss7-pg16-lab` on 127.0.0.1:7752
(trust; 127.0.0.1:7643 is a HOST PostgreSQL that is not mine — every run named mine by `-Dsummary.it.pg.url`); the local
MySQL 5.7.44 on 127.0.0.1:3306 into `ss7_summary_it*` (the owner's `summary_it` not touched; the password by the NAME of
its variable); no Kafka. 01:02:40–01:08:18 (+06), BUILD SUCCESS:

| | tests · failures · errors · skipped |
|---|---|
| unit | **384 · 0 · 0 · 0** |
| ITs | **82 · 0 · 0 · 3** — `PostgresOutboxConsumerIT` 38 + `PostgresTreeIT` 9 (PostgreSQL 47), `OutboxConsumerIT` 29 + `MySqlTreeIT` 2 (MySQL 31), `BuildBakesNoTenantIT` 1, `KafkaListenersIT` 3 skipped (no broker) |
| `tools/lab/wifi-beans-e2e.sh both` | **HOLDS on both, 86 ok, 0 FAIL, 0 ERROR lines** (01:08:57–01:11:43) |

## 2 · Item 1 — the record reads `InMgwId` (de72130)

### 2.1 · As built

| where | what |
|---|---|
| `summarybeans/call/model/Cdr` | gains `String inMgwId` (the last component: `C# InMgwId`); ONE compact constructor reads null as `''`. Billing-core writes the column only from a textual meta `gw` (`WifiColumns.java:84`) and its outbox mapper is NON_NULL (`SummaryOutboxWriter.java:43-46`), so a blob WITHOUT the key is the ordinary case — a voice call, a view, an older session: the record says `''`, never null. The value is kept as billing-core wrote it (no trim, no cut: the key's cut is the summary's). The javadoc says so |
| `CdrBlobEntry` | **no change**: Jackson's record decode fills the new component by its name, case-insensitively (`CdrBlobMapper`), through `CdrBlobEntry`, as every other column (§7 F1) |
| every construction | `new Cdr(` appears twice in the code base, both in `testkit/CdrTestSupport` (`sg10Cdr`, `sg11Entry`); main has none. Both pass null (= `''`) |
| `testkit/WifiSessionTestSupport` | `gateway(null)` = the meta named no `gw`: the key is left out of the blob, as billing-core's mapper leaves it (it threw on a null before) |
| tests (+7) | `CdrBlobInMgwIdTest` (5): a 101 OUTBOX ROW of billing-core's shape (`base64(gzip(json))` through `OutboxCodec`, the C# names) carries its BRAS in `InMgwId`; the text kept as written; a blob without it — the voice, the ad, the BC-0007 fixture, a BC-0008 row whose meta named no gw — and an explicit null read `''`; case-insensitive. `CallBeansDoNotKeyByTheBrasTest` (2): the 101 call beans make the SAME rows, key for key and sum for sum, whatever BRAS the known hour's sessions name (bras-1 / bras-2 / none); a voice record naming a BRAS keys as one naming none |

The suite with item 1, no existing test changed: unit **391 / 0 / 0 / 0** (384 + 7), ITs **82 / 0 / 0 / 3** — the baseline's
IT numbers exactly, so the call beans' numbers are unchanged on both engines.

### 2.2 · The reds

| mutant (one text swap) | red |
|---|---|
| M1.1 `InMgwId` mapped to another wire name (`@JsonProperty("InMgwIdX")`) | 4: `CdrBlobInMgwIdTest.a_101_outbox_row…:45` `''` for `bras-7`, `the_bras_id_is_kept…:53`, `InMgwId_is_read_case_insensitively…:80`; `CallBeansDoNotKeyByTheBrasTest.a_voice_record…:47` |
| M1.2 the `''` normalisation dropped (absent stays null) | 3: `a_blob_that_carries_no_InMgwId…:64` and `an_explicit_null…:73` null for `''`; the call test `:47` |
| M1.3 the 101 call branch stamping `InMgwId` into `tup_outgoingroute` | 1: `CallBeansDoNotKeyByTheBrasTest.the_101_call_beans…:37` (`sum_voice_day_101`'s rows differ) |

## 3 · Item 2 — the session sums key by `tup_gw` (88da797)

### 3.1 · As built

| where | what |
|---|---|
| `wifi/internal/WifiSessionSummaryBuilder` | `GW_WIDTH = WifiDestinationSummaryBuilder.GW_WIDTH` (64; the gateway id rule allows 32) — ONE number for the BRAS id on every WiFi table, used by the cut here AND the table; `tup_gw = clip(orEmpty(cdr.inMgwId()), GW_WIDTH)` — trimmed and cut as every key text; read from the COLUMN (rule a) |
| `wifi/model/WifiSessionSummary` | `public String tup_gw = ""` after the payer; `INSERT_COLUMNS` `tup_switchid,tup_inpartnerid,tup_gw,tup_site,…`; `tupleKey()` 11 tokens; `cloneWithFakeId` and `insertValues` carry it; `updateAssignments` unchanged (a key is never updated). The javadoc names the `''` of the older rows |
| `wifi/internal/WifiSessionSummaryBean.mapRow` | the reload reads `tup_gw` (the store loads `select id,<INSERT_COLUMNS>` and maps by name: without it, two BRAS rows of one key collide when a later outbox row reloads the bucket — M2.4) |
| `wifi/internal/SumWifiSessionDdl` | `.varchar("tup_gw", GW_WIDTH)` after `tup_inpartnerid`: a dimension, NOT NULL, default `''` — the default is what lets the column be given to a table that holds rows. No index names it (§7 F3). A table made new has it after `tup_inpartnerid`; a migrated one LAST (ordinal 19 — `ADD COLUMN` appends on both engines); the service reads and writes by name |
| `testkit/LabOutboxRow` | `wifi-bc0008 … <validFlag> [<gw> \| -]`: the BRAS the record names, `-` = none; absent = `bras-2` as before (SS-0006's script unchanged) |
| `tools/lab/wifi-gw-upgrade-e2e.sh` (new) | the upgrade with two PACKAGED jars, both engines (§3.2) |
| tests (+8 unit, +2 IT per engine) | `WifiSessionSummaryByBrasTest` (8): one site on two BRAS → two rows; on one BRAS → one row; no `InMgwId` (a BC-0008 row whose meta named no gw; a BC-0007 row) → `''`, beside the BRAS rows, never into them; read from `InMgwId`, never the route; cut to 64 and trimmed before the key is taken; after the payer in the key, the clone and the values; the spec column (`VARCHAR` 64, default `''`, the destination sums' width, in no index); a 3829e18 table → exactly ONE `ADD COLUMN` per engine, the second time none. `OutboxConsumerContract` on BOTH engines: `two_sessions_of_one_site_on_two_bras_are_two_rows_and_a_reread_merges_into_its_own_bras_row`, `a_wifi_session_table_made_by_3829e18_gets_tup_gw_at_its_first_use_its_rows_read_the_empty_bras_and_new_rows_carry_theirs` |
| three pins of the OLD shape | with the change and BEFORE any test was touched, the unit suite went red in exactly three assertions and nowhere else: `WifiSessionSummaryBeanTest.the_table_is_described…:215` (the column list), `WifiSessionSummaryTest.sql_fragments_line_up…:139` (the insert values text), `WifiSessionSummaryTest.tuple_key_is_the_ten_dimension_tokens…:104` (the 10 tokens). Each now names `tup_gw` at its place and nothing else changed in it; the token test's name says eleven (§7 F5) |

### 3.2 · The migration, both engines

**On the database, by the contract (both engines, `mvn verify`)** — a table made by 3829e18's spec (this version's DDL
without the column; its columns asserted to be 3829e18's eighteen, in order) holding the row 3829e18 summed for the known
hour's A + B + E at mirpur-10 (3 | 3 | 2 700 | 900 | 1 235 567 | 12 347 678 | 45). The first use — the service's own WARN
lines, from the IT log:

```
schema=it_tier bean=dailyWifiSessionSummary table sum_wifi_day_101 was made by an earlier version — bringing it up to its description: ALTER TABLE sum_wifi_day_101 ADD COLUMN IF NOT EXISTS tup_gw VARCHAR(64) NOT NULL DEFAULT ''
schema=ss7_summary_it bean=dailyWifiSessionSummary table sum_wifi_day_101 was made by an earlier version — bringing it up to its description: ALTER TABLE sum_wifi_day_101 ADD COLUMN tup_gw VARCHAR(64) NOT NULL DEFAULT ''
```

then: the column 64 wide; the old row `tup_gw = ''` with its numbers; a = bras-1, b = bras-2 and b with no BRAS → three
rows: `''` 4 | 4 | 3 300 | 825 | 1 236 567 | 12 349 678 | 55 (the old row, its id), bras-1 1 | 1 | 1 800 | …, bras-2 1 | 1 | 600 | …;
`bringUpToDate` on the catalog → no statement; a second first use and a re-drain change nothing.

**With the two PACKAGED jars — `SS_OLDER_APP=<a packaged clean copy of 3829e18> tools/lab/wifi-gw-upgrade-e2e.sh both`**
(each version started by its own `tools/lab/run-lab.sh`; every endpoint 127.0.0.1: PostgreSQL 7752, MySQL 3306, the
listener 7671, the ping's 7692 with no broker; the MySQL password by the NAME of its variable). From the clean copy of
e42b1da: 02:02:44–02:04:34 (+06), **HOLDS on both, 80 checks ok, 0 FAIL, 0 ERROR lines in all six starts**.

| step | PostgreSQL 16 | MySQL 5.7.44 |
|---|---|---|
| 1 · 3829e18's jar, 4 sessions (3 at mirpur-10 — two of their records name bras-2, one bras-1: it does not read them — 1 at uttara-7) | no `tup_gw`; 2 rows per table: `1\|mirpur-10\|TF_min\|other\|3\|3\|4200\|1400.000000\|2000500\|20005000\|70.000000` · `2\|uttara-7\|…\|1\|1\|900\|900.000000\|300\|3000\|0.000000`; 0 ERROR lines | the same |
| 2 · this jar, the same schema | **two** changes said, and only those: `ALTER TABLE sum_wifi_hr_101 ADD COLUMN IF NOT EXISTS tup_gw VARCHAR(64) NOT NULL DEFAULT ''` and the same for `sum_wifi_day_101`; the column `character varying\|64\|NO\|''::character varying\|19`; the older rows EQUAL (every id, every number); 0 rows with `tup_gw <> ''` | `ALTER TABLE sum_wifi_hr_101 ADD COLUMN tup_gw VARCHAR(64) NOT NULL DEFAULT ''` (and day); `varchar\|64\|NO\|\|19`; the rest the same |
| 3 · 5 sessions after: mirpur-10 on bras-1 ×2, bras-2 ×1, one naming none; uttara-7 on bras-2 | per table: mirpur-10 `''` **4\|4\|5100\|1275.000000\|2000700\|20007000\|85.000000 in the older row's id 1**, bras-1 2\|2\|3600\|1800.000000\|…\|60, bras-2 1\|1\|600\|…\|10, uttara-7 `''` (the older row) 1\|1\|900, bras-2 1\|1\|900; 5 rows; sum(sessions) 9 = the cdr rows of group 101; the 101 VOICE tables: 9 sessions in 2 rows (no split) | the same |
| 4 · this jar again | 0 changes said; every row as it was (id, BRAS, numbers); nothing counted twice (9); 0 ERROR lines in all three starts | the same |

The column on a migrated table is the 19th (appended); the service never relies on the order.

### 3.3 · The reds (each a one-text swap; original saved in `~/ss7-work/mutants/` first; restored sha256-equal; the named tests green again)

The unit tests named: `WifiSessionSummaryByBrasTest`, `WifiSessionSummaryTest`, `WifiSessionSummaryBeanTest` (31); with
"IT": the two SS-0007 contract ITs on BOTH engines (4).

| mutant | red |
|---|---|
| M2.1 `tup_gw` left out of `tupleKey()` | 8 — the two BRAS MERGE: unit `ByBras.two_sessions…on_two_bras:40` (1 row for 2), `:61`, `:93`, the token pin `:104`; IT both engines `two_sessions…reread:481` (1 for 2), `…made_by_3829e18…:505` (1 for 3) |
| M2.2 the builder reading another column (`IncomingRoute`) | 10 — unit `:82` `site-mirpur-10` for the 64 b's, `:58`, `:71` `site-mirpur-10` for `bras-7`, `:93`, `:50`, `:40`; IT both engines `:505` (2 for 3), `:481` (1 for 2) |
| M2.3 the column with no default (`varcharRequired`) | 4 — unit `:110` (default null), `:120`; IT both engines **the first use REFUSES**: `provisionSmall` → `IllegalStateException: table sum_wifi_day_101 has no column tup_gw, and that column has no default: it cannot be added to a table that holds rows. Is sum_wifi_day_101 this service's table?` (§7 F8: this mutant's first run exposed my IT's own construction; fixed, re-run) |
| M2.4 the reload (`mapRow`) not reading `tup_gw` | 2 — IT both engines `two_sessions…reread:478`: `IllegalStateException: duplicate existing row while populating cache for sum_wifi_day_101`; the unit tests stay green under it (only the database reloads) |
| M2.5 the cut to 64 dropped | 1 — unit `a_bras_id_is_cut…:81` (2 rows for 1) |
| M2.6 `cloneWithFakeId` losing `tup_gw` (every fresh INSERT under `''`) | 9 — unit `:82`, `:62`, `:95`, `:50`, `:41`; IT both engines `:506`, `:478` |
| M2.7 `insertValues` without it | 2 — unit `:97`; `WifiSessionSummaryTest.sql_fragments…:138` (17 values for 18 columns) |
| M2.8 the spec without the column | 3 — unit `WifiSessionSummaryBeanTest…:215`, `ByBras :119` (no ALTER), `:106` (no such column) |

The brief's three named mutants are M2.1, M2.2 and M2.3. After the item, in the worktree: unit 399 / 0, ITs 86 / 0 / 0 / 3,
`wifi-gw-upgrade-e2e.sh both` 80 / 0, `wifi-beans-e2e.sh both` 86 / 0.

## 4 · Item 3 — the record of the change (e42b1da)

| where | what it says now |
|---|---|
| `docs/ad-as-call/postgres-ad-profile.md` §4 "after an upgrade" | the WiFi session tables get `tup_gw` (`VARCHAR(64) NOT NULL DEFAULT ''`) at the first use — **the rows summarised before keep `tup_gw = ''`** |
| … §4a (new) "The WiFi session sums split by BRAS" | the key (`InMgwId`, cut to 64 as `sum_wifi_dst_*`'s); the rows summarised before keep `''` (one WARN line per table, the ALTER; history not rebuilt; the second start changes nothing); the day and the hour of the upgrade split between `''` and the BRAS rows (a report per site sums over `tup_gw`); the records of today (the WiFi design's G14 — read from the design, not on any box); **the D1 replay: if the owner orders it after the central gatekeeper, it fills the split for the replayed records — each summed under the BRAS its blob names in `InMgwId`, `''` when it names none**; nothing summed before is re-summed; no index on `tup_gw`; the proof |
| … §7 the lab table | `tools/lab/wifi-gw-upgrade-e2e.sh` |
| `summarybeans/wifi/package-info.java`, `README.md` | the BRAS among the session keys; the `''` of the older rows |
| the profiles | NOT touched — byte-identical to 3829e18's (§7 F7) |

## 5 · The suites, from a clean copy of e42b1da

`git archive e42b1da` into `~/ss7-work/clean-e42b1da/`; the same private repository (still EMPTY), offline; `mvn -o verify`
(the jar packaged, every IT class); my PostgreSQL 16 on 127.0.0.1:7752, the local MySQL 5.7.44 on 127.0.0.1:3306; no Kafka.
Then the two stories from the same copy, its own packaged jar; the older jar for the upgrade = a clean copy of 3829e18
(`git archive 3829e18`, `mvn -o package -DskipTests`, `~/ss7-work/older-3829e18`).

| | before — clean copy of 98d097c (01:02–01:11) | after — clean copy of e42b1da (01:56–02:04) |
|---|---|---|
| unit (surefire) | 384 · 0 · 0 · 0 | **399 · 0 · 0 · 0** (+7 item 1, +8 item 2) |
| ITs (failsafe), all classes | 82 · 0 · 0 · 3 | **86 · 0 · 0 · 3** |
| PostgreSQL | 47 — `PostgresOutboxConsumerIT` 38, `PostgresTreeIT` 9 | **49** — 40 (+2), 9 |
| MySQL | 31 — `OutboxConsumerIT` 29, `MySqlTreeIT` 2 | **33** — 31 (+2), 2 |
| engine-neutral | `BuildBakesNoTenantIT` 1; `KafkaListenersIT` 3 skipped (no broker) | the same |
| `tools/lab/wifi-beans-e2e.sh both` (SS-0006's story) | HOLDS, 86 ok, 0 FAIL, 0 ERROR lines | **HOLDS, 86 ok, 0 FAIL, 0 ERROR lines** (02:01:13–02:02:44) — unchanged |
| `tools/lab/wifi-gw-upgrade-e2e.sh both` (new) | — | **HOLDS, 80 ok, 0 FAIL, 0 ERROR lines** (02:02:44–02:04:34) |

**The tree of item 5** (bd237a8 + the four new files under `tools/lab/`; no file under `src/` changed) from a clean copy
(`~/ss7-work/clean-item5`): `mvn -o verify` 02:37:22–02:42:21, BUILD SUCCESS — unit **399 · 0 · 0 · 0**, ITs **86 · 0 · 0 · 3**
(`PostgresOutboxConsumerIT` 40, `PostgresTreeIT` 9, `OutboxConsumerIT` 31, `MySqlTreeIT` 2, `BuildBakesNoTenantIT` 1,
`KafkaListenersIT` 3 skipped); then `wifi-gw-wayback-e2e.sh both` **HOLDS, 96 ok, 0 FAIL** (02:42:21–02:44:35),
`wifi-gw-upgrade-e2e.sh both` **HOLDS, 80 ok, 0 FAIL** (02:44:35–02:47:14), `wifi-beans-e2e.sh both` **HOLDS, 86 ok, 0 FAIL**
(02:47:14–02:49:11).

Every red of this round is a mutant's, a pin of the old shape seen before its update (§3.1), or my own test's (§7 F8) —
none a defect of the service, none flaky: nothing failed twice differently, so nothing was run five times.

## 6 · What the architect's cut needs

- **The artifact:** the jar of e42b1da (88da797's code; item 3 is pages and a javadoc). No pom change, no new dependency;
  built offline on the private repository with the read-only tails. The bed install waits for the WiFi architect's go
  (his §8 step 7 or later), as the brief says.
- **No configuration change:** the profiles (`profile-radius2.yml`, `profile-lab.yml`) are byte-identical to 3829e18's;
  the beans' names, kinds and tables are as before. Nothing for devops to render.
- **No new right:** the service owns its tables; on PostgreSQL the `ALTER` needs the table's owner — `summary_service`,
  which made it. The bed is PostgreSQL (`profile-radius2.yml` `store.kind: postgresql`): `ADD COLUMN … NOT NULL DEFAULT ''`
  with a constant default is a catalog change on PostgreSQL 11 and later (no table rewrite) — the bed's PostgreSQL version
  is not read by this round.
- **What devops will see at the first start, per tier schema:** two WARN lines, `table sum_wifi_day_101` / `sum_wifi_hr_101`
  `was made by an earlier version — bringing it up to its description: ALTER TABLE … ADD COLUMN IF NOT EXISTS tup_gw
  VARCHAR(64) NOT NULL DEFAULT ''`. Expected, not a fault; the next start says none.
- **The way back** (the `*.jar.prev` road): **it WEDGES once BRAS rows exist** — probed on PostgreSQL (`~/ss7-work/probe-wayback.sh`, my lab,
  removed): 3829e18's jar sums one session at mirpur-10; this jar upgrades the tables and sums one on bras-2 (the bucket
  now holds `1||mirpur-10|1 ; 2|bras-2|mirpur-10|1`); 3829e18's jar again with one more session → **10 of 12 beans catch up;
  both WiFi session beans: `drain failed — offset STUCK … java.lang.IllegalStateException: duplicate existing row while
  populating cache for sum_wifi_hr_101`** (`SummaryCache.populateExisting:44`; the older jar reloads without `tup_gw`, so the
  two rows are one key to it), bookmarks at 2 with the outbox at 3, retried at every poll. Not quarantined, nothing lost:
  the rows wait in the outbox (the reaper keeps them while a bean has not passed them) — and **forward again, this jar on
  the same schema: 12 of 12 caught up, the stuck session drained into its bras-2 row (`2|bras-2|mirpur-10|2`), 0 changes
  said, 0 ERROR lines**. On the bed the records already name a BRAS (F6), so the first session after the install makes
  such a bucket — the `*.jar.prev` way back is clean only before it (§7 F11). **Rehearsed since item 5:** stop the new jar,
  `tools/lab/wifi-gw-wayback.sh` per tier schema (the sign: READY), start 3829e18's jar (§9.2).

## 7 · Findings — and what the brief got wrong

| # | finding | proposal |
|---|---|---|
| F1 | "`CdrBlobEntry` fills it": nothing in `CdrBlobEntry` fills a field — Jackson's record decode fills the new component by name, case-insensitively, through it, as every other column. `CdrBlobEntry` is unchanged | none |
| F2 | the item-2 list ("the spec column, INSERT_COLUMNS, the field, tupleKey(), the builder") leaves out `mapRow` (the reload, by name), `cloneWithFakeId` (every fresh INSERT is a clone) and `insertValues`. All three carry `tup_gw`; each broken once: M2.4 (the reload collides — "duplicate existing row"), M2.6 (new rows written under `''`), M2.7 | none — said so the list is exact |
| F3 | "Indexes are NOT added to an existing table (only columns)": true of `bringUpToDate`, and of MySQL (its indexes live inside the `CREATE TABLE IF NOT EXISTS`). On PostgreSQL the table's `CREATE INDEX IF NOT EXISTS` statements run at EVERY first use (`OutboxReader.ensureProvisioned` runs `tableDdl`, which on PostgreSQL is the table and one `CREATE INDEX IF NOT EXISTS` per index), so an index ADDED to a spec WOULD reach an existing PostgreSQL table — never a MySQL one. No index is added this round, so nothing differs between the engines | before any index on `tup_gw`: decide whether the engines may differ, or add it on MySQL too by an explicit step |
| F4 | "`''` when absent" is this service's reading: billing-core writes NOTHING when the meta has no `gw` (`cdr.InMgwId` stays NULL — `billing-tables.sql:107` nullable; the blob leaves the key out) | a report joining the raw `cdr` should `coalesce(InMgwId, '')` to meet the summary |
| F5 | "the whole suite unchanged" holds for item 1 (no test changed). Item 2 necessarily changed three assertions that pinned the OLD shape exactly (the column list, the insert values text, the 10-token key): with the change they went red and nothing else did; each now names `tup_gw` at its place, nothing else | none |
| F6 | **the day and the hour of the upgrade are split** between the `''` row (summed before) and the BRAS rows (after) of the same site and bucket — and the records of today already name a BRAS (the WiFi design's G14), so on the bed this happens at once. A report per site sums over `tup_gw` and stays whole; a report per BRAS sees that day's earlier sessions under `''` (the owner's words: "an empty key for the old rows is fine") | none, or install at a day's start to keep the split to one hour |
| F7 | the profiles' comment lines (`profile-radius2.yml` :66-69, `profile-lab.yml` :58) list the session keys without the BRAS — left so the profiles stay byte-identical to 3829e18's (nothing to render on the bed) | one comment line each, when a profile changes anyway |
| F8 | my own reds on the way: (a) M2.3's first run was red in my NEW IT's own construction of the 3829e18 table (it deleted the column's exact text from the new DDL; the mutant changed that text, so the "old" table was made with the column) — the IT now removes the column by pattern, whatever its default, and the re-run was red at the first use; (b) the mutant helper's first restore kept the old file time (`copy2`) and the compiler skipped the file — the first green check after M1.1 ran the mutant's class; fixed (content + a fresh time), every green re-run after it compiled the restored file | none |
| F9 | "the D1 replay, if the owner orders it after the central gatekeeper, fills the split": the summary sums a replayed record under the BRAS its blob names in `InMgwId` — at any time; whether that is the SESSION's BRAS is the replayed record's meta (G14: today the process's `gw`), not this service's timing | none |
| F10 | the column is LAST on a migrated table (ordinal 19) and after `tup_inpartnerid` on a new one | none: everything is by name |
| F11 | **the way back** — 3829e18's jar started again on a table this version upgraded wedges the two WiFi session beans (offset STUCK, "duplicate existing row while populating cache") as soon as one bucket holds two rows of one OLD key — the `''` row and a BRAS row; probed on PostgreSQL (§6). The other ten beans drain; nothing is lost: this jar started again drains the stuck rows (probed: 12 of 12, 0 ERROR lines) | DONE in item 5 (§9): the one-off step is `tools/lab/wifi-gw-wayback.sh`, proven on both engines; devops's order in §9.2. The split of the collapsed rows is given up, every total kept |

## 8 · The rules, kept

Java 21 (Corretto 21.0.9); Maven 3.9.10 OFFLINE with `-Dmaven.repo.local=$HOME/.m2/ss7-repo
-Dmaven.repo.local.tail=$HOME/.m2/arch-ss-review-repo,$HOME/.m2/summary-service-repo,$HOME/.m2/repository` for every build
(and `MAVEN_ARGS` the same for the lab scripts) — the head repository stayed EMPTY; nothing written to `~/.m2/repository`,
`arch-bed-repo`, `session-base-repo`, `seed-sw33-repo`, `sa-a-repo`; nothing missing offline. The labs: my own
`ss7-pg16-lab` (postgres:16, 127.0.0.1:7752, trust — no password exists), removed at the end; the local MySQL 5.7.44 at
127.0.0.1:3306 as root with the password by the NAME of its variable — on no command line, in no file of the repository,
in no log of mine (checked at the end), not in this note; its databases of this round (`ss7_summary_it`, and the stories'
`lab_wifibeans`, `lab_wifigw`, `lab_wifiwayback`, dropped by their own scripts; item 5's hand-made `ss7_sqltest` and PostgreSQL `sqltest`)
dropped at the end; the probe's PostgreSQL schema `lab_wayback` too; the owner's `summary_it` not touched; the host PostgreSQL on 127.0.0.1:7643 (not mine)
never named. No Kafka started (`KafkaListenersIT` self-skipped; the shared `onlab-kafka` on 7592 never touched); nothing
dialled off this box. The jar was never started bare: every start through `tools/lab/run-lab.sh btcl/lab` (the endpoints
printed first, all loopback). `SummaryEngine` / `OutboxReader` / `TableDdl` untouched. Every commit after its run; one per
item; only `wifi-gw-key` pushed. `date` before every time written. The worktree stays for your merge; the main checkout,
`postgres-ad-call` and `master` not touched; the `wifi` and `telcobright-billing-core` repositories read by `git show`
only. Mutant originals in `~/ss7-work/mutants/`; the notes in `~/ss7-work/notes.md`; the logs in `~/ss7-work/logs/`.

## 9 · Item 5 — the way back after sessions with a BRAS exist (the architect's ask after F11)

### 9.1 · As built

| where | what |
|---|---|
| `tools/lab/wifi-gw-wayback.sh postgresql\|mysql <tier schema>` | devops's tool, ONE tier schema per run, AFTER the new jar is stopped and BEFORE 3829e18's jar starts. It names its target first (`== the way back on postgresql <host>:<port>/<db>, tier schema <s>, as <role> ==`); reads, read only, whether the schema holds the two tables and their `tup_gw` (none: "nothing to do", exit 0; tables 3829e18 made and never upgraded: "nothing to do", exit 0; anything else: REFUSED, exit 3, nothing done); runs the engine's SQL file; prints per table `merged`, `deleted`, `bras cleared`; ends with the sign — **READY** (exit 0) or **NOT READY** (exit 1: something wrote a BRAS row after the collapse — the new jar still running). Connection: `WAYBACK_HOST` / `WAYBACK_PORT` / `WAYBACK_DB` / `WAYBACK_USER` (defaults 127.0.0.1, 5432 or 3306, `routesphere`, `summary_service` or root); a password by the NAME of its variable, `WAYBACK_PASSWORD_VAR=<NAME>` (psql reads it as `PGPASSWORD`, mysql as `MYSQL_PWD`; on no command line, in no output) |
| `tools/lab/wifi-gw-wayback-postgres.sql` / `-mysql.sql` | per table (`sum_wifi_day_101`, then `sum_wifi_hr_101`), ONE transaction: (1) the groups of rows that share the OLD key (`tup_switchid, tup_inpartnerid, tup_site, tup_zone, tup_granttier, tup_category, tup_packagecode, tup_customercurrency, tup_causefamily, tup_starttime`) are counted and each summed into its LOWEST id — `sessions, sessions_connected, total_duration_sec, bytes_up, bytes_dn, customercost` — with `avg_session_sec` derived again as the bean derives it (`CASE WHEN connected = 0 THEN 0 ELSE round(total carried to 18 places / connected, 6) END`); (2) the other rows of each group deleted; (3) `tup_gw = ''` on every row — the column stays, so the new jar, started again, finds its table as described. Then, read only, the sign's check per table: old keys held by more than one row, rows with a BRAS. Nothing but the two tables is read or written. MySQL compares the key's texts byte for byte (`CAST(… AS BINARY)`): the tables' collation is case-insensitive, and the jar's keys are not |
| the average | equal to the bean's `BigDecimal` HALF_UP at six places on both engines — 13 hard pairs against Java, exact ties among them (1/2 000 000 → 0.000001, 3/2 000 000 → 0.000002, 2 500 001/2 000 000 → 1.250001), all equal |
| `tools/lab/wifi-gw-wayback-e2e.sh [pg\|mysql\|both]` | the proof with the two PACKAGED jars (§9.3) |

On hand-made tables first (both engines, MySQL partitioned as the service makes it): merged 3, deleted 4, cleared 3, READY;
the second run 0, 0, 0, READY; a site that differs only in case (`Mirpur-10`) stays a key of its own on both engines.

### 9.2 · The way back, as devops reads it

1. **Stop the new jar** (its unit) and see its process gone — nothing may write the two tables while the way back runs.
2. **Per tier schema the service serves** (the root and every reseller schema: the `schema=… is served` lines of its last
   start), on the box of the switch database, run
   `WAYBACK_PORT=<the database's port> WAYBACK_PASSWORD_VAR=<the NAME of the variable that holds summary_service's password> tools/lab/wifi-gw-wayback.sh postgresql <schema>`
   (the role is `summary_service`, the tables' owner; `WAYBACK_HOST` / `WAYBACK_DB` when they are not 127.0.0.1 /
   `routesphere`; the script reads `wifi-gw-wayback-postgres.sql` from its own directory — the two travel together). It prints, per table, `merged <groups> / deleted <rows> / bras cleared <rows>`, then
   **`READY: sum_wifi_day_101 and sum_wifi_hr_101 of <schema> hold no old key twice and no BRAS — 3829e18's jar may start on it`**
   (exit 0). Run it again if in doubt: `0 / 0 / 0` and READY. `NOT READY` (exit 1) = a BRAS row was written after the
   collapse: stop the new jar for real, run it again. "nothing to do" (exit 0) = a schema without the WiFi tables, or whose
   tables 3829e18 made and the new jar never upgraded.
3. **Start 3829e18's jar** (the `*.jar.prev`).
4. **The sign that it runs:** `is served: 12 of 12` in its log; no `duplicate existing row` line, no `drain failed — offset
   STUCK` for `dailyWifiSessionSummary` / `hourlyWifiSessionSummary`; within a poll or two the two beans' bookmarks reach the
   outbox head: `select bean_name, last_offset from summary_offset where bean_name like '%WifiSession%'` against
   `select max(id) from summary_affected where entity_type = 'cdr'`.

What it costs: the BRAS split of the rows the new jar summed — they collapse into the old key, every total kept, the
average the bean's. Forward again later: the new jar starts on the collapsed tables with no change said and no ERROR line;
the collapsed rows stay under `''`, and new sessions split by BRAS from then on.

### 9.3 · The proof — `SS_OLDER_APP=<3829e18 packaged> tools/lab/wifi-gw-wayback-e2e.sh both`

From the clean copy of the item-5 tree, 02:42:21–02:44:35 (+06): **HOLDS on both, 96 checks ok, 0 FAIL**. Every endpoint
127.0.0.1 (PostgreSQL 7752, MySQL 3306, the listener 7671, no broker); the way back run as devops runs it — the host's
`psql` / `mysql` on 127.0.0.1, as `summary_service` on PostgreSQL. In the worktree, before the mutants: the same, 96 / 0.

| step | PostgreSQL 16 and MySQL 5.7.44 (the same on both) |
|---|---|
| 1 · 3829e18's jar, 4 sessions | mirpur-10 and uttara-7 get their `''` rows |
| 2 · this jar: the upgrade (2 ALTERs said), then 8 sessions in the SAME hour and day | 8 rows per table: mirpur-10 `''` (+ the one naming no BRAS) / bras-1 / bras-2; uttara-7 `''` / bras-2; banani-11 bras-2 then bras-1 (the group's lowest id names a BRAS); gulshan-2 bras-1 alone; 3 old keys held twice or more. Read: the totals per old key summed across `tup_gw`, and a digest of each of the 14 OTHER tables of the schema |
| 3 · the way back, then again | per table **merged 3, deleted 4, bras cleared 2, READY**, exit 0; again **0, 0, 0, READY**. 4 rows per table, one per old key; **the totals per old key = those read in 2**; mirpur-10 `7\|7\|9300\|1328.571429\|4001200\|40012000\|155` (into the older row), banani-11 `2\|2\|1800\|900.000000\|500\|5000\|30` (its bras-2 row kept and cleared); every average the bean's; no BRAS; the 14 other tables digest-equal |
| 4 · 3829e18's jar again, 2 more sessions | its two WiFi session beans RUN: offsets **12 → 14** (the outbox head), **0 `duplicate existing row`**, 0 ERROR lines; mirpur-10 `8\|8\|11100\|1387.500000\|…\|185`, banani-11 `3\|3\|2400\|800.000000\|…\|40` — merged into the collapsed rows; 4 rows; sum(sessions) 14 = the cdr rows |
| 5 · forward again: this jar | **0 changes said**, 0 ERROR lines; a session on bras-2 opens its own row beside the collapsed `''` row (5 rows); sum(sessions) 15 = the cdr rows |

### 9.4 · The reds (each a text swap in both tables' blocks of ONE engine's file; original saved first; restored sha256-equal)

| mutant | red |
|---|---|
| M5.1 the delete step removed (`AND false` on the DELETE) — PostgreSQL, then MySQL | 34 FAIL each: the way back says **NOT READY** (3 old keys twice), exit 1, `deleted 0`; the totals double; **3829e18's jar sticks** — 10 of 12 beans, 2 `duplicate existing row`, the session beans' offsets stay 12; and forward again the NEW jar sticks too (every row now `''`, two of one key) |
| M5.2 the average not derived again — PostgreSQL, then MySQL | 6 FAIL each: after the way back 3 rows per table off the bean's average (mirpur-10 `1275.000000` for `1328.571429`, banani-11 `1200` for `900`, uttara-7 `900` for `600`); after 3829e18's sessions uttara-7 is still off. READY is said (by design: the sign checks what 3829e18 needs to run — no old key twice, no BRAS — and the story checks the averages) |

The four red runs, then the green ones from the clean copy (§9.3): the two SQL files back to `34cca4c3…` (PostgreSQL) and
`43ddb96c…` (MySQL).

### 9.5 · For report readers (F6)

A report over the day (or the hour) of the upgrade, or of a forward-again, sums across `tup_gw`: the sessions summed before
sit in the `''` row and the later ones in the BRAS rows of the same site and bucket. Per site:
`select tup_site, sum(sessions), sum(sessions_connected), sum(total_duration_sec), round(sum(total_duration_sec)::numeric / nullif(sum(sessions_connected), 0), 6) from sum_wifi_day_101 where tup_starttime = '<day>' group by tup_site`
(MySQL: `cast(sum(total_duration_sec) as decimal(24,6))`). A report per BRAS reads only what was summed with a BRAS: the
earlier sessions of that day are under `''`, and after a way back the buckets it collapsed are `''` whole.
