from: SS        to: ARCH        kind: done        number: 0002
date: 2026-10-04T13:36+06:00        branch: postgres-ad-call (from master ec2546e)        head: 2e9b03e (the code and its suite; this note is the commit after it — pages only)        second branch: no-bundled-tenant c2f9127 (S15: prepared, not merged)
first written: 2026-10-04T11:59+06:00 on 4cb77a1 (S1–S12); brought up to date after S13, S14 and S15
subject: S1–S9 of the brief are built, S10–S12 from the first rehearsal, S13 (the content in the key) and S14 (no value wider than its column); S15 (the jar enables no tenant) is PREPARED on its own branch — the suites, the breaks, what I assumed, what I could not do, what is not mine to fix, and the template of the bed profile

# SS-0002 · done — the ad category on the `cdr` stream, PostgreSQL, the whole tree

## 0 · What to relay

| | |
|---|---|
| the branch | `postgres-ad-call`, pushed. `master` is not touched. The full suite was green on the head of every push. One head was pushed before its full run (aeb53d4: the unit suite only); the full suite ran on it right after, green |
| S1–S9 | done, each with tests (§1) |
| S10–S12 (R-0001) | done: a profile from outside the jar, the lab key means "this box", the ping's topic carries its root (§1) |
| S13 | done: `sum_ad_*` is keyed by content too — `tup_contentid`. An existing table gets the column by the service's own step (§12) |
| S14 | done: `tup_rulecode` is 64 wide; an existing table is widened by the same step. What a box would have met before: nothing failed (§13) |
| S15 | PREPARED, not merged: branch `no-bundled-tenant` c2f9127, ONE commit on the code head of `postgres-ad-call` (2e9b03e) — the jar enables no tenant, a start that names none is refused (§14) |
| an upgrade, seen | the older packaged jar (4cb77a1) summed three tiers; this jar started on the same schemas: twelve changes said, every row as it was, a content's own row after it, a second start changed nothing (§4) |
| for stream-x and stream-y | the column: `tup_contentid`, `VARCHAR(64) NOT NULL DEFAULT ''` on both engines; the 4th part of the row's key, after the campaign; the table's last column (§12) |
| the suite | before (master ec2546e): 96 unit + 8 MySQL IT. Now, `postgres-ad-call`: **290 unit + 69 IT, 0 failed, 0 skipped**; `no-bundled-tenant`: **294 unit + 70 IT, 0 failed, 0 skipped** — on MySQL 5.7.44, PostgreSQL 16 and Kafka 3.9, all on 127.0.0.1 (§2) |
| the breaks | **308 rules** broken once in a clean clone: green before, red after, restored with `git checkout`. 5 did not go red at first — real gaps: their tests were sharpened and then seen red (§3) |
| the DDL | `src/main/resources/db/postgres/sum_ad.sql` (19 columns after the id: the 17 the reader knows, `chargedunits`, `tup_contentid`; plain tables; and the by-hand statements for a table made earlier). The README has the column list |
| the page | `docs/ad-as-call/postgres-ad-profile.md` — the profile of a PostgreSQL tenant with the ad beans, for the person who deploys |
| the bed profile | §9: a template of `btcl/bed`. Every address and every secret is a NAME to fill |
| against billing-core's own rows | its lab command (64334ac) run from my clone on MY lab, then my jar: every line SAME (§4) |
| for the window | the two topics must exist before billing-core starts; the unit names its tenant and its password's variable (§9) |
| your three rulings | D1 → S15 (prepared), D2 → S14 (done), D3 stays (§8) |

## 1 · What was built

| # | what | held by |
|---|---|---|
| S1 | The ad beans read the `cdr` outbox stream. They keep the entries whose `Cdr.ServiceGroup` is 30. Dimensions and measures come from the `Cdr` and from the JSON in `AdditionalMetaData`. The `ad_cdr` entity and its models are gone | `AdSummaryBeanTest`, `AdSampleMessageTest` (the brief's sample message), the contract test `the_ad_beans_read_the_cdr_stream…` on both engines |
| S2 | The tables keep their columns. `tup_tenant` is always the schema's own name (the drain passes the tier to the bean). ad-sphere's reader needs only the schema | `AdReaderContractTest`; `PostgresOutboxConsumerIT.the_summary_tables_are_plain_tables_and_ad_spheres_reader_sees_and_reads_them` |
| S3 | `tup_app` is 64 wide, in the DDL of both engines and in the builder's cut | `AdSummaryBeanTest.an_app_name_past_the_column_is_cut_to_64…`, `AdReaderContractTest` (the two engines give a column the same shape) |
| S4 | The call and the chargeable categories take group 30 from the profile. The call bean needed a branch (it threw on group 30). No `ChargingStatus` early return there, as you ruled | `CallSummaryGroup30Test`, `ChargeableSummaryGroup30Test`, `WifiProfileTest` |
| S5 | PostgreSQL as the store, chosen per profile at run time (`summary.store.kind`). One jar serves either engine. One table description, two renderers; plain tables on PostgreSQL, no partitioned form (ruled). Exactly-once and crash-replay hold on PostgreSQL | ONE contract, word for word on both engines: `OutboxConsumerIT` (MySQL: 17 then, 23 now), `PostgresOutboxConsumerIT` (the same and 8 of its own: 25 then, 31 now) |
| S6 | Every tenant of the tree in one process: a worker and a bookmark per (schema, bean). The tree comes from prime-context (`POST /get-specific-tenant-root`). The doorbell `config_event_loader_<root>` and a timer read it again. Tables are made at a schema's first use. A reseller made at run time is served with no restart | `PostgresTreeIT` (9), `MySqlTreeIT` (2), `TenantWatcherTest` (11), `SummaryBeanRegistryTest`, `KafkaListenersIT`; `tools/lab/tree-e2e.sh` |
| S7 | A schema seen for the first time starts every bean at offset 0. On a schema already served, a bean switched on later starts at the outbox head. Decided once per schema, before its workers start | `SummaryBeanRegistryTest` (4 tests), `PostgresTreeIT.billing_writes_first_summary_starts_second…` |
| S8 | The ping `{tenant, entity, rows}` wakes the workers of the tenant it names. The poll stays. Measured: a view is in the summary 25 to 134 ms after its ping (the IT's own measure, 18 suite runs today, some on a loaded box); 268 ms in the lab story, which asks every 200 ms | `SummaryBeanRegistryTest` (5 tests), `KafkaListenersIT`, `PostgresTreeIT.a_view_is_in_the_summary_within_a_few_seconds_of_its_ping` |
| S9 | `summary.store.password-ref: env:NAME`. A named variable that is not set refuses the start, in words that name it, before anything is dialled. The value is never printed. The inline form stays. A password in the URL is refused and hidden in every form | `StoreConfigTest` (17), `UrlSecretsTest`, `SummaryBootstrapTest`; `tools/lab/secret-e2e.sh` |
| S10 | A profile from OUTSIDE the jar. One key names the directory that holds `config/tenants/<tenant>/<profile>/profile-<profile>.yml`: `-Dsummary.config.dir` or `SUMMARY_CONFIG_DIR` (not named: the working directory). A file there wins; else the jar's. The start's first line says which file was read | `WifiProfileTest` (10 tests of it), `SummaryBootstrapTest` |
| S11 | The lab key means "this box": `localhost`, a loopback address, or an address one of this box's own interfaces holds. Another box's address still refuses the start, in words, before anything is dialled. The key is `summary.endpoints.local-only`; its first name `summary.endpoints.loopback-only` is the same switch | `StartEndpointsTest` (15), `SummaryBootstrapTest`; `tools/lab/this-box-e2e.sh` |
| S12 | No code. On a deployment the ping's topic carries the root: **`cdr_summary_ping_<root>`, the same name on both sides** (billing-core's `billing.summary.ping-topic`), **and the topic must exist**. Said in the README, the page and decisions §16i. The lab profile and the lab scripts use `cdr_summary_ping_btcl` | `WifiProfileTest.the_ping_topic_of_a_deployment_carries_its_root`; `tools/lab/ping-topic-e2e.sh` |
| S13 | The ad summaries are keyed by CONTENT too: `tup_contentid` = the record's `additionalMetaData.contentId`, `''` when it carries none. One row per (the key as before, content); every measure as it was. An existing table gets the column by the service's own idempotent step; its rows read `''` (§12) | `AdSummaryBeanTest` (4 tests), `AdSummaryTest`, `AdReaderContractTest`, `TableDdlTest`, `OutboxProvisioningTest`; on both engines the contract's `two_contents_of_one_campaign_in_one_hour_are_two_rows_and_a_reread…`, `a_view_with_no_content_is_the_empty_string_row…`, `an_ad_table_made_before_the_content_gets_the_column…`; `tools/lab/reconcile-with-billing-core.sh` (per content) |
| S14 | A value is never wider than its column: one width per text of the key, used by the builder's cut and by the table; each is its source's full width. `tup_rulecode` is 64 (it was 20). An existing table is widened by the same step (§13) | `AdSummaryBeanTest` (3 tests), `TableDdlTest` (3 tests); on both engines `every_text_of_the_key_at_its_sources_full_width…`, `a_text_wider_than_its_column_is_cut_by_the_service…`, `an_ad_table_with_the_20_wide_rule_code_is_widened…` |
| S15 | On the branch `no-bundled-tenant` only: the jar's registry enables no tenant; a start that names none is refused in words that say how a deployment names one; the voice deployment's lines are `deploy/tcbl-tenants.yml.example` (§14) | `WifiProfileTest` (4 tests), `SummaryBootstrapTest`, `BuildBakesNoTenantIT.the_packaged_jars_own_registry_enables_no_tenant` |

Also built, because the work asked for it:

| what | why |
|---|---|
| `chargedunits`, a second measure | your ruling on SS-0001: money (`BDT`) and a package's units are never added |
| on PostgreSQL the service never makes `summary_affected` | your rule; §5 |
| the profile source lists nothing | the leak I reported at S5: Quarkus baked the build tenant's keys into the jar as every tenant's default. `BuildBakesNoTenantIT` reads the packaged jar |
| a start says its profile and its endpoints first; print-only; the lab key | your lab rule; §4 |
| a fault of the configuration refuses the start, in words | a wrong engine for the URL, no store with the workers on, a config directory that is none, a tenant with no profile, a file that cannot be parsed. A store that does not ANSWER is another matter: it is tried again, and never fails a start |
| `SET LOCAL` on PostgreSQL | the schema and the string setting end with the transaction; nothing stays on a pooled connection |

The repo's own pages are brought to the branch: `README.md`, `docs/architecture.md`, `docs/decisions.md` §16 (a–n), a dated banner in `CLAUDE.md`.

## 2 · The suites

| | unit | integration | failed | skipped |
|---|---|---|---|---|
| before — `master` ec2546e | 96 | 8 (MySQL) | 0 | 0 |
| S1–S12 — 4cb77a1 | 270 | 57 | 0 | 0 |
| S13 — d97f44a | 283 | 63 | 0 | 0 |
| S14 — 96920c7 | 289 | 69 | 0 | 0 |
| now, `postgres-ad-call` — 2e9b03e (two test fixes, one more test, a lab story) | 290 | 69 | 0 | 0 |
| `no-bundled-tenant` — S15, c2f9127 | 294 | 70 | 0 | 0 |

The 69: the build guard 1, the Kafka listeners 3, the MySQL tree 2, the contract on MySQL 23, the contract on PostgreSQL 31, the PostgreSQL tree 9.
The branch has one more: the build guard reads the packaged jar's registry.
Each of these runs was made in a clean detached clone of that commit.

Two faults of my TESTS showed in the last runs, on a loaded box, and are fixed (e4e4243, 2e9b03e) — said because a green
number hides them:

| | what it was | now |
|---|---|---|
| red once | `KafkaListenersIT.a_ring_of_the_roots_doorbell…` asserted "a read was asked" the instant the listener said it hears; the listener says so, THEN asks, on its own thread. A dozen runs before it were green | the test waits for the read (bounded). The listener is not changed |
| skipped once | the Kafka lab's probe asked the broker once, with 4 seconds; the first answer of a JVM was slower, and one of the three tests was SKIPPED with the broker up. A skipped test proves nothing | the probe asks three times, ten seconds each |
A test whose lab does not answer is SKIPPED, never passed; none was skipped. The MySQL tests are green: MySQL's behaviour did not change.
The command: `tools/lab/pg-lab.sh all`, then
`mvn verify -Dsummary.it.mysql.url='jdbc:mysql://127.0.0.1:7633/?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true'`.

## 3 · The breaks

Each rule was broken once in the code, in a clean detached clone of the pushed head. Its test had to be green before the
break and red after it. The file was restored with `git checkout --`; `git status --short` was empty after each.

| pass | on | rules | red at once | not red at first |
|---|---|---|---|---|
| S1 | 7372784 | 32 | 31 | 1 — my harness (an IT that was red before the break). Run again: red |
| S2–S4 | 14bb4d3 | 25 | 20 | 5 — my harness (it hid the profile's files). Run again: red |
| S5, the lab rule | aeb53d4 | 37 | 34 | **3 real gaps** → 4 re-specs red on bd7361c |
| S6–S8 | 92fae7d | 52 | 50 | **2 real gaps** → 2 re-specs red on ea95a69 |
| S9, the watcher's retry, the profile file | ad13783 | 30 | 30 | — |
| the unit of work's edge (`SET LOCAL`) | fda182a | 9 | 9 | — |
| the start's refusals, a password in a URL | 06bdfbd | 18 | 18 | — |
| S10–S12, print-only | 4cb77a1 | 46 | 46 | — |
| S13, the step for an existing table, S14 | 96920c7 | 45 | 45 | — |
| an older table of any earlier shape | 2e9b03e | 1 | 1 | — |
| S15, on its branch | c2f9127 | 13 | 13 | — (12 by the harness; 1 by hand, on the same commit before its last rebase: the pom's line that names the unit tests' tenant) |
| **all** | | **308** | **297** | 6 my harness, 5 real gaps — all 11 seen red after |

The real gaps the breaks found, and what was done:

| the rule | the gap | now |
|---|---|---|
| head-init never moves a bookmark that exists | the test used a way that could not tell `DO NOTHING` from `DO UPDATE` | it head-inits again while a row waits; held on both engines |
| a failed drain leaves nothing | only a failure BEFORE any write was tested | a new contract test: a failure after some writes takes every one of them back |
| the hosts of a URL are read | a dead branch (`jdbc:` prefix) | removed |
| the tree: a context is not a tenant | no test had a tenant-shaped object inside a context | it has one |
| the bookmarks are decided before a worker starts | the restart path of a late bean was not tested | `a_bean_enabled_by_a_restart_on_a_served_schema_sums_from_now_never_from_the_residue` |

The break of "a host name is never looked up" makes a name into an address WITHOUT a lookup: a break that looked names up
would itself have sent a query off this box.

## 4 · The lab, and the rule of a lab start

My lab is my own: `ss-pg16-lab` (127.0.0.1:7643), `ss-mysql57-lab` (7633), `ss-kafka-lab` (7692). No password exists.
`ping-topic-e2e.sh` makes one more broker of its own (`ss-kafka-noauto-lab`, 7694) and removes it.
No other container was touched. Nothing was dialled on a live box or on the bed.
**The lab is removed now**: the three containers and their volumes (`tools/lab/pg-lab.sh down`), no process of mine runs,
none of my ports listens. `tools/lab/pg-lab.sh all` makes the lab again in a minute.

**My lab script does the rule.** `tools/lab/run-lab.sh` is the only way a service is started in the lab:

1. it runs the jar print-only: the service says `PROFILE …` (which file was read) and one `ENDPOINT …` line for the
   store, each broker, prime-context, each config source and the listener. Nothing is started, nothing is dialled;
2. the script reads the hosts itself. It asks the KERNEL for this box's addresses, not the service. One host that is
   not this box: not started (exit 3). A configuration that would refuse the start: not started (exit 4);
3. the real start carries `summary.endpoints.local-only=true`, so the service refuses by itself if anything changed.
   The lab profile carries the key itself too. A start names its tenant.

One mistake of mine is on record: at S5 a lab start told to serve `btcl/lab` dialled tcbl's config-manager once (the
connection timed out; nothing was exchanged). The cause is fixed (the profile source lists nothing; the contexts are
loaded only when the workers start), and the rule above came from it.

The stories with the PACKAGED jar. The first five ran on 4cb77a1; the first two ran again on the S14 head (96920c7); the
sixth is the upgrade from 4cb77a1 to that head:

| script | what it shows | result |
|---|---|---|
| `tools/lab/tree-e2e.sh` | billing-core's tables from ITS OWN DDL file, rows written as it writes them, the tree btcl > res_44, a reseller made at run time | a view in both schemas 268 ms after the last ping; `res_45` served 1,027 ms after the doorbell, no restart; `DELETE` on `cdr` and on its partition refused; the outbox is billing_core's. On the S14 head each tier's row carries `content 'c-81'` and `rule 7001` |
| `tools/lab/reconcile-with-billing-core.sh` | the summaries beside billing-core's OWN rows | every line SAME. btcl: 44 views, 41 shown, 3 failed, 310 s, money 16.8. res_44: 42 views, money 16.0, units 10.0. On the S14 head also PER CONTENT: res_44 `'c-81'` 42 views 16.0 / 10.0; btcl `''` 44 views (billing-core's lab writes the root's record with the brief's short meta data: no content) |
| `tools/lab/secret-e2e.sh` | S9 against a role that needs a password | refused without the variable (exit 4 / 1); a wrong value: the store refuses, the service stays up and tries again; the right value: served. The value is in no log and on no command line |
| `tools/lab/this-box-e2e.sh` | S11 | 192.0.2.1: not started, the service's own start refused, nothing dialled. In a namespace whose loopback also holds 10.10.250.1: `THIS-BOX`, the tree is read from it. The same address outside: `NOT-THIS-BOX` |
| `tools/lab/upgrade-e2e.sh [an older commit]` | S13 and S14 on a deployment that HAS ROWS, with two packaged jars: the older version (a worktree of that commit, packaged there) runs its own tree story and is left as it stands; this version starts on the same schemas | twelve changes said in WARN lines (3 tiers × 2 ad tables × the rule code widened, the content column added); every row its id, its numbers and its rule code, each with `''` as its content; a view after the upgrade: `'c-81'` has a row of its own beside the older row (btcl: `''` 3 views 1.20, `'c-81'` 1 view 0.40); a second start: 0 changes; res_44's views in the summary = its `cdr` rows |
| `tools/lab/ping-topic-e2e.sh` | S12 on my side, on a broker with auto-create off | no topic: the service serves, health UP, about one WARN a second names the topic; a view is not summed (poll set to 10 min). The topic is made: heard with no restart, the view summed 4 to 5 s later (two runs) |

**How the reconcile script is run** (you asked): first billing-core's lab command, from a CLONE of its repository and
only against this lab — `mvn -f java/pom.xml test -Dtest=LabSchemaForTheReportRoads -Dbc.lab.pg.url=jdbc:postgresql://127.0.0.1:7643/routesphere`
(it drops and re-makes `btcl` and `res_44`). Then `mvn package -DskipTests` here, then
`tools/lab/reconcile-with-billing-core.sh`. Every line must say SAME. The head of the file says the same.

**S6 on rows written the way billing-core writes them** (you asked to be told): yes, both ways. With rows written by
billing-core's own code (above). And with its tables made from its DDL file and rows written under its advisory lock,
one transaction per tier (`tree-e2e.sh`). Your rehearsal then saw it on the real chain: 4,479 views, tier by tier.

## 5 · `summary_affected` — your three questions

| # | |
|---|---|
| 1 · does my code create `summary_affected`? | On MySQL only: `OutboxInfraDdl.MYSQL_DEV_OUTBOX` (a `CREATE TABLE IF NOT EXISTS`, as before this work), and the reference script `src/main/resources/db/summary_outbox.sql` (its head says MySQL ONLY). **On PostgreSQL: nowhere.** There the service makes `summary_offset`, `summary_affected_dlq` and its `sum_*` tables only. Pinned by `TableDdlTest` (2 tests), `OutboxProvisioningTest.on_postgresql_the_infra_tables_are_the_services_own_and_the_outbox_is_never_created`, and `PostgresOutboxConsumerIT.the_service_makes_only_its_own_tables_and_never_billings_outbox` |
| 2 · the wait | `SummaryBeanRegistryTest` and `PostgresTreeIT`, the same name: `a_schema_with_no_summary_affected_waits_no_table_is_made_one_warn_the_others_go_on_and_it_is_picked_up_with_no_restart`. One WARN names the schema and the table. Your rehearsal saw it: `res_45`, 52 s, no restart |
| 3 · MySQL | kept as it is. It does not have the fault: a table has no owner there, and rights are granted on the database |

## 6 · What I assumed

| # | |
|---|---|
| 1 | The tree: the schemas are the `dbName` of every node of `POST /get-specific-tenant-root`. A name that is not letters, digits and `_` is not served, and an ERROR says so |
| 2 | Any ring of the doorbell means "read the tree again". Its payload is not read |
| 3 | The ping's `tenant` is the schema's name. A ping that cannot be read wakes every worker, as before |
| 4 | Money is the unit `BDT` (in any case). Every other unit is a package's. A leg that names no unit is not money |
| 5 | `outcome` is `done` when `HangupCause` is `NORMAL_CLEARING`, else `failed`. `shown` = an answer time |
| 6 | `summary_service` has USAGE and CREATE in each tier schema, SELECT on billing-core's tables and DELETE on `summary_affected` only. My PostgreSQL tests run with exactly these rights |
| 7 | One process per root. No second instance, no leader election |
| 8 | The names `summary.config.dir` / `SUMMARY_CONFIG_DIR`, `summary.endpoints.local-only`, and the variable `TENANT_BTCL_SWITCH_SUMMARY_SERVICE_PASSWORD` (after `TENANT_<ID>_<KIND>`) are mine. Say if you want others |
| 9 | The directory holds `config/tenants/…` (seed's way: the parent of `config`). billing-core's key names the `config` folder itself. I followed your sentence |
| 10 | MySQL and `chargedunits`: the one table description gives the column on MySQL too. The step of S13 is general for the ad tables — any described column a table lacks is added when it has a default, any narrower text column is widened — so stream X's older MySQL table (17 columns, `tup_app` 32) is brought up by it too: four statements, held by a test of the statements. Run for real on both engines: a text column added and a text column widened (the first PostgreSQL version's table). No deployed MySQL tenant has group 30 |
| 11 | The tree is read again every 300 s (`summary.tenants.refresh-seconds`). It is the backstop for a ring that is missed (R-0001 finding 3) |
| 12 | A content id is a text of at most 64 characters (`ad_content.id VARCHAR(64)` in ad-sphere). Blanks around it are not part of it; a number is read as its digits; two ids that differ only in case are two contents |
| 13 | "The row's unique key" is the engine's key (§12): no `UNIQUE` index is on the tuple today, on either engine, and I added none. Say if you want one |
| 14 | S14 is the AD tables'. The call and chargeable tables are not widened (they are legacy tables on the voice deployment; F7) |

## 7 · What I could not do, and why

| # | what | why |
|---|---|---|
| 1 | S10–S12 on the real chain | built and proven on my lab after R-0001. The kit can drop its workarounds 6 and 7 now (§10). Its next run is the proof |
| 2 | the real prime-context in MY lab | my lab runs summary-service alone; a stand-in answers its one read road. Your rehearsal ran the real one on 92fae7d |
| 3 | a load or soak test | none. My runs are small: tens of views per story. The rehearsal's 4,479 views over 4 tiers is the largest seen |
| 4 | the unit file and the deploy tool's entries | not my lane. The page shows the unit's lines; §9 is the profile |
| 5 | a real secreteer file | the profile names a variable; I proved the path with a throwaway role and a random password in my own shell |
| 6 | ad-sphere's reader probe on the last head | run on fda182a (83 rows read by its real `JdbcCdrReader`). It reads columns by name, so a column added at the end does not disturb it; the IT with the reader's own query is in the suite and green on the S14 head |
| 7 | an upgrade with two packaged jars on MySQL | rehearsed on PostgreSQL only (`upgrade-e2e.sh`, §4). On MySQL the ITs make the first version's table by its DDL, with a row, and run the real provisioning on it |
| 8 | S13–S15 on the real chain | built after R-0001; the next run of the kit is the proof (§10) |

## 8 · Not mine to fix, and the three calls you ruled

| # | finding | whose |
|---|---|---|
| F1 | ad-sphere's reader does not read `chargedunits` yet. The column is there | ad-sphere (known: F-U1) |
| F2 | billing-core's lab check: its `StartEndpoints.IsAnAddress` takes any four numbers of 1–3 digits and hands them to `InetAddress.getByName`. For `999.1.1.1` the JDK may look that text up as a NAME — a query that leaves the box. I read this in its code at 64334ac; I did not run it. Mine parses the numbers itself | billing-core |
| F3 | A missing topic makes the Kafka client log about one WARN a second, on my side too. It is loud on purpose; the fix is the topic | the window's list |
| F4 | prime-context's W12 (`summary_service` SELECT only by default): I did not see it land. It does not matter to me: my reaper deletes from `summary_affected` only, and billing-core's own DDL revokes the rest (seen: `DELETE` on `cdr` refused) | prime-context |
| F5 | A poison outbox row goes to `summary_affected_dlq` after 8 tries, with one ERROR line. Nobody is paged | operations |
| F6 | One thread per (schema, bean), and a pool of 16. A worker holds a connection for one transaction only. Fine at 4 tiers; a tree of hundreds wants a number first | a later decision |
| F7 | On the wifi tenant the CALL and CHARGEABLE tables have text columns narrower than 64: `sum_voice_*_30.tup_matchedprefixcustomer` 32 (the record's matched prefix — the routing rule for a view nobody was admitted for) and `tup_customercurrency` 16 (the unit); `sum_chargeable_*.tup_prefix` 32 and `tup_billeduom` 32. Their builders cut too, so nothing fails; a longer value is stored as its first characters. I did not widen them: on the voice deployment these are legacy tables another system writes, and the service never alters them | say if group 30 can send more than 32 characters there |
| F8 | ad-sphere's reader does not select `tup_contentid` yet. Until it does, a campaign's window may come back from it as SEVERAL rows that look alike: they differ only in the content it does not select. Sums over them are right; a screen that lists rows shows them twice. Rows summed before the column read `''` | stream-x (you pass it on) |

| # | my call | your ruling | now |
|---|---|---|---|
| D1 | The jar's `config/tenants.yml` enables `tcbl/dev`, whose profile names CCL's database, broker and config-manager. A start that names no tenant falls on it | S15: prepared, not decided | the branch `no-bundled-tenant` (§14). On `postgres-ad-call` it is as it was: the start's first line says `THE JAR'S OWN config/tenants/tcbl/dev/…`, and a lab start refuses it |
| D2 | `tup_rulecode` is 20 wide; with no rule table the switch puts the zone there (up to 64) | S14: widen it | done (§13) |
| D3 | The key's default `cdr_summary_ping` (no root) | it stays | unchanged |

## 9 · The bed profile `btcl/bed` — a template

Every `<NAME>` is to be filled by the window. No address and no secret of a box is known to me or written here.
The file goes into the deployment's directory, not into this repository's jar:
`<CONFIG_DIR>/config/tenants/btcl/bed/profile-bed.yml`.

```yaml
summary:
  autostart: true                              # the workers start with the service

  store:                                       # the switch database; a tenant is a SCHEMA of it
    kind: postgresql
    url: jdbc:postgresql://<SWITCH_PG_HOST>:<SWITCH_PG_PORT>/<SWITCH_DATABASE>      # no schema, no user, no password in it
    username: summary_service
    password-ref: env:<PASSWORD_VARIABLE>      # the NAME of the variable, e.g. TENANT_BTCL_SWITCH_SUMMARY_SERVICE_PASSWORD
    max-size: 16

  tenants:
    mode: tree                                 # every tier schema of the root's tree, in this one process
    root: btcl
    prime-context:
      base-url: http://<PRIME_CONTEXT_HOST>:<PRIME_CONTEXT_PORT>
    doorbell:
      topic-base: config_event_loader          # -> config_event_loader_btcl; the topic must exist
      bootstrap-servers: <KAFKA_BOOTSTRAP>
    refresh-seconds: 300                       # the tree is read again, ring or no ring

  outbox:
    entity-type: cdr
    ping-topic: cdr_summary_ping_btcl          # the SAME name as billing-core's billing.summary.ping-topic; the topic must exist
    ping-bootstrap-servers: <KAFKA_BOOTSTRAP>
    poll-interval-seconds: 5
    max-rows-per-tx: 1
    segment-size: 1000
    reaper-interval-seconds: 60
    quarantine-after: 8

  enabledSummary:
    - dailyAdSummary                           # sum_ad_day_30
    - hourlyAdSummary                          # sum_ad_hr_30
    - dailyCallSummarySg30                     # sum_voice_day_30
    - hourlyCallSummarySg30                    # sum_voice_hr_30
    - dailyChargeableSummary                   # sum_chargeable_day
    - hourlyChargeableSummary                  # sum_chargeable_hr
  beans:
    dailyCallSummarySg30:  { window: daily,  service-group: 30, table-suffix: "30" }
    hourlyCallSummarySg30: { window: hourly, service-group: 30, table-suffix: "30" }
```

Beside it, for the unit:

| what | where | value |
|---|---|---|
| the directory | the unit's environment | `SUMMARY_CONFIG_DIR=<CONFIG_DIR>` (or leave it out and make `<CONFIG_DIR>` the working directory) |
| the tenant | the unit's environment, or `<CONFIG_DIR>/config/tenants.yml` with `btcl` / `bed` / `enabled: true` | `SUMMARY_ACTIVE_TENANT=btcl/bed` |
| the password | secreteer's file, by the unit's `EnvironmentFile=` | `<PASSWORD_VARIABLE>=…` — never in the profile, a URL or a command line |
| the listener | `config/application.properties` in the working directory | `quarkus.http.host=<THIS_HOSTS_OWN_ADDRESS>`, `quarkus.http.port=<PORT_IN_THE_7000_RANGE>`. The service serves `/q/health` only |
| NOT in a bed profile | | `summary.endpoints.local-only` (a lab's key) |

What must be there before the first start:

| | |
|---|---|
| the role | `summary_service`, LOGIN, its password in secreteer; USAGE and CREATE on each tier schema (prime-context's provisioning gives them) |
| the topics | `cdr_summary_ping_btcl` and `config_event_loader_btcl` exist — **before billing-core starts** (R-0001 finding 2) |
| billing-core | its profile names the same `cdr_summary_ping_btcl` |
| the order | none is needed between billing-core and this service: both orders are safe (S7, and the wait of §5) |

Read at the first start: `PROFILE tenant btcl, profile bed (…): the file <CONFIG_DIR>/config/tenants/btcl/bed/profile-bed.yml`, then the
`ENDPOINT` lines, then `the store's password: from the environment variable <PASSWORD_VARIABLE>`, then per schema
`schema <tier> is served: 6 of 6 bean(s) running`. The page has the rest: what a start refuses, the rights, every key.

## 10 · For the next rehearsal

The kit can drop three workarounds:

| workaround | now |
|---|---|
| (6) start `btcl/lab` and override it with `-D` | put a profile of its own root in a directory — `<run>/ss/config/tenants/<root>/r1/profile-r1.yml` — and start with `SUMMARY_CONFIG_DIR=<run>/ss SUMMARY_ACTIVE_TENANT=<root>/r1` (or start in that directory). The first line says the file |
| (7) start without the lab key and judge the endpoints itself | put `summary.endpoints.local-only: true` into that profile. A prime-context on an address of the namespace's own interface is `THIS-BOX`. The listener must be bound to one address (`-Dquarkus.http.host=127.0.0.1`); `0.0.0.0` is refused |
| (2) the fourth topic | the profile names `ping-topic: cdr_summary_ping_<root>`; the kit makes that topic, and gives billing-core the same name |

Since S13 a summary row is one CONTENT of a campaign: a comparison of the kit that counts `sum_ad_*` rows per key must
group the `cdr` rows by `additionalMetaData.contentId` too (my reconcile script does). A kit database that still has the
tables of 92fae7d is brought up by the service at its start: two WARN lines per ad table and tier say it.

The `ENDPOINT` line's last word changed: `LOOPBACK`, `THIS-BOX`, `NOT-THIS-BOX`, `NOT-SET` (it was `LOOPBACK` / `NOT-LOOPBACK` / `NOT-SET`).
A refusal in a print-only run is one line that starts with `REFUSING TO START:`; the exit codes are 0, 3 (a host is not this box), 4 (the configuration refuses the start).

## 11 · The files

| | |
|---|---|
| this note | `docs/ad-as-call/SS-0002-done.md` |
| my first note | `docs/ad-as-call/SS-0001-update.md` |
| the page | `docs/ad-as-call/postgres-ad-profile.md` |
| the DDL | `src/main/resources/db/postgres/sum_ad.sql` |
| the decisions | `docs/decisions.md` §16 |
| the lab | `tools/lab/` — `pg-lab.sh`, `run-lab.sh`, `tree-e2e.sh`, `reconcile-with-billing-core.sh`, `secret-e2e.sh`, `this-box-e2e.sh`, `ping-topic-e2e.sh`, `upgrade-e2e.sh` |
| the tables' columns | `README.md`, "The ad summary tables" |
| on the branch `no-bundled-tenant` only | `deploy/tcbl-tenants.yml.example` |

## 12 · S13 — the content in the key

| | |
|---|---|
| the column | **`tup_contentid`** |
| its type | **`VARCHAR(64) NOT NULL DEFAULT ''`** — the same text on MySQL and on PostgreSQL (`character varying(64)`) |
| its value | the record's `additionalMetaData.contentId`. Blanks around it are cut off; a JSON number is read as its digits; longer than 64 is cut to 64. `''` when the record carries none: a refused view, a house ad, no meta data. Never NULL |
| in the row's KEY | the 4th of its 11 parts, right after the campaign: tier, payer, campaign, **content**, rule code, zone, site, app, media kind, outcome, the window's start. One row per (the key as before, content) |
| what the key is | the summary engine's key, the same on both engines: a drain loads the rows of the windows it touches once, merges by the key in memory, inserts new keys and updates the loaded rows by `id` — one writer per table, the rows and the bookmark in one transaction. **No `UNIQUE` index carries the key on either engine** (the primary key is `(id, tup_starttime)`), before and after S13. I added none: say if you want one as a safety net |
| in the TABLE | the LAST column, on both engines. A column added by `ALTER` can only be appended on PostgreSQL, so a table that got it later is the same table as one made with it. `AdSummary.INSERT_COLUMNS` ends with it |
| the measures | as they were. Money and units stay two measures, per content |
| the reader | it reads columns by name: nothing breaks. `tup_contentid` is there to select and to group by |

**An EXISTING table (a deployment that has rows).** The service does it itself, at the table's first use after the upgrade:

1. it reads the table's columns from the database's catalog (`information_schema.columns`, in the schema it works in);
2. a column the description has and the table lacks is added: `ALTER TABLE sum_ad_day_30 ADD COLUMN tup_contentid VARCHAR(64) NOT NULL DEFAULT ''`
   (PostgreSQL: `ADD COLUMN IF NOT EXISTS`), in the same transaction as the `CREATE TABLE IF NOT EXISTS`;
3. a WARN line says each change. The rows that are there read `''` as their content and keep their id and their numbers:
   **history is not rebuilt**. A view with no content goes on in such a row; a view of a content opens that content's row.

It is idempotent: a table that is as described gets no statement and no lock, so the step runs at every start and changes
nothing the second time (tested). It applies to the ad tables only (net-new, the service's alone). The voice deployment's
`sum_voice_*` and `sum_chargeable_*` are never looked at: a legacy system also writes them. By hand it is the `ALTER` under
each table in `db/postgres/sum_ad.sql`. The service owns its tables on PostgreSQL, so it needs no new right; on MySQL it
needs `ALTER` on the database — only when a table really lacks something. A table somebody made by hand as ANOTHER role is
not the service's to alter: the database refuses, that tier's ad bean does not start (an ERROR, tried again), and the two
statements are run by the table's owner.

The tests you asked for, by name:

| | where |
|---|---|
| two contents of one campaign in one hour → two rows, each with its own views and charges, their sum what the campaign's one row was | `AdSummaryBeanTest.two_contents_of_one_campaign_in_one_hour_are_two_rows_and_their_sum_is_what_the_campaigns_one_row_was`; on both engines `…two_rows_and_a_reread_of_the_same_outbox_rows_changes_nothing` |
| a record with no content → the empty-string row | `AdSummaryBeanTest.a_record_with_no_content_is_the_empty_string_row`; on both engines `a_view_with_no_content_is_the_empty_string_row_beside_the_contents_rows` |
| a re-read of the same outbox rows changes nothing | the same contract test: a second drain is 0; a crash at the commit leaves nothing; the replay updates the two rows that are there, never a second row for a content |
| against billing-core's own rows | `tools/lab/reconcile-with-billing-core.sh`, the line "per content": SAME |

## 13 · S14 — a value is never wider than its column

**What a box would have met with the 20-wide `tup_rulecode`: nothing failed.** The builder cut the called number to 20
characters BEFORE the key was taken (it always cut every text of the key to its column). So the row was stored and merged
correctly; no outbox row failed, no bean stopped, nothing was dead-lettered. `tup_zone` (64) is in the key too, so no two
zones merged. The cost was only this: the rule code column held the zone's first 20 characters (`dhaka-north-mirpur-1`).
I saw exactly that in the test, red, before the change.

| | |
|---|---|
| now | `tup_rulecode` is `VARCHAR(64)` on both engines, in both tables. A 64-character zone as the called number is stored whole |
| the rule | every text of the ad key has ONE width, used by the builder's cut and by the table's description (`AdSummaryBuilder.*_WIDTH`): they cannot drift apart. Each is its source's full width: zone, site, app, content id 64; the called number 64; the media kind 16 and the outcome 32 as they were (the switch's short words; `done` / `failed`) |
| a wider value | is cut to its column by the service, never refused by the database. 200 characters in every text, on both engines: stored, merged into one row by the next batch, nothing dead-lettered |
| an existing table | widened by the step of §12: PostgreSQL `ALTER TABLE … ALTER COLUMN tup_rulecode TYPE VARCHAR(64)`; MySQL `ALTER TABLE … MODIFY COLUMN tup_rulecode VARCHAR(64) NOT NULL DEFAULT ''`. What the column holds stays. Never narrowed |

What my code does when a value IS wider than its column — it cannot be, after the step above; it would need a column
narrower than the cut, for example a table the service was not allowed to `ALTER`:

| engine | what happens |
|---|---|
| PostgreSQL; MySQL in strict mode (the rule is the engine's, held by the contract's tests of a failing statement) | the `INSERT` fails at the SQL layer. That drain step is rolled back, the bookmark stays, and the worker tries again — it backs off up to 60 s and logs an ERROR with a rising count each try. **The bean of that tier stops at that outbox row. It is never dead-lettered** (an SQL failure may be transient, so it is not poison: decisions §13c). The other beans and tiers go on. That tier's outbox is not trimmed past the row until the bean passes it |
| MySQL not in strict mode | by MySQL's own rule (not run here: my lab's MySQL is strict, the default) the server cuts the value with a warning. The stored row would then key apart from the next batch's built row: a second row for the same key. The sums stay right when grouped; the shape is wrong. One more reason the widths are one number |

## 14 · S15 — the jar enables no tenant (PREPARED, not decided)

| | |
|---|---|
| the branch | `no-bundled-tenant`, pushed. **ONE commit** on the code head of `postgres-ad-call`. Not merged, as you said |
| its head | c2f9127 |
| its suite | 294 unit + 70 IT, 0 failed, 0 skipped (a clean clone of that commit) |
| (a) | the jar's `config/tenants.yml` enables no tenant. It lists the profiles the jar carries (`tcbl/dev`, `tcbl/prod`, `btcl/lab`), each `enabled: false` |
| (b) | a start that names no tenant is refused before anything is dialled, with the workers on or off: `REFUSING TO START: this start names no tenant, and no registry enables one (the jar's own enables none). A deployment names its tenant in its unit — SUMMARY_ACTIVE_TENANT=<tenant>/<profile> (or -Dsummary.active-tenant=…) — or in a file: config/tenants.yml with one entry 'enabled: true', in the deployment's configuration directory. That directory is the one SUMMARY_CONFIG_DIR (or -Dsummary.config.dir) names, else the working directory…`. print-only says it and exits 4 |
| (c) | `deploy/tcbl-tenants.yml.example`: the voice deployment's registry, with where it goes on the box (`<the service's configuration directory>/config/tenants.yml`) and when (BEFORE the first build that enables no tenant), or the one line of the unit that does the same (`SUMMARY_ACTIVE_TENANT=tcbl/dev`). No deployment and no deploy script is touched |
| (d) | the README ("Configuration"), the page and decisions §16k say the same |
| what stays | the PROFILES are still in the jar: a deployment that names `tcbl/dev` runs as before; the lab names `btcl/lab` |
| the build | names no tenant and is not a start: nothing is said or refused while packaging. The unit tests name theirs in the pom (`summary.active-tenant=tcbl/dev`; they dial nothing) |
| with the packaged jar | no tenant named: refused, exit 1 (print-only: exit 4), the `PROFILE` and `ENDPOINT` lines first, nothing dialled. `SUMMARY_ACTIVE_TENANT=btcl/lab`: as before. The example file as a directory's registry: `tcbl/dev` (print-only) |
| before it is deployed for the voice tenant | its unit must name `tcbl/dev`, or its configuration directory must hold `config/tenants.yml`. Forgotten = the service does not start and says why in its first lines |
| the bed | needs nothing: its unit names `btcl/bed` (§9) |
