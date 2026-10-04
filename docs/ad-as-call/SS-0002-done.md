from: SS        to: ARCH        kind: done        number: 0002
date: 2026-10-04T11:59+06:00        branch: postgres-ad-call (from master ec2546e)        head: 4cb77a1 (the code; this note is the commit after it, pages only)
subject: S1–S9 of the brief are built, and S10–S12 from the first rehearsal — the suites, the breaks, what I assumed, what I could not do, what is not mine to fix, and the template of the bed profile

# SS-0002 · done — the ad category on the `cdr` stream, PostgreSQL, the whole tree

## 0 · What to relay

| | |
|---|---|
| the branch | `postgres-ad-call`, pushed. `master` is not touched. The full suite was green on the head of every push. One head was pushed before its full run (aeb53d4: the unit suite only); the full suite ran on it right after, green |
| S1–S9 | done, each with tests (§1) |
| S10–S12 (R-0001) | done: a profile from outside the jar, the lab key means "this box", the ping's topic carries its root (§1) |
| the suite | before (master ec2546e): 96 unit + 8 MySQL IT. Now: **270 unit + 57 IT, 0 failed, 0 skipped** — on MySQL 5.7.44, PostgreSQL 16 and Kafka 3.9, all on 127.0.0.1 (§2) |
| the breaks | **249 rules** broken once in a clean clone: green before, red after, restored with `git checkout`. 5 did not go red at first — real gaps: their tests were sharpened and then seen red (§3) |
| the DDL | `src/main/resources/db/postgres/sum_ad.sql` (18 columns: the 17 of MySQL and `chargedunits`; plain tables) |
| the page | `docs/ad-as-call/postgres-ad-profile.md` — the profile of a PostgreSQL tenant with the ad beans, for the person who deploys |
| the bed profile | §9: a template of `btcl/bed`. Every address and every secret is a NAME to fill |
| against billing-core's own rows | its lab command (64334ac) run from my clone on MY lab, then my jar: every line SAME (§4) |
| for the window | the two topics must exist before billing-core starts; the unit names its tenant and its password's variable (§9) |
| your call | three small things I did not decide alone (§8, D1–D3) |

## 1 · What was built

| # | what | held by |
|---|---|---|
| S1 | The ad beans read the `cdr` outbox stream. They keep the entries whose `Cdr.ServiceGroup` is 30. Dimensions and measures come from the `Cdr` and from the JSON in `AdditionalMetaData`. The `ad_cdr` entity and its models are gone | `AdSummaryBeanTest`, `AdSampleMessageTest` (the brief's sample message), the contract test `the_ad_beans_read_the_cdr_stream…` on both engines |
| S2 | The tables keep their columns. `tup_tenant` is always the schema's own name (the drain passes the tier to the bean). ad-sphere's reader needs only the schema | `AdReaderContractTest`; `PostgresOutboxConsumerIT.the_summary_tables_are_plain_tables_and_ad_spheres_reader_sees_and_reads_them` |
| S3 | `tup_app` is 64 wide, in the DDL of both engines and in the builder's cut | `AdSummaryBeanTest.an_app_name_past_the_column_is_cut_to_64…`, `AdReaderContractTest` (the two engines give a column the same shape) |
| S4 | The call and the chargeable categories take group 30 from the profile. The call bean needed a branch (it threw on group 30). No `ChargingStatus` early return there, as you ruled | `CallSummaryGroup30Test`, `ChargeableSummaryGroup30Test`, `WifiProfileTest` |
| S5 | PostgreSQL as the store, chosen per profile at run time (`summary.store.kind`). One jar serves either engine. One table description, two renderers; plain tables on PostgreSQL, no partitioned form (ruled). Exactly-once and crash-replay hold on PostgreSQL | ONE contract, word for word on both engines: `OutboxConsumerIT` (17, MySQL), `PostgresOutboxConsumerIT` (the same 17 + 8 of its own) |
| S6 | Every tenant of the tree in one process: a worker and a bookmark per (schema, bean). The tree comes from prime-context (`POST /get-specific-tenant-root`). The doorbell `config_event_loader_<root>` and a timer read it again. Tables are made at a schema's first use. A reseller made at run time is served with no restart | `PostgresTreeIT` (9), `MySqlTreeIT` (2), `TenantWatcherTest` (11), `SummaryBeanRegistryTest`, `KafkaListenersIT`; `tools/lab/tree-e2e.sh` |
| S7 | A schema seen for the first time starts every bean at offset 0. On a schema already served, a bean switched on later starts at the outbox head. Decided once per schema, before its workers start | `SummaryBeanRegistryTest` (4 tests), `PostgresTreeIT.billing_writes_first_summary_starts_second…` |
| S8 | The ping `{tenant, entity, rows}` wakes the workers of the tenant it names. The poll stays. Measured: a view is in the summary 25 to 111 ms after its ping (the IT, six suite runs today); 268 ms in the lab story, which asks every 200 ms | `SummaryBeanRegistryTest` (5 tests), `KafkaListenersIT`, `PostgresTreeIT.a_view_is_in_the_summary_within_a_few_seconds_of_its_ping` |
| S9 | `summary.store.password-ref: env:NAME`. A named variable that is not set refuses the start, in words that name it, before anything is dialled. The value is never printed. The inline form stays. A password in the URL is refused and hidden in every form | `StoreConfigTest` (17), `UrlSecretsTest`, `SummaryBootstrapTest`; `tools/lab/secret-e2e.sh` |
| S10 | A profile from OUTSIDE the jar. One key names the directory that holds `config/tenants/<tenant>/<profile>/profile-<profile>.yml`: `-Dsummary.config.dir` or `SUMMARY_CONFIG_DIR` (not named: the working directory). A file there wins; else the jar's. The start's first line says which file was read | `WifiProfileTest` (10 tests of it), `SummaryBootstrapTest` |
| S11 | The lab key means "this box": `localhost`, a loopback address, or an address one of this box's own interfaces holds. Another box's address still refuses the start, in words, before anything is dialled. The key is `summary.endpoints.local-only`; its first name `summary.endpoints.loopback-only` is the same switch | `StartEndpointsTest` (15), `SummaryBootstrapTest`; `tools/lab/this-box-e2e.sh` |
| S12 | No code. On a deployment the ping's topic carries the root: **`cdr_summary_ping_<root>`, the same name on both sides** (billing-core's `billing.summary.ping-topic`), **and the topic must exist**. Said in the README, the page and decisions §16i. The lab profile and the lab scripts use `cdr_summary_ping_btcl` | `WifiProfileTest.the_ping_topic_of_a_deployment_carries_its_root`; `tools/lab/ping-topic-e2e.sh` |

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
| now — the head above | 270 | 57 | 0 | 0 |

The 57: the build guard 1, the Kafka listeners 3, the MySQL tree 2, the contract on MySQL 17, the contract on PostgreSQL 25, the PostgreSQL tree 9.
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
| **all** | | **249** | **238** | 6 my harness, 5 real gaps — all 11 seen red after |

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

The stories with the PACKAGED jar, all run again on the head above:

| script | what it shows | result |
|---|---|---|
| `tools/lab/tree-e2e.sh` | billing-core's tables from ITS OWN DDL file, rows written as it writes them, the tree btcl > res_44, a reseller made at run time | a view in both schemas 268 ms after the last ping; `res_45` served 1,027 ms after the doorbell, no restart; `DELETE` on `cdr` and on its partition refused; the outbox is billing_core's |
| `tools/lab/reconcile-with-billing-core.sh` | the summaries beside billing-core's OWN rows | every line SAME. btcl: 44 views, 41 shown, 3 failed, 310 s, money 16.8. res_44: 42 views, money 16.0, units 10.0 |
| `tools/lab/secret-e2e.sh` | S9 against a role that needs a password | refused without the variable (exit 4 / 1); a wrong value: the store refuses, the service stays up and tries again; the right value: served. The value is in no log and on no command line |
| `tools/lab/this-box-e2e.sh` | S11 | 192.0.2.1: not started, the service's own start refused, nothing dialled. In a namespace whose loopback also holds 10.10.250.1: `THIS-BOX`, the tree is read from it. The same address outside: `NOT-THIS-BOX` |
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
| 10 | MySQL and `chargedunits`: the one table description gives the column on MySQL too, for a table made from now on. A `sum_ad_*` table made before (stream X's rehearsal, 17 columns, `tup_app` 32) needs one `ALTER` or a drop. No deployed MySQL tenant has group 30 |
| 11 | The tree is read again every 300 s (`summary.tenants.refresh-seconds`). It is the backstop for a ring that is missed (R-0001 finding 3) |

## 7 · What I could not do, and why

| # | what | why |
|---|---|---|
| 1 | S10–S12 on the real chain | built and proven on my lab after R-0001. The kit can drop its workarounds 6 and 7 now (§10). Its next run is the proof |
| 2 | the real prime-context in MY lab | my lab runs summary-service alone; a stand-in answers its one read road. Your rehearsal ran the real one on 92fae7d |
| 3 | a load or soak test | none. My runs are small: tens of views per story. The rehearsal's 4,479 views over 4 tiers is the largest seen |
| 4 | the unit file and the deploy tool's entries | not my lane. The page shows the unit's lines; §9 is the profile |
| 5 | a real secreteer file | the profile names a variable; I proved the path with a throwaway role and a random password in my own shell |
| 6 | ad-sphere's reader probe on the last head | run on fda182a (83 rows read by its real `JdbcCdrReader`); since then only the start changed, and the IT with the reader is in the suite |

## 8 · Not mine to fix, and three calls I leave to you

| # | finding | whose |
|---|---|---|
| F1 | ad-sphere's reader does not read `chargedunits` yet. The column is there | ad-sphere (known: F-U1) |
| F2 | billing-core's lab check: its `StartEndpoints.IsAnAddress` takes any four numbers of 1–3 digits and hands them to `InetAddress.getByName`. For `999.1.1.1` the JDK may look that text up as a NAME — a query that leaves the box. I read this in its code at 64334ac; I did not run it. Mine parses the numbers itself | billing-core |
| F3 | A missing topic makes the Kafka client log about one WARN a second, on my side too. It is loud on purpose; the fix is the topic | the window's list |
| F4 | prime-context's W12 (`summary_service` SELECT only by default): I did not see it land. It does not matter to me: my reaper deletes from `summary_affected` only, and billing-core's own DDL revokes the rest (seen: `DELETE` on `cdr` refused) | prime-context |
| F5 | A poison outbox row goes to `summary_affected_dlq` after 8 tries, with one ERROR line. Nobody is paged | operations |
| F6 | One thread per (schema, bean), and a pool of 16. A worker holds a connection for one transaction only. Fine at 4 tiers; a tree of hundreds wants a number first | a later decision |

| # | your call | what I did until you say |
|---|---|---|
| D1 | The jar's `config/tenants.yml` enables `tcbl/dev`, whose profile names CCL's database, broker and config-manager. A start that names no tenant falls on it. With the workers off nothing is dialled; with `summary.autostart=true` and no tenant named, it would dial them. I recommend: no tenant enabled in the jar. I did not change it: it is the voice deployment's default | the start's first line now says `THE JAR'S OWN config/tenants/tcbl/dev/…`, and a lab start refuses it |
| D2 | `tup_rulecode` is 20 wide; with no rule the switch puts the zone there (up to 64). No rows merge by it (`tup_zone` is in the key) | left |
| D3 | The key's default `cdr_summary_ping` (no root) | left: S12 says no code, and it is the voice deployment's name |

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
| the lab | `tools/lab/` — `pg-lab.sh`, `run-lab.sh`, `tree-e2e.sh`, `reconcile-with-billing-core.sh`, `secret-e2e.sh`, `this-box-e2e.sh`, `ping-topic-e2e.sh` |
