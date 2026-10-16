from: SS        to: ARCH        kind: done        number: 0006
date: 2026-10-16 (the times below are +06)
branch: wifi-beans (from postgres-ad-call 7d63ae6 + the brief 48c7d7b)
head: 366f795 (item 5); this note and one doc row are the commit after it — pages only
subject: SS-0006 — the WiFi summary beans: the session per hour/day (sum_wifi_hr_101 / sum_wifi_day_101 — keys by column, the derived average, ValidFlag 0 summed) and the volume per destination (sum_wifi_dst_hr / sum_wifi_dst_day — the wifi_flow entity, no money, no devices); the blob's Cdr reads the WiFi columns; the kinds in the bootstrap and the four beans in the lab and the bed profiles; the e2e story on both engines with the owner's two reports; every rule broken once and seen red; the suites from a clean copy

# SS-0006 · done — items 0–5

## 0 · What to relay

| | |
|---|---|
| the branch | `wifi-beans`, pushed after each item: **212a876** item 1 · **c599d1b** item 2 · **3196d27** item 3 · **5126d34** item 4 · **366f795** item 5 · the commit after = this note + the profile page's §7 row. `postgres-ad-call` and `master` not touched; the merge is yours (`--no-ff` into `postgres-ad-call`) |
| item 1 | DONE (§1). `Cdr` reads the WiFi columns of a 101 row + `PackageAmount` / `IdPackageAccount` (SS-0005's F2 closed) + `HangupCause`; the three old fixtures pinned field for field at 7d63ae6 |
| item 2 | DONE (§2). `summarybeans/wifi`: `sum_wifi_hr_101` / `sum_wifi_day_101` — keys by COLUMN (rule a), `avg_session_sec` DERIVED (ruling 4), ValidFlag 0 summed (rule b), the cause family from the gateway's real words (§2.2) |
| item 3 | DONE (§3). `sum_wifi_dst_hr` / `sum_wifi_dst_day` on the `wifi_flow` entity; the window's start on the root's zone (ruling 7); `tup_gw` for the switch (ruling 5); no money, no `distinct_macs` (ruling 8); a NEW entity starts at 0 beside `cdr` bookmarks — pinned on the fakes and on both engines |
| item 4 | DONE (§4). `summary.beans.<name>.kind: wifi-session \| wifi-dst`; absent = the call bean exactly as today; unknown = refused in words; four beans in `profile-lab.yml` and `profile-radius2.yml` (twelve beans); the kinds named in `README.md` and `postgres-ad-profile.md` |
| item 5 | DONE (§5). `tools/lab/wifi-beans-e2e.sh both` with the packaged jar HOLDS on PostgreSQL 16 and MySQL 5.7: 86 checks ok, 0 ERROR lines; the owner's two reports equal to the raw rows |
| the engine | `SummaryEngine` and `OutboxReader` UNTOUCHED (`git diff 48c7d7b..366f795 --stat` shows no file under `engine/` or `outbox/`). The bootstrap changed as item 4 names it (one delegation + the cause's words in its ERROR line) |
| the suites | from a clean copy of 366f795 (`git archive`, a fresh private repository chained read-only, offline; the two labs up, no Kafka): unit **384 / 0 / 0 / 0**, ITs **82 / 0 / 0 / 3** — PostgreSQL 47 / 0, MySQL 31 / 0, the 3 = `KafkaListenersIT`, no broker in my lab (§6) |
| to decide | §9: F1 `BALANCE_EXHAUSTED` is `balance`, though the minutes meter's cut arrives under that word; F2 `hangupCause` added to `Cdr` beyond the brief's list; F3 the two lab scripts' bean count moved to 12 without a re-run; F4 the MySQL side of `wifi_flow` is the lab's only; F5 no `config/README.md` exists |

## 1 · Item 1 — the blob reads the new columns (212a876)

| where | what |
|---|---|
| `summarybeans/call/model/Cdr` | gains `xAmount, yAmount, inTrunkAdditionalInfo, outTrunkAdditionalInfo, category, validFlag, terminatingCallingNumber, releaseCauseSystem, releaseDirection, packageAmount, idPackageAccount, hangupCause` (`zAmount`, `originatingIp`, `duration1` were there). `hangupCause` is not in the brief's list; ruling 6 (`tup_causefamily` from the cdr's `HangupCause`) needs it (§9 F2). Nothing else of the mapper changes (`CdrBlobMapper` untouched) |
| `testkit/CdrTestSupport` | the two `new Cdr(…)` calls take the twelve new components as null |
| `testkit/WifiSessionTestSupport` | `measuredSession(start)`: the standing session as BC-0008 writes it (billing-core's lab story: `mirpur-10` / `dhaka-north` / paid 2 / 1 234 567 up, 12 345 678 down / 5 000 000 000 budget / 1 800 granted / MAC `aa:bb:cc:dd:ee:01` / `10.44.0.7` / cause 4 by 1 / measured / expiry 2026-10-11) + a setter per column; `session(start)` unchanged (the BC-0007 shape: the golden); `site(name)` sets the route AND the column |
| `CdrBlobGoldenTest` (3) | the voice, the ad and the BC-0007 session fixture field for field as they decoded at 7d63ae6 — **run green on a clean copy of 48c7d7b first** (`tests="3" failures="0"`), then on the new model |
| `CdrBlobWifiColumnsTest` (3) | a 101 blob of BC-0008's shape decodes every column; an older blob → nulls, no error; case-insensitive, 5 000 000 000 round-trips |

Reds on the way: `CdrBlobWifiColumnsTest.a_101_blob…:45` `expected <mirpur-10> but was <site-mirpur-10>` — my fixture wrote the BC-0007 route into the new column; the fixture got its own site column (the model was right).
Mutant: `yAmount` renamed on the wire (`@JsonProperty("YAmnt")`) → `a_101_blob_of_bc_0008s_shape_decodes_every_wifi_column:43` and `the_columns_are_read_case_insensitively…:92` red (NullPointer on the null amount); restored; green. Unit suite after the item: 326 / 0.

## 2 · Item 2 — `sum_wifi_hr_101` / `sum_wifi_day_101` (c599d1b)

### 2.1 · As built

| where | what |
|---|---|
| `summarybeans/wifi/model/WifiSessionSummary` | the entity: keys `tup_switchid, tup_inpartnerid, tup_site, tup_zone, tup_granttier, tup_category, tup_packagecode, tup_customercurrency, tup_causefamily, tup_starttime`; sums `sessions, sessions_connected, total_duration_sec, bytes_up, bytes_dn, customercost`; derived `avg_session_sec` = `total_duration_sec / sessions_connected` (0 when none, six places half up), recomputed in `merge` AND `multiply`, never added. Its javadoc says ValidFlag 0 rows are summed |
| `…/internal/WifiSessionSummaryBean` | entity `cdr`; the call bean's filter copied with citation (`CallSummaryBean.buildBatch:135-156`): the customer leg of group 101, a start time, malformed entries skipped and said; table `sum_wifi_<token>_101`; no tier needed (one pair per tier schema, as the voice tables) |
| `…/internal/WifiSessionSummaryBuilder` | every key from a COLUMN (rule a; `WifiColumns.java:82-104`): `tup_site` = `InTrunkAdditionalInfo`, `tup_zone` = `OutTrunkAdditionalInfo`, `tup_granttier` = `MatchedPrefixY`, `tup_category` = `Category`, `tup_packagecode` = `MatchedPrefixCustomer`; the unit and the cost behind the voice's `ChargingStatus` early return, as the 101 call bean has it (`CallSummaryBuilder.stampWifiSession:152-162`) — a 0-second session has no unit and no cost; `sessions_connected` = `DurationSec > 0`; bytes = `XAmount` / `YAmount` whole; ValidFlag never read (rule b); key texts cut to their widths (site/zone 64 as the ad bean's, grant tier 16, package code 64, unit 16, family 16) |
| `…/internal/WifiSessionSummaryGenerator` | `I = RatedCall` (the call category's own input), `T = WifiSessionSummary` |
| `…/internal/WifiCauseFamily` | §2.2 |
| `…/internal/SumWifiSessionDdl` | `SummaryTableSpec`: bigint sums, DECIMAL(18,6) cost and average, PK `(id, tup_starttime)`, `ix_starttime`, `ix_site_starttime`, `partitionedByDayOn(tup_starttime)`, `keptUpToDate()` — the ad table's pattern (`SumAdDdl`) |
| `summarybeans/wifi/WifiSummaries` | `sessionForWindow(name, window, context)` — `CallSummaries.forWindow`'s pattern; no catalog class per window (ruling 1) |
| tests | `WifiSessionSummaryBeanTest` 16, `WifiSessionSummaryTest` 7, `WifiCauseFamilyTest` 4; `OutboxConsumerContract.a_replayed_hour_of_wifi_sessions_lands_the_known_rows_in_the_wifi_session_tables_the_service_made_on_this_engine` on both engines (the catalog's columns = the description, nothing to bring up to date; the known hour → the four rows; the 101 voice table counts the same six; re-drain a no-op) |

The known hour (`WifiBeansTestSupport`): A 09:05 paid/mirpur-10 1 800 s 30 TF_min · B 09:20 the same key 600 s 10 TF_min · E 09:40 the same key 300 s 5 TF_min **ValidFlag 0** · C 09:30 in money 900 s 3.00 BDT MEDIA_TIMEOUT · D 09:50 0 s · F 09:55 free at uttara-7 900 s VOLUME_EXHAUSTED → the rows (sessions | connected | total | avg | up | dn | cost): mirpur-10/TF_min/other **3 | 3 | 2700 | 900 | 1 235 567 | 12 347 678 | 45**; mirpur-10/BDT/idle 1 | 1 | 900 | 900 | 500 | 7 000 | 3.00; mirpur-10/''/other 1 | 0 | 0 | 0 | 0 | 0 | 0; uttara-7/TF_min/volume 1 | 1 | 900 | 900 | 100 | 200 | 0.

### 2.2 · The cause family — the gateway's words as read (`wifi` origin/master, 2026-10-16)

The record's `hangupCause` = `WifiCause.of(<the base's cause>)` (`wifi-sphere/src/main/java/com/telcobright/wifisphere/flow/api/WifiSessionFlow.java:656`); the base's cause is the machine's end word or `NORMAL_CLEARING` (`routesphere-seed/seed-sessionflow/…/SessionFlowSteps.java:331-334`); `WifiCause.of` (`flow/api/WifiCause.java:42-59`) turns the live machine's words into the record's and passes an unknown word through as it is. (The brief's path `internal/session/v2/EndFacts.java` is `flow/internal/EndFacts.java` on master; there is no `SessionCause` in the `wifi` repo — it is the seed's `seed-sessionflow/…/api/SessionCause.java`.)

| family | the record's words | source |
|---|---|---|
| `idle` | `MEDIA_TIMEOUT` | `WifiCause.java:32`; the meter's / the plane's idle (`internal/session/v2/UsageMeter.java:16,101,170`); RADIUS Idle-Timeout `internal/radius/RadiusAttr.java:85` |
| `time` | `PACKAGE_EXPIRED`, `MAX_DURATION_REACHED` | `WifiCause.java:25`; `SessionCause.java:37` |
| `volume` | `VOLUME_EXHAUSTED` | `WifiCause.java:33`; `UsageMeter.java:110`, `PlaneCut.java:11` |
| `balance` | `BALANCE_EXHAUSTED`, `INSUFFICIENT_BALANCE` | `SessionCause.java:36,26`. **The meter's `minutes` cut (`UsageMeter.java:141`) and the plane's `time-plane` cut (`PlaneCut.java:11`) reach the record as `BALANCE_EXHAUSTED` (`WifiCause.java:49`)** — so a minutes package used up is `balance`, not `time`; `ReleaseCauseSystem` says Session-Timeout (5) for both (`RadiusAttr.java:82,90`). §9 F1 |
| `deauth` | `DEAUTH` and every word that starts with `deauth` (`deauth:user`, `deauth:portal`, `deauth:flow:<cause>`), `COA`, `SUPERSEDED` | `WifiCause.java:34,45,35,37`; `flow/internal/EndFacts.java:37`; `RadiusAttr.java:80,92-93` |
| `shutdown` | `SYSTEM_SHUTDOWN`, `LOST_AT_RESTART` | `SessionCause.java:48,47`; the Stop says `restart` (`EndFacts.java:36`) |
| `other` | `NORMAL_CLEARING` (the subscriber's own sign-out), `GATE_BUSY`, `GATE_FAILED`, `NO_AUTH`, `SILENT`, `ROGUE_ROW`, the refusals before admission (`NO_MAC` … `NO_PURCHASE_NAMED`), `INTERNAL_ERROR`, none, any unknown word | `SessionFlowSteps.java:333`; `WifiCause.java:15-39` |

### 2.3 · The reds (each a one-text swap, the named test run, the file put back from `~/ss6-work/mutants/<file>.orig`, green again)

| the break | red |
|---|---|
| the average ADDED in `merge` instead of derived | 5 of 16 + the entity test: `a_replayed_hour…:83` avg `2700.000000` for `900.000000`, `a_second_session…` |
| a 0-second session counted as connected | 2: `…zero_seconds…:132` `1 \| 1 \| 0` for `1 \| 0 \| 0` |
| the 101 filter dropped (every group kept) | 1: `a_view_and_a_voice_call…:176` 3 for 1 |
| `tup_site` from the route, not the column | 10: `site-mirpur-10` for `mirpur-10` / `''` |
| ValidFlag 0 rows dropped | 4: `a_record_whose_valid_flag_is_zero…:155` 1 session for 2 |
| the unit stamped for an uncharged session | 3: `…zero_seconds…:133` `TF_min` for `''` (3 rows for 4) |
| `BALANCE_EXHAUSTED` filed under `time` | 1: `WifiCauseFamilyTest.every_word…:33` |

After the item: unit 353 / 0; `PostgresOutboxConsumerIT` 36 / 0, `OutboxConsumerIT` 28 / 0.

## 3 · Item 3 — `sum_wifi_dst_hr` / `sum_wifi_dst_day` (3196d27)

### 3.1 · As built

| where | what |
|---|---|
| `summarybeans/wifi/model/WifiFlowRow` | the blob row — the `wifi_flow_window` columns as `WifiFlowOutboxRow.java:12-14` names them (`window_start, window_sec, gw, zone, site, seg, mac, remote, port, proto, packets_up, packets_dn, bytes_up, bytes_dn, produced_at`), every field boxed (a null is left out of the JSON), unknown keys ignored |
| `…/model/WifiDestinationSummary` | the entity: keys `tup_gw` (where the switch id stands — ruling 5), `tup_site, tup_zone, tup_remote, tup_proto, tup_port, tup_starttime`; sums `bytes_up, bytes_dn, packets_up, packets_dn, flows`; no money, no devices (ruling 8) |
| `…/internal/WifiFlowBlob` | the small reader: the decoded `data` is ONE JSON array of rows (`WifiFlowOutbox.Decode`'s inverse, `WifiFlowOutbox.java:38-40`), by the call category's lenient mapper; not an array = a poison row; the window's start as an instant (`2026-10-16T09:41:00Z`; an offset form taken too) |
| `…/internal/WifiDestinationSummaryBuilder` / `Generator` | the window's start put on the ROOT tenant's zone (`DdlPartitions.tenantZone`: `summary.zone`, Asia/Dhaka by default — ruling 7), then cut to the bucket; the sums as the row carries them, `flows` 1; the MAC not in the key and not counted; the segment, the window length, produced-at read past |
| `…/internal/WifiDestinationSummaryBean` | entity `wifi_flow` (= `WifiFlowOutbox.EntityType`); table `sum_wifi_dst_<token>`; a row with no readable `window_start` skipped and said once per batch (the ad bean's rule) |
| `…/internal/SumWifiDestinationDdl` | bigint sums, PK `(id, tup_starttime)`, `ix_starttime`, `ix_site_remote_starttime`, partitioned by day on MySQL, `keptUpToDate`; `tup_remote` VARCHAR(45) |
| `WifiSummaries.destinationForWindow` | the zone from config, or given (tests) |
| tests | `WifiDestinationSummaryBeanTest` 17, `WifiDestinationSummaryTest` 5; `OutboxConsumerContract.a_replayed_hour_of_flow_windows_lands_the_known_destination_rows_and_a_wifi_flow_bean_starts_at_0_beside_cdr_bookmarks` (both engines); `PostgresOutboxConsumerIT.flow_rows_in_another_tenants_schema_make_no_row_in_this_tenants_destination_table` |

The known hour (`WifiFlowTestSupport`): W1 09:41Z (r1 mac…01 → 142.250.74.78:443/6 120/340 15 200/410 300; r2 mac…02 → the same 10/20 1 000/2 000; r3 → 1.1.1.1:53/17 5/5 300/700), W2 09:42Z (r4 → 142.250.74.78 30/60 3 000/90 000; r5 uttara-7 → 1.1.1.1 2/2 100/200), W3 09:43Z (r6 mac…02 → 142.250.74.78 1/1 100/100) → three rows under Dhaka's hour 15: mirpur-10 → 142.250.74.78:443/6 **4 | 19 300 | 502 400 | 161 | 421** (two devices, one row); mirpur-10 → 1.1.1.1:53/17 1 | 300 | 700 | 5 | 5; uttara-7 → 1.1.1.1:53/17 1 | 100 | 200 | 2 | 2.

The seeding rule, pinned for the new entity (`OutboxReader.seedBookmarks:188-222`, the engine untouched): a `wifi_flow` bean on a schema with `cdr` bookmarks gets **0** (`{dailyWifiDstSummary=0, hourlyWifiDstSummary=0}`) — every flow row ever written is summed — while a `cdr` bean switched on beside the bookmarked ones gets the head (2); on the fakes and on both real engines (four `wifi_flow` outbox rows beside two `cdr` rows: the dst beans drain exactly the four, the call bean's bookmark stays at 2, nothing dead-lettered).

### 3.2 · The reds

| the break | red |
|---|---|
| the bucket left in UTC (the zone ignored) | 4: `one_flow_row…:69` `2026-10-16T09:00` for `15:00` |
| entity `cdr` instead of `wifi_flow` | 2: the seeding test `:201` `{…=2, …=2}` for `{…=0, …=0}`; `:52` |
| `bytes_dn` stamped from `bytes_up` | 7: `:78` `502400` read as `19300` |
| the MAC as the key (in `tup_remote`) | 5: `:77` 4 rows for 3; `:66` |
| a row with no `window_start` counted under 1970 instead of skipped | 2: `:160` 3 for 1; `:173`. (A first try threw where `continue` followed — a compile error, not a red; not counted) |
| the port dropped from the key | 4: `:78` no row for 443/6; `:68` 0 for 443 |

My own red on the way: `PostgresOutboxConsumerIT.flow_rows_in_another_tenants_schema…:159` `expected 3 but was 2` — the test's arithmetic (r1 and r2 of window 1 share one destination: two rows); the expectation fixed to 2. After the item: unit 375 / 0; `PostgresOutboxConsumerIT` 38 / 0, `OutboxConsumerIT` 29 / 0.

## 4 · Item 4 — the kinds (5126d34)

| where | what |
|---|---|
| `registry/internal/ConfigInstantiatedBeans` (new) | `from(config, name)`: no `window` → null (not this road); `kind` absent → the call bean exactly as before (`service-group` required, `table-suffix`, `context`); `kind: wifi-session` → `WifiSummaries.sessionForWindow` (group 101 and suffix 101 fixed by the kind; `service-group` / `table-suffix` not read); `kind: wifi-dst` → `destinationForWindow`; any other word → `IllegalArgumentException("summary.beans.<name>.kind is 'x' — it must be wifi-session or wifi-dst (or absent: a call bean, as before)")` |
| `registry/internal/SummaryBootstrap` | `configInstantiated(name)` delegates (its top level names the step); its catch line now carries the cause's words — `could not activate summary bean 'badWifi': summary.beans.badWifi.kind is 'wifi-nope' — …` — and the service goes on with the other beans. (The whole start is not refused: a bean's fault stops that bean only, as the missing-group case always did; say if you want an unknown kind to refuse the start itself) |
| `summarybeans/wifi/WifiBeanKind` | the enum with the two words and `parse` |
| `profile-lab.yml`, `profile-radius2.yml` | `dailyWifiSessionSummary` / `hourlyWifiSessionSummary` (`kind: wifi-session`) and `dailyWifiDstSummary` / `hourlyWifiDstSummary` (`kind: wifi-dst`) under `enabledSummary` and `beans` — **twelve beans**; the head and a comment block say what they are; the dst pair idles until billing-core's flow ingest is on |
| `README.md` (Configuration, Layout), `docs/ad-as-call/postgres-ad-profile.md` (§1, §4, §6, §7) | the kinds named. There is no `config/README.md` in the repository (§9 F5) |
| `tools/lab/wifi-101-e2e.sh`, `outage-e2e.sh`, `upgrade-e2e.sh` | "8 of 8" → "12 of 12"; the ERROR-line ceiling 24 → 36 (§9 F3) |
| tests | `ConfigInstantiatedBeansTest` 7; `SummaryBootstrapTest` +1 (over the start, by system properties: the two kinds registered with their tables, the unknown one refused in the ERROR line's words, nothing dialled); `WifiProfileTest` 24 (the twelve; the four WiFi beans made from the profile alone through the bootstrap's road; the bed the same key for key) |

The print-what-this-start-resolved road, on the packaged jar (a clean snapshot of the item-4 tree): `tools/lab/run-lab.sh --show btcl/lab` → `PROFILE tenant btcl, profile lab …`, five `ENDPOINT … LOOPBACK` lines, `SECRET the store's password: none is configured`, exit 0; a real start with `-Dsummary.autostart=false` → twelve `bean registered` lines (`name=dailyWifiSessionSummary entity=cdr window=daily table=sum_wifi_day_101`, `name=dailyWifiDstSummary entity=wifi_flow window=daily table=sum_wifi_dst_day`, …), `autostart off — 12 bean(s) registered; no schema served … nothing dialled`, 0 ERROR lines.

Mutant: the kind ignored (`if (false)`) → `ConfigInstantiatedBeansTest.kind_wifi_dst…:47` null for the dst bean, `an_unknown_kind_is_refused…:78` nothing thrown; restored; green. Unit suite after the item: 384 / 0.

## 5 · Item 5 — end to end on the packaged jar, both engines (366f795)

`tools/lab/wifi-beans-e2e.sh both` — SS-0005's way (`run-lab.sh btcl/lab`, the endpoints shown first, every host this box; `summary.tenants.mode=single`; the lab profile's twelve beans; 5 s poll; no broker — the workers drain on the poll). `LabOutboxRow` gained `wifi-bc0008 <13 facts>` (one session as BC-0008's row writes it) and `wifi-flow <window_start> <rows>` (one `wifi_flow` outbox row); the script writes the SAME facts into raw tables (`cdr` with the columns, `wifi_flow_window`) beside each outbox row, in one transaction, as billing-core does. Run 17:07:50–17:09:13 +06; PostgreSQL 16 on 127.0.0.1:7751 (my `ss6-pg16-lab`), MySQL 5.7.44 on 127.0.0.1:3306 (the password by the NAME of its variable, `SECRET … from the environment variable LAB_MYSQL_PASSWORD`); **both HOLD: 86 checks ok, 0 FAIL, 0 ERROR lines of the service.**

| step | PostgreSQL | MySQL |
|---|---|---|
| 1 · served | `is served: 12 of 12`; the four WiFi beans registered with their tables | the same (twelve tables, the 60-day horizon) |
| 2 · 5 ad views first | 12 of 12 at row 5 in 5 s; `sum_voice_day_30` = `61\|BDT\|5\|2.5\|75` | the same |
| 3 · 12 WiFi sessions (BC-0008 rows: 8 at mirpur-10 — 4 × 30 TF_min, one idle, one in money, one of 0 s, one unmeasured — 4 free at uttara-7) | 12 of 12 at row 17 in 2 s | in 5 s |
| 4 · `sum_wifi_hr_101` / `_day_101` (sessions\|connected\|total\|avg\|up\|dn\|cost) | mirpur-10 TF_min other **5\|5\|7500\|1500\|4 000 000\|40 000 000\|125** (the unmeasured one summed) · TF_min idle 1\|1\|600\|600\|500\|5000\|10 · BDT other 1\|1\|900\|900\|200\|2000\|3.00 · no unit 1\|0\|0\|0\|0\|0\|0 · uttara-7 TF_min volume 3\|3\|2700\|900\|900\|9000\|0 · TF_min deauth 1\|1\|120\|120\|10\|100\|0; 6 rows; sum(sessions) 12 = the cdr rows of group 101; keyed `free\|1\|free-15` and `paid\|2\|wifi-30`; the hour bucket 09:00; the 101 VOICE tables count the same 12; the 30 tables unchanged | the same |
| 5 · 3 flow windows (W1 in two outbox rows, W2, W3) | `sum_wifi_dst_hr` / `_day`: mirpur-10 → 142.250.74.78:443/6 **4\|19300\|502400\|161\|421**, → 1.1.1.1:53/17 1\|300\|700\|5\|5, uttara-7 → 1.1.1.1 1\|100\|200\|2\|2; 3 rows; sum(flows) 6 = the `wifi_flow_window` rows; the hour 15:00 for 09:41Z; the dst bookmarks their own at the last row; `sum_voice_*_30`, `sum_voice_*_101` and `sum_wifi_day_101` byte-for-byte unchanged by the flows | the same (the flow rows are the lab's: in production nothing writes `wifi_flow` on MySQL — the dst pair idles) |
| 6 · the owner's two reports | `select tup_site, round(sum(total_duration_sec)::numeric / sum(sessions_connected), 6) from sum_wifi_day_101 where tup_starttime = '2026-10-16' group by tup_site` → **mirpur-10 1285.714286, uttara-7 705.000000** = the same from the raw `cdr` rows of the hour (`sum(durationsec) / count of durationsec > 0`: 9000/7, 2820/4). `select tup_site, tup_remote, sum(bytes_up + bytes_dn) from sum_wifi_dst_hr where tup_starttime = '2026-10-16 15:00' group by … order by total desc` → **mirpur-10: 142.250.74.78 521 700, 1.1.1.1 1 000; uttara-7: 1.1.1.1 300** = the same from the raw `wifi_flow_window` rows | the same (`cast(… as decimal(24,6))` for the division) |

Reds on the way — the script's, not the service's: "caught up" counted every bean at `max(id)` of the whole outbox (10 of 12: a `wifi_flow` bean never moves over `cdr` rows) → per entity; `agg()` called without the engine (`$3: unbound variable`); report 1's first query had an aggregate beside a plain column inside `string_agg` (an empty result) and would have divided `bigint / bigint` on PostgreSQL → the average computed inside the grouped subquery with a decimal cast. The service's numbers were right from the first run.

## 6 · The suites, from a clean copy of 366f795

From a clean copy of 366f795 — `git archive` into `~/ss6-work/clean-366f795/`; the private repository `~/.m2/ss6-repo` (EMPTY before and after: everything came from the read-only tails `arch-ss-review-repo`, `summary-service-repo`, `repository`); `mvn -o verify` (the jar packaged, every IT class); PostgreSQL 16 on 127.0.0.1:7751 (my `ss6-pg16-lab`) and the local MySQL 5.7.44 on 127.0.0.1:3306 with its password by the NAME of a variable; no Kafka — 17:11:23 to 17:15:52 (+06), BUILD SUCCESS:

| | tests · failures · errors · skipped |
|---|---|
| unit (surefire) | **384 · 0 · 0 · 0** (320 at the base + 6 of item 1 + 27 of item 2 + 22 of item 3 + 9 of item 4) |
| ITs (failsafe), all classes | **82 · 0 · 0 · 3** |
| PostgreSQL | **47 · 0 · 0 · 0** — `PostgresOutboxConsumerIT` 38 (35 + the item-2 test + the item-3 test + the second-tenant test), `PostgresTreeIT` 9 |
| MySQL | **31 · 0 · 0 · 0** — `OutboxConsumerIT` 29 (27 + 2), `MySqlTreeIT` 2 (the password by `SUMMARY_IT_MYSQL_PASSWORD`) |
| engine-neutral | `BuildBakesNoTenantIT` 1; `KafkaListenersIT` 3 **skipped** — no broker in my lab (§10) |

Before (SS-0005's clean copy): unit 320, ITs 77 / 0 / 3. Every red of this round is in §1–§5 (the fixture's site column; the second-tenant IT's arithmetic; the story script's yardstick, `agg` and report-1 query) — none a defect of the service, none flaky: nothing was run five times because nothing failed twice differently.

## 7 · The four tables' DDL as the service makes them

Rendered from the beans' `tableDdl(dialect)` (the same code the service runs at a bean's first use; `~/ss6-work/four-tables-ddl.sql`). PostgreSQL: the table and its two indexes in ONE transaction, plain (no partitions, as every summary table there). MySQL: ONE statement with the FULL daily partition set inside (`summary.ddl.partition-start`, default 1 January of the tenant's year; `partition-days`, default 730; closed by `pMAX`) — cut to two days below. The `hr` and `day` tables of a pair are the same statement, the name apart.

```sql
-- PostgreSQL · sum_wifi_day_101 (sum_wifi_hr_101 the same, the name apart)
CREATE TABLE IF NOT EXISTS sum_wifi_day_101 (id BIGINT GENERATED BY DEFAULT AS IDENTITY,
  tup_switchid INTEGER NOT NULL DEFAULT 0, tup_inpartnerid INTEGER NOT NULL DEFAULT 0,
  tup_site VARCHAR(64) NOT NULL DEFAULT '', tup_zone VARCHAR(64) NOT NULL DEFAULT '',
  tup_granttier VARCHAR(16) NOT NULL DEFAULT '', tup_category INTEGER NOT NULL DEFAULT 0,
  tup_packagecode VARCHAR(64) NOT NULL DEFAULT '', tup_customercurrency VARCHAR(16) NOT NULL DEFAULT '',
  tup_causefamily VARCHAR(16) NOT NULL DEFAULT '', tup_starttime TIMESTAMP NOT NULL,
  sessions BIGINT NOT NULL DEFAULT 0, sessions_connected BIGINT NOT NULL DEFAULT 0, total_duration_sec BIGINT NOT NULL DEFAULT 0,
  avg_session_sec NUMERIC(18,6) NOT NULL DEFAULT 0, bytes_up BIGINT NOT NULL DEFAULT 0, bytes_dn BIGINT NOT NULL DEFAULT 0,
  customercost NUMERIC(18,6) NOT NULL DEFAULT 0, PRIMARY KEY (id, tup_starttime));
CREATE INDEX IF NOT EXISTS sum_wifi_day_101_ix_starttime ON sum_wifi_day_101 (tup_starttime);
CREATE INDEX IF NOT EXISTS sum_wifi_day_101_ix_site_starttime ON sum_wifi_day_101 (tup_site, tup_starttime);

-- MySQL · sum_wifi_day_101 (sum_wifi_hr_101 the same, the name apart)
CREATE TABLE IF NOT EXISTS sum_wifi_day_101 (id BIGINT NOT NULL AUTO_INCREMENT,
  tup_switchid INT NOT NULL DEFAULT 0, tup_inpartnerid INT NOT NULL DEFAULT 0,
  tup_site VARCHAR(64) NOT NULL DEFAULT '', tup_zone VARCHAR(64) NOT NULL DEFAULT '',
  tup_granttier VARCHAR(16) NOT NULL DEFAULT '', tup_category INT NOT NULL DEFAULT 0,
  tup_packagecode VARCHAR(64) NOT NULL DEFAULT '', tup_customercurrency VARCHAR(16) NOT NULL DEFAULT '',
  tup_causefamily VARCHAR(16) NOT NULL DEFAULT '', tup_starttime DATETIME NOT NULL,
  sessions BIGINT NOT NULL DEFAULT 0, sessions_connected BIGINT NOT NULL DEFAULT 0, total_duration_sec BIGINT NOT NULL DEFAULT 0,
  avg_session_sec DECIMAL(18,6) NOT NULL DEFAULT 0, bytes_up BIGINT NOT NULL DEFAULT 0, bytes_dn BIGINT NOT NULL DEFAULT 0,
  customercost DECIMAL(18,6) NOT NULL DEFAULT 0,
  PRIMARY KEY (id, tup_starttime), KEY ix_starttime (tup_starttime), KEY ix_site_starttime (tup_site, tup_starttime))
ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
PARTITION BY RANGE COLUMNS(tup_starttime) (
  PARTITION p20261016 VALUES LESS THAN ('2026-10-17 00:00:00'),
  PARTITION p20261017 VALUES LESS THAN ('2026-10-18 00:00:00'),   -- … one a day over the horizon
  PARTITION pMAX VALUES LESS THAN (MAXVALUE));

-- PostgreSQL · sum_wifi_dst_day (sum_wifi_dst_hr the same, the name apart)
CREATE TABLE IF NOT EXISTS sum_wifi_dst_day (id BIGINT GENERATED BY DEFAULT AS IDENTITY,
  tup_gw VARCHAR(64) NOT NULL DEFAULT '', tup_site VARCHAR(64) NOT NULL DEFAULT '', tup_zone VARCHAR(64) NOT NULL DEFAULT '',
  tup_remote VARCHAR(45) NOT NULL DEFAULT '', tup_proto INTEGER NOT NULL DEFAULT 0, tup_port INTEGER NOT NULL DEFAULT 0,
  tup_starttime TIMESTAMP NOT NULL,
  bytes_up BIGINT NOT NULL DEFAULT 0, bytes_dn BIGINT NOT NULL DEFAULT 0, packets_up BIGINT NOT NULL DEFAULT 0,
  packets_dn BIGINT NOT NULL DEFAULT 0, flows BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (id, tup_starttime));
CREATE INDEX IF NOT EXISTS sum_wifi_dst_day_ix_starttime ON sum_wifi_dst_day (tup_starttime);
CREATE INDEX IF NOT EXISTS sum_wifi_dst_day_ix_site_remote_starttime ON sum_wifi_dst_day (tup_site, tup_remote, tup_starttime);

-- MySQL · sum_wifi_dst_day (sum_wifi_dst_hr the same, the name apart)
CREATE TABLE IF NOT EXISTS sum_wifi_dst_day (id BIGINT NOT NULL AUTO_INCREMENT,
  tup_gw VARCHAR(64) NOT NULL DEFAULT '', tup_site VARCHAR(64) NOT NULL DEFAULT '', tup_zone VARCHAR(64) NOT NULL DEFAULT '',
  tup_remote VARCHAR(45) NOT NULL DEFAULT '', tup_proto INT NOT NULL DEFAULT 0, tup_port INT NOT NULL DEFAULT 0,
  tup_starttime DATETIME NOT NULL,
  bytes_up BIGINT NOT NULL DEFAULT 0, bytes_dn BIGINT NOT NULL DEFAULT 0, packets_up BIGINT NOT NULL DEFAULT 0,
  packets_dn BIGINT NOT NULL DEFAULT 0, flows BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id, tup_starttime), KEY ix_starttime (tup_starttime), KEY ix_site_remote_starttime (tup_site, tup_remote, tup_starttime))
ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
PARTITION BY RANGE COLUMNS(tup_starttime) ( … the same daily set …, PARTITION pMAX VALUES LESS THAN (MAXVALUE));
```

Both pairs are `keptUpToDate`: a table of the name made by an earlier version is brought up to this at its first use (a column added with its default, a text widened), as the ad tables are.

## 8 · What devops must know

- **Four new tables, made by the service itself** at the beans' first use in each tier schema, on both engines: `sum_wifi_hr_101`, `sum_wifi_day_101`, `sum_wifi_dst_hr`, `sum_wifi_dst_day` (§7). No DDL to run, no new right: `summary_service` already has CREATE on the tier schemas; the tables are its own.
- **The beans' names for `enabledSummary`** (already in `profile-radius2.yml`, twelve beans): `dailyWifiSessionSummary`, `hourlyWifiSessionSummary` (`kind: wifi-session`), `dailyWifiDstSummary`, `hourlyWifiDstSummary` (`kind: wifi-dst`), each with its `window`. The lab scripts now wait for `is served: 12 of 12`.
- **The session beans sum from the outbox HEAD on radius2** (a `cdr` bean switched on later on a schema that has bookmarks: the engine's rule, unchanged): the 101 records written before they start — the 40 dead-lettered ones and B1's proof row — are not in `sum_wifi_*_101` unless the owner's D1 replay re-sends them. The `wifi_flow` beans are a NEW entity and start at 0: every flow row billing-core ever wrote is summed.
- **The dst pair idles** until billing-core's flow ingest is switched on on the bed (its radius2 profile block `enabled: false`); the beans, their bookmarks (0) and their tables are there before that, nothing errors. On MySQL nothing writes `wifi_flow` in production (billing-core writes it on PostgreSQL only): the dst beans exist there and idle.
- **The bucket of a flow row** is the window's start (UTC on the wire) on the root tenant's zone — `summary.zone`, Asia/Dhaka when the profile names none — as the voice beans' rows are the tenant's wall clock.
- **A report over a range recomputes the average** from `sum(total_duration_sec) / sum(sessions_connected)` — never averages `avg_session_sec`; a report that wants measured bytes only filters the raw `cdr` on `ValidFlag` (the summary sums ValidFlag 0 rows too, rule b); "devices per destination" is `count(distinct mac)` on `wifi_flow_window`, not a summary column.
- Threads: one per (schema, bean) — 12 per tier now.

## 9 · Findings — yours to decide

| # | finding | proposal |
|---|---|---|
| F1 | **`BALANCE_EXHAUSTED` is `balance`** by its word, though the WiFi meter's `minutes` cut and the plane's `time-plane` cut reach the record as that word (`WifiCause.java:49`) — a minutes package used up is counted under `balance`, not `time`; `time` holds `PACKAGE_EXPIRED` and `MAX_DURATION_REACHED` only. The record cannot tell the two apart by `HangupCause`; `ReleaseCauseSystem` (5 for both) cannot either | yours: keep (the word rules), or rule `BALANCE_EXHAUSTED` → `time` for the WiFi bean (one map entry, one test line), or ask the WiFi architect for a distinct record word for the minutes cut |
| F2 | `hangupCause` added to the blob's `Cdr` beyond the brief's item-1 list — ruling 6 needs it; the ad category reads the same field through its own `AdCallCdr` | none; said so the list is exact |
| F3 | `outage-e2e.sh` and `upgrade-e2e.sh` now wait for 12 beans (and 36 ERROR lines at most); not re-run this round (S16's story is not mine to re-prove) — SS-0005's F4 again | say if you want them re-run on the twelve-bean profile before the bed |
| F4 | the e2e story writes `wifi_flow` outbox rows on MySQL too (the lab's own, to prove the dst tables on that engine); in production billing-core writes the entity on PostgreSQL only | none |
| F5 | the brief's "`config/README.md` names the kinds": no such file exists at 7d63ae6; the kinds are named in `README.md` § Configuration and `docs/ad-as-call/postgres-ad-profile.md` §1/§6 | say if a `src/main/resources/config/README.md` is wanted |
| F6 | an unknown `kind` refuses THAT BEAN (said in the bootstrap's ERROR line, in words) and the start goes on with the others — the missing-`service-group` behaviour since §12g. The brief says "refused at start in words" | yours: keep, or make an unknown kind a `REFUSING TO START` of the whole service (one line in the bootstrap) |

## 10 · The rules, kept

Java 21 (Corretto 21.0.9); Maven 3.9.10 offline with `-Dmaven.repo.local=$HOME/.m2/ss6-repo -Dmaven.repo.local.tail=$HOME/.m2/arch-ss-review-repo,$HOME/.m2/summary-service-repo,$HOME/.m2/repository` for every build — the head repository stayed EMPTY (everything came from the read-only tails); nothing installed into `~/.m2/repository`; no dependency was missing offline. The labs: my own `ss6-pg16-lab` (postgres:16, 127.0.0.1:7751, trust — no password exists; the §4 recipe of prime-context's `postgres-tenancy.md`), removed at the end; the local MySQL 5.7.44 at 127.0.0.1:3306 as root with the password by the NAME of its variable (`set -a; . ~/.lab-env/mysql.env; set +a`, then `SUMMARY_IT_MYSQL_PASSWORD="$LAB_MYSQL_PASSWORD"` / `SS_LAB_MYSQL_PASSWORD_VAR=LAB_MYSQL_PASSWORD`) — on no command line, in no file of the repository, not in this note; its databases of this round (`ss6_summary_it*`, `ss6_clean_it*`, `lab_wifibeans`) dropped at the end; the owner's `summary_it` not touched. No Kafka started (`KafkaListenersIT` self-skipped; the shared `onlab-kafka` on 7592 never touched); nothing dialled off this box. The jar was never started bare: the lab story through `tools/lab/run-lab.sh btcl/lab`, the item-4 proof with `autostart=false` on 127.0.0.1 (nothing dialled). `SummaryEngine` / `OutboxReader` untouched. Every commit after its green run; one per item; only `wifi-beans` pushed. `date` before every time written. The worktree stays for your merge; the main checkout, `postgres-ad-call` and `master` not touched; no other agent's worktree entered; the `wifi` and `telcobright-billing-core` repos read by `git show` only. Mutant originals in `~/ss6-work/mutants/`; the notes in `~/ss6-work/notes.md`; the logs in `~/ss6-work/*.log`.
