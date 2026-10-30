from: SS  to: ARCH  kind: done (DRAFT — a State line after every item)  number: 0009  date: 2026-10-31  branch: `ad-pair` (from `postgres-ad-call` ff5d945)  ·  the brief: ~/c-work/briefs/ARCH-0102-ad-billing-pair.md

# SS-0009 — summary-service for the AD category alone: the `ad_services` store, kinds = ad, the beans sum_ad_* + sum_chargeable_*, the groups named by category

The owner's ruling (routesphere `docs/architecture/two-db-two-tenant-organisation.md` §1, §3, §5, §6, §7 row 5): one billing pair
per service category, each on its category's own records database. This round = the AD pair's summary-service. The billing-core side:
telcobright-billing-core `docs/ad-as-call/BC-0010-done.md` (its §1 holds the read list of the other half, F1–F3, R1 and D1).

## State

| item | state | stamp |
|---|---|---|
| 1 the read list | DONE — §1 below (the table; no non-record read exists; D2 the chargeable pair under kinds = ad) | 2026-10-31 02:01 +0600 |
| 2 (billing-core's; BC-0010) | — | |
| 3 summary-service on the services store, kind ad | DONE — §3 below (the profile btcl/ad-radius2; summary.category names the groups; a cross-kind reader starts under any kind (D2); 4 test classes touched/new, fast runner 58/58) | 2026-10-31 02:41 +0600 |
| 4 the proof: two categories, two databases, two topics | DONE — §4 below (continued by a fresh agent after the stop of ~03:05; the harness is billing-core's `java/tools/lab/ad-pair-e2e.sh`, 2e8fe7d): the run of record **HOLDS 41 ok / 0 FAIL**, this service's checks all ok; mutants red — fast runner MS1 3/4, MS2 1/16, MS3 1/5 (base-ss 20/20 green); the worktree never held a mutant, every touched file = HEAD by cmp | 2026-10-31 03:58 +0600 |
| 5 the gates and the bed jar | DONE — §5 below: a clean copy of 32990ce, `mvn -o verify` on the private base alone: **unit 481 · 0 · 0 · 0, ITs 96 · 0 · 0 · 3** (both engines); the bed jar `~/artifacts/summary-service/32990ce-pinned/` (sha256 `7d4cb81f…c736`), its base proven by bytes (116 of 116 libraries, 0 in-house); the e2e on its own bytes **HOLDS 41 / 0**. §6: the rulings received, 3 open points | 2026-10-31 04:51 +0600 |

## 1 · Item 1 — the read list: every table and topic summary-service names, by road, and today's source

The tree of ff5d945 (`src/main`), read whole: every SQL literal, every Kafka client, every HTTP road.

| road / worker | table (per tier schema) or topic (+ group) | today's source (radius2) | the AD pair |
|---|---|---|---|
| the outbox reader — `runtime/internal/JdbcOutboxStore` (each bean's worker drains from its own bookmark) | READS `summary_affected` (`entity_type`, `id > bookmark`); the bookmarks `summary_offset` (SELECT … FOR UPDATE, INSERT, UPDATE; the first bookmark seeded at the entity's `max(id)`); the dead letters INSERT `summary_affected_dlq`; the catalog: `to_regclass('summary_affected')` | the switch database `routesphere`, schema = the tier | **`ad_services`**, schema = the tier (`btcl`, `res_*`) |
| the reaper — `outbox/internal/OutboxReaper` (HARDEN-1: each entity that has beans, by its own beans' minimum, bounded batches) | DELETES `summary_affected` (`entity_type`, `id <= min(last_offset)`); READS `summary_offset` | same | same — the entities with beans here: `cdr` only (no `wifi_flow` bean) |
| the beans' tables — `engine/internal/SummaryCache` + `runtime/internal/JdbcSummaryStore` (SELECT the window's rows, INSERT / UPDATE / DELETE), `bean/spi/TableDdl` (CREATE TABLE / INDEX IF NOT EXISTS), `JdbcSummaryStore` (the catalog: `information_schema.columns`) | `sum_ad_day_30`, `sum_ad_hr_30` (the ad pair); `sum_chargeable_day`, `sum_chargeable_hr` (the chargeable pair); on radius2 also `sum_voice_day/hr_30`, `sum_voice_day/hr_101`, `sum_wifi_day/hr_101`, `sum_wifi_dst_day/hr` | same | **`sum_ad_*` and `sum_chargeable_*` only** (the brief's item 3) — in `ad_services` |
| the infrastructure DDL — `outbox/internal/OutboxInfraDdl` | `summary_offset`, `summary_affected_dlq` (and the MySQL dev outbox — not on PostgreSQL) | same | same |
| the ping — `ping/internal/PingListener` (a consumer; wakes the tenant's workers) | topic `summary.outbox.ping-topic`; group `summary-ping-<uuid>` (a fresh id per start = broadcast; `auto.offset.reset=latest` — a ping is a wake-up, the poll drains regardless) | `cdr_summary_ping_btcl` | **`cdr_summary_ping_ad_btcl`** (BC-0010 D1) / group **`summary-service-ad-ping-<uuid>`** (item 3: `summary.category`) |
| the doorbell — `tenancy/internal/DoorbellListener` (a consumer; re-reads the tree) | topic `<topic-base>_<root>`; group `summary-doorbell-<uuid>` (broadcast, latest) | `config_event_loader_btcl` | `config_event_loader_btcl` (prime-context rings ONE topic per root) / group **`summary-service-ad-doorbell-<uuid>`** |
| the tree — `tenancy/internal/PrimeContextTreeSource` (tree mode: the tier schemas to serve) and `context/internal/ConfigManagerClient` (a bean's `summary.contexts.<name>`, source config-manager — none on the bed) | `POST /get-specific-tenant-root?name=<root>` | the bed's prime-context facade :7091 | the same road, the same facade (HTTP; not a table) |
| a producer | none — summary-service publishes nothing | | |

**The reads of a NON-record table: none.** summary-service reads no partner, partnerprefix, rate or package table — it sums the blobs
billing-core wrote into `summary_affected` (`CdrBlobEntry`: the cdr row and its chargeables as written) and keeps its own sums. Every
table above is a record table of the tier schema; every one moves to `ad_services` with the datasource (`summary.store.url`).

**D2 — the chargeable pair under `kinds: [ad]`.** The brief's item 3 wants *"the sum_ad_* and sum_chargeable_* beans only"* with
`summary.kinds = ad`. Today (SS-0008 D3) the chargeable roll-up is the CALL kind's bean (`CallKind.owns(ChargeableSummaryBean)`) and a
bean of a kind not enabled REFUSES the start (D1) — so an ad-only profile with the chargeable pair cannot start on ff5d945. Its input
crosses the kinds by design (`bean/spi/CrossKindReader`: told the enabled kinds' groups, it sums no leg of another group). Item 3 therefore
makes a cross-kind reader a bean of EVERY enabled kind: it starts whenever the tenant enables any kind and sums the enabled kinds' legs
only (here: group 30's) — the ownership for the catalog (the call's package) is unchanged, the pins of a voice tenant are unchanged.
Taken unless you rule otherwise (your D3 said "or rule otherwise"; the brief rules it).

## 3 · Item 3 — summary-service on the services store, kind ad (as built)

| where | what |
|---|---|
| `src/main/resources/config/tenants/btcl/ad-radius2/profile-ad-radius2.yml` (NEW) | the AD category's profile on the bed, the radius2 shape with the contract's values: `store.url` → `ad_services` (127.0.0.1:5434 on the box), `password-ref: env:TENANT_BTCL_AD_SUMMARY_SERVICE_PASSWORD`; `kinds: [ad]`; `category: ad`; the beans `dailyAdSummary`, `hourlyAdSummary`, `dailyChargeableSummary`, `hourlyChargeableSummary` and NO config-instantiated bean (no call-shaped 30/101, no WiFi bean); `outbox.ping-topic: cdr_summary_ping_ad_btcl` (BC-0010 D1); the tree and the doorbell the root's (`config_event_loader_btcl`); the reaper as HARDEN-1 built it (the `cdr` entity, the four beans' minimum). `profile-radius2.yml` (the pre-split bed) untouched |
| `registry/internal/ServiceCategory` (NEW) | `summary.category` → the consumer groups `summary-service-<category>-ping-<uuid>` / `summary-service-<category>-doorbell-<uuid>`; unique per start as before (broadcasts); none named = the names of before |
| `ping/internal/PingListener`, `tenancy/internal/DoorbellListener`, `tenancy/api/TenantWatcher` | take the category (the CDI constructor reads `summary.category`; the old constructors kept = no category); their start lines say the group: `ping listener started: topic=… group=…`, `doorbell listener started: topic=… brokers=… group=…` |
| `registry/internal/SummaryBootstrap` | D2: `refuseBeansOfKindsNotEnabled` passes over a `CrossKindReader` (the chargeable roll-up: a bean of every enabled kind); the `KINDS` line names such a bean when its own kind is not enabled; the print-only road the same |
| tests | NEW `config/internal/AdPairProfileTest` (5: the store `ad_services` + the password by name, the kinds / category / beans, the ping and the root's tree + doorbell, nothing of the pre-split store or ping shared, no secret value in the file); NEW `registry/internal/CategoryNamedGroupsTest` (4: the names of before without a category; a category names the groups, unique per start; both listeners; the watcher hands the profile's category to its doorbell); `ProfileKindsTest` +1 (the ad pair's profile starts: `ad` alone, its four beans); `SummaryBootstrapKindsTest` +1 (an ad-only tenant with the chargeable roll-up starts, and the roll-up sums the view's leg alone — the call's two legs not) |
| docs | decisions §20; the profile page: the `summary.category` key row and §8 (one summary-service per category) |

Fast runner (`~/adpair-work/jt-ss.sh`, NOT Maven) on the touched and neighbouring classes (`SummaryBootstrapKindsTest`,
`ProfileKindsTest`, `CategoryNamedGroupsTest`, `AdPairProfileTest`, `KnownKindsTest`, `TenantKindsTest`, `SummaryBootstrapTest`,
`TenantWatcherBearerTest`): **58 found, 58 passed, 0 failed** (02:34 +06, after the owner's model switch; one compile fault of the
print-only road found by the first run and fixed). Maven's numbers of record: item 5.

**Stops.** The owner's model switches stopped the round twice (02:0x, 02:3x); nothing was running at either stop but my own
background package of billing-core (it had finished, exit 0, 02:21); no mutant had been applied; item 3 was in the worktree
uncommitted and is this commit.


## 4 · Item 4 — the proof: two categories, two databases, two topics

ONE harness proves the pair: billing-core's `java/tools/lab/ad-pair-e2e.sh` (billing-core `ad-pair` 2e8fe7d; its report BC-0010 §4
holds the whole table, the lab and the harness's own faults found by its first runs). This service runs there as the bed will run it:
the profile it SHIPS (`btcl/ad-radius2`), only the addresses moved to this box (the store url → the lab's `ad_services`, the broker,
the prime-context stand-in; the lab belt `summary.endpoints.local-only` on) — the profile run = the jar's own resource (a check:
EQUAL), started by `tools/lab/run-lab.sh btcl/ad-radius2` (the tenant named; the endpoints shown first, every host this box; the
bundled tcbl never reached). Beside it: the ad billing-core on `cdr_ad_btcl` → `ad_services`, and a WiFi category's billing-core on
`cdr_wifi_btcl` → `wifi_services`.

**The run of record** (2026-10-31 03:54:07–03:56:51 +06; this service = the package of 32990ce): **HOLDS — 41 ok, 0 FAIL**
(`~/c-work/gates/adpair-e2e-run4.log`). This service's checks:

| what | the check (each `ok`) |
|---|---|
| the start says the category | `KINDS ad (summary.kinds) — ad = service group 30; dailyChargeableSummary, hourlyChargeableSummary = every enabled kind's legs`; `ping listener started: topic=cdr_summary_ping_ad_btcl group=summary-service-ad-ping-<uuid>`; `doorbell listener started: topic=config_event_loader_btcl … group=summary-service-ad-doorbell-<uuid>` |
| the summaries sum from `ad_services` alone | `sum_ad_day_30` btcl 1 view / 0.40, res_44 1 / 0.50; `sum_ad_hr_30` 1 + 1; `sum_chargeable_day` group 30 alone, one leg per tier (D2: the cross-kind reader under `kinds: [ad]`); the four beans' bookmarks, one offset per tier; `wifi_services` holds no table of this service (`sum_*`, `summary_offset`, `summary_affected_dlq`); the role `summary_service` connected to `ad_services`, never to `wifi_services`; the reaper trimmed the `cdr` entity's outbox rows every bean has passed (HARDEN-1, both tiers 0 left) |
| the groups | its two groups on the broker in the category's names; none of the pre-split `summary-ping-*` / `summary-doorbell-*` |
| one ring, every category | ONE ring of `config_event_loader_btcl` → the tree read again by billing-core (ad), billing-core (WiFi) AND this service, each in its own group |
| a restart | stopped and started again with its billing-core: the sums count each view ONCE after both restarts (btcl 2 / 0.80, res_44 2 / 1.00; chargeable 2 legs per tier) — a drain cut by the stop (F1 below) is drained again, never twice |

**F1 — ERROR lines at a stop (pre-existing; not this round's).** A worker's drain in flight when the pool closes logs "drain failed —
offset STUCK … SummaryStoreException: commit failed … Socket closed [Error Occurred After Shutdown]" (seen 4 times in run 3, 0 in run
4: it depends on whether a 1-second poll is in flight at the stop). The transaction rolls back WITH its bookmark (one transaction), so
the next start drains it again — the restart check above. Harmless, but a log watcher that alerts on ERROR fires at a planned stop. A
follow-up if wanted (stop the workers before the pool closes) — `OutboxWorker` is untouched by this round.

**The mutants** — each ONE break in its OWN fresh copy of the tree (`~/adpair-work/mutants.py`, rsync of the worktree without `.git`
/ `target`); the worktree never held one; after the round every touched file is byte-equal to the commit's (`cmp` with
`git show HEAD:<file>`) and `git status` is clean. Fast runner = javac + the JUnit launcher (NOT Maven); Maven's numbers: item 5.

| mutant | the break | fast runner (the named tests) |
|---|---|---|
| base-ss | none: `CategoryNamedGroupsTest`, `SummaryBootstrapKindsTest`, `ProfileKindsTest`, `AdPairProfileTest` | **20 found, 20 passed** |
| MS1 | the category ignored: the consumer groups of before (`summary-ping-<uuid>`, `summary-doorbell-<uuid>`) | **RED 3 of 4** — `a_category_names_the_groups_and_every_start_gets_its_own_group_still`, `the_ping_listener_and_the_doorbell_listener_join_their_categorys_groups`, `the_tenant_watcher_hands_the_profiles_category_to_its_doorbell` |
| MS2 | a cross-kind reader refused again under a kind it is not of (the chargeable pair cannot start with `kinds: [ad]`) | **RED 1 of 16** — `an_ad_only_tenant_with_the_chargeable_roll_up_starts_and_the_roll_up_sums_the_ad_legs_alone` |
| MS3 | the ad pair's profile names no category (its groups the names of before) | **RED 1 of 5** — `the_kinds_the_category_and_the_beans_are_the_ad_categorys_alone` |

The brief's three mutants are billing-core's (the ingest group left old, `kinds` left `ad, wifi`, partnerprefix from the table): BC-0010
§4 — all red there, in the fast runner and in this e2e. Logs: `~/adpair-work/mut/<id>.log`, `~/c-work/gates/adpair-e2e-*.log`.

## 5 · Item 5 — the gate and the bed jar

**Step 0, the fill** (shared with billing-core; BC-0010 §5): the private base `~/adpair-work/m2-ss` held no surefire JUnit-platform
provider (the first agent's package ran `-DskipTests`); one hold of the lock (04:16:22–04:18:13 +06) ran ONE test class
(`AdPairProfileTest`) and ONE IT (`BuildBakesNoTenantIT`) online against this box's own `~/.m2/repository` only (the `file://`
mirror): 2426 → 2459 files; 0 files written into `~/.m2`.

**The gate** — in ONE hold of the lock with billing-core's (`~/adpair-work/gate-and-jars.sh 2e8fe7d 32990ce`, 04:20:19–04:33:33 +06):
a git-archive clean copy of **32990ce** (all of the round's code; the commits after it add only reports), `mvn -o verify` on the
private base `m2-ss` ALONE (no tail; the pom pins no in-house artifact), both engines.

| run | tests · failures · errors · skipped |
|---|---|
| the baseline — HARDEN-2's gate of 32e8056 | unit 470 · 0 · 0 · 0; ITs 96 · 0 · 0 · 3 |
| **unit** (surefire) | **481 · 0 · 0 · 0** — 481 = 470 + this round's 11 (`AdPairProfileTest` 5, `CategoryNamedGroupsTest` 4, `ProfileKindsTest` +1, `SummaryBootstrapKindsTest` +1) |
| **ITs** (failsafe), PostgreSQL 16 (c-pg16 127.0.0.1:7544, `adpair_ss_it`) + MySQL 5.7.44 (`adpair-mysql57` 127.0.0.1:7642) | **96 · 0 · 0 · 3**, BUILD SUCCESS — PostgreSQL 54 (`PostgresOutboxConsumerIT` 45, `PostgresTreeIT` 9), MySQL 38 (`OutboxConsumerIT` 36, `MySqlTreeIT` 2), `BuildBakesNoTenantIT` 1; the 3 skipped = `KafkaListenersIT` (pointed at a closed port on purpose: the lab broker was the e2e's alone, and the IT's `summary-ping-<uuid>` groups would have tripped the e2e's "no pre-split group" check), as in every gate of record — the listeners on a real broker are the e2e's checks |

The MySQL password reached Maven by NAME only (`SUMMARY_IT_MYSQL_PASSWORD` in Maven's environment from the owner's 0600 lab file):
lines of the item-5 logs that hold it — 0.

**The bed jar** — packaged in the same hold from its own fresh clean archive of 32990ce, ALONE on `m2-ss`, offline, no tail,
`-DskipTests` (`~/adpair-work/cut-artifacts.sh`), a Quarkus fast-jar as every earlier bed artifact of this repo:

| | |
|---|---|
| artifact | `~/artifacts/summary-service/32990ce-pinned/summary-service-32990ce-quarkus-app.tar.gz` |
| sha256 | **`7d4cb81fb133c64184dc83802b40370d33de60936fa8428e687fb97ef7cdc736`** (`SHA256SUMS`; check it on the box before the install) |
| the base, by bytes | in-house artifacts in the base: **0** (the pom pins none); the app's libraries (lib/boot + lib/main): **116, each byte-equal (sha256) to a jar of the base**, none in-house; `app/summary-service-1.0.0-SNAPSHOT.jar`: 158 classes, all under `com/telcobright/summary/`; the profile `config/tenants/btcl/ad-radius2/profile-ad-radius2.yml` in the jar = the commit's file (EQUAL) |
| the proof on its bytes | the two-category e2e run on THIS tarball (unpacked after `sha256sum -c`, under a clean archive of 32990ce for the lab tools), beside billing-core's tarball the same way: **HOLDS — 41 ok, 0 FAIL** (2026-10-31 04:34:11–04:38:53 +06, `~/c-work/gates/adpair-e2e-shipped.log`); item 4's run of record ran the same app classes byte for byte (158 of 158) |
| `BUILD.txt` | the recipe, the base proof, the gate, the proof, the start rule (NEVER bare: the jar's own `config/tenants.yml` enables tcbl/dev, a LIVE deployment — `SUMMARY_ACTIVE_TENANT=btcl/ad-radius2`, the secreteer variable `TENANT_BTCL_AD_SUMMARY_SERVICE_PASSWORD`, a port of its own), what must exist first (`cdr_summary_ping_ad_btcl`, `ad_services` with grants, the ad billing-core started first) |

## 6 · The rulings received, and what stays open

**Received** (the architect, `~/c-work/briefs/ARCH-0102b-continuation.md`, 2026-10-31 03:2x): **D2** ACCEPTED — a cross-kind reader is a
bean of every enabled kind; it sums the enabled kinds' legs only (as built in item 3, pinned, mutant MS2 red). **D1** (billing-core's)
ACCEPTED — the ping topic per category, `cdr_summary_ping_ad_btcl` on both sides (the WiFi pair's `cdr_summary_ping_wifi_btcl`).

**Open — for the architect / devops** (no code of this round waits on them):
1. **The bed's prerequisites** — `cdr_summary_ping_ad_btcl` on kafka-s2 (the profile: "it must exist"; a ping only cuts the
   latency — the outbox poll drains regardless, every 5 s); `ad_services` with the tier schemas and the grants for `summary_service` (W1, prime-context's
   create-or-adopt); the secreteer file with `TENANT_BTCL_AD_SUMMARY_SERVICE_PASSWORD`; the ad billing-core started FIRST.
2. **Beside the pre-split summary-service on radius-2** until the clean slate stops it (`radius2`: `routesphere`, twelve beans) — the ad
   pair's unit needs its own HTTP port and `SUMMARY_ACTIVE_TENANT=btcl/ad-radius2`. Devops's unit name and port.
3. **F1: the drain lines at a planned stop** — a follow-up round (stop the workers before the pool), or accepted as is? (BC-0010 F4 is
   billing-core's twin.)
