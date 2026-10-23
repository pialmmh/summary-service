from: SS        to: ARCH        kind: done        number: 0008
date: 2026-10-24 (the times below are +06)
branch: kinds (from postgres-ad-call 72d5875 + the brief a4ed2ab); the merge plan on master-merge (from master ec2546e)
subject: SS-0008 — one bean set per kind (call · ad · wifi), switched on per tenant by summary.kinds (the owner's R13; S17 of SS-0003 §5): the kind contract, the three kinds each one package, a bean of a kind not enabled refuses the start, the cdr rows of a kind not enabled summed by no bean and counted once (KIND_NOT_ENABLED, one WARN per tenant, kind and day); every existing sum pinned on both engines before and proven identical after; the postgres-ad-call → master merge plan (no conflict: master is an ancestor)

# SS-0008 · done — items 0–5

## 0 · What to relay

| | |
|---|---|
| the branch | `kinds`, not pushed: **2d0e226** item 3 (the pin harness, before item 1) · **bd7721a** item 1 · **a680760** item 2 · **e2c99dd** item 3 (the after-pins) · item 5 = the commit that carries this note (the docs: decisions §17, the README, the architecture page, the deploy page, the SMS placeholder's pointer). `postgres-ad-call` and `master` not touched |
| the merge plan | branch **`master-merge`** from `master` ec2546e, `kinds` merged `--no-ff`: **0 conflicts** — `master` is an ancestor of `kinds` (0 commits on master that kinds lacks, 68 the other way), so the merge's tree IS `kinds`' tree. What changes for anyone who runs master's jar: §6. The suite on the merge head: in its merge commit's message and the state file (it is made after this note). The merge into master is yours |
| item 0 | baseline, clean copy of a4ed2ab: unit **399/0/0/0**, ITs **86/0/0/3** (PostgreSQL 40 + 9, MySQL 31 + 2, the build check 1, Kafka 3 skipped — no broker in my lab, as SS-0007), `wifi-beans-e2e` **86 ok**, `wifi-gw-upgrade-e2e` **80 ok**, `wifi-gw-wayback-e2e` **96 ok**, 0 FAIL, 0 ERROR lines — SS-0007's numbers exactly (§1) |
| item 1 | DONE (§2). `bean/spi/SummaryKind` + `ServiceGroupReader`; `summarybeans/call/CallKind`, `ad/AdKind`, `wifi/WifiKind`; `registry/internal/KnownKinds`; the chargeable roll-up MOVED to `summarybeans/call/chargeable` (git mv + the package lines). Unit 409/0/0/0 (+10), ITs 86/0/0/3 |
| item 2 | DONE (§3). `summary.kinds` (none = `[call]`); a bean of a kind not enabled REFUSES the start (decision D1, §9); the chargeable roll-up reads only the enabled kinds' records; `KindGate` counts the others once (one counting bean per tier, after its commit) and says `KIND_NOT_ENABLED <kind> <n> rows` once per tenant, kind and day. The btcl profiles name `[call, ad, wifi]`. Unit **436/0/0/0** (+27), ITs **88/0/0/3** (+1 per engine) |
| item 3 | DONE (§4). The pins: one corpus of every record shape, 16 beans, both engines — **identical before and after** (sha256 `d71bdb69…`; PostgreSQL's digest = MySQL's, byte for byte). A voice tenant: its voice tables identical; its chargeable tables = before minus exactly their group-30/101 rows (the intended change of item 2). The e2e on the new jar: 86 / 80 / 96 ok |
| mutants | 12, each in its own fresh copy of the tree (the worktree never held one), every one red (§5) |
| to do before this jar runs on the bed | **the bed's rendered profile gains `kinds: [call, ad, wifi]`** (as `src/main/resources/config/tenants/btcl/radius2/profile-radius2.yml` now has it) — without it the start is REFUSED in words that name the ad and WiFi beans (D1). Nothing else: the same beans, tables, keys and sums |
| the blob contract | the `summary_affected` blob is read exactly as before (billing-core's BC-0009 shape untouched): the beans' readers did not change; the gate's probe is a separate reader of the same bytes |

## 1 · Item 0 — the baseline (SS-0007's way)

A clean copy of a4ed2ab (`git archive`), `mvn -o verify` under `flock /home/mustafa/telcobright-projects/.one-maven.lock`, the
head repository `~/ss8-work/m2-head` (EMPTY before and after every run of this round: 0 files) with the read-only tails
`~/.m2/arch-ss-review-repo`, `~/.m2/summary-service-repo`, `~/.m2/repository`. My labs: PostgreSQL 16.13 (docker `ss8-pg16`,
127.0.0.1:7437, trust — no password exists) and MySQL 5.7.44 (docker `ss8-mysql57`, 127.0.0.1:7346, root with no password);
no Kafka. 01:04:47–01:09:36 (+06), BUILD SUCCESS:

| | tests · failures · errors · skipped |
|---|---|
| unit | **399 · 0 · 0 · 0** |
| ITs | **86 · 0 · 0 · 3** — `PostgresOutboxConsumerIT` 40 + `PostgresTreeIT` 9 (PostgreSQL 49), `OutboxConsumerIT` 31 + `MySqlTreeIT` 2 (MySQL 33), `BuildBakesNoTenantIT` 1, `KafkaListenersIT` 3 skipped |
| `wifi-beans-e2e.sh both` (01:10–01:12) | **HOLDS, 86 ok, 0 FAIL**, 0 ERROR lines |
| `SS_OLDER_APP=<3829e18 packaged> wifi-gw-upgrade-e2e.sh both` (01:12–01:14) | **HOLDS, 80 ok, 0 FAIL** |
| `SS_OLDER_APP=… wifi-gw-wayback-e2e.sh both` (01:14–01:18) | **HOLDS, 96 ok, 0 FAIL** |
| the pins of a4ed2ab's jar (§4) | all (16 beans): sha256 `d71bdb69…` on both engines; call (6 beans): `d5f27833…` on both |

## 2 · Item 1 — the kind contract (bd7721a)

### 2.1 · As built

`bean/spi/SummaryKind` — a kind declares its **word** (`summary.kinds`), its **service groups** (whose `cdr` rows are its), its
**entities**, and its **bean set**: its catalog window classes (CDI singletons, by name), the **shapes** it config-instantiates
(`summary.beans.<name>.kind`), and the beans it **owns**. Each bean keeps its own generator, builder and table (the SPI of
decisions §12). A call-shaped bean (`sum_voice_*`) is the kind of the group it reads: `bean/spi/ServiceGroupReader`,
implemented by `CallSummaryBean` (one getter). The contract and the table of the three kinds: §7.

| where | what |
|---|---|
| `summarybeans/call/CallKind` | `call`: groups 10, 11 **and every group no kind names** (D2: before kinds every group's chargeables were summed); `cdr`; catalog `dailyCallSummary`, `hourlyCallSummary`, `dailyChargeableSummary`, `hourlyChargeableSummary`; owns the chargeable roll-up |
| `summarybeans/call/chargeable/…` | the chargeable roll-up, MOVED from `summarybeans/chargeable` (git mv; the package lines and the importers' lines — `beans/Daily\|HourlyChargeableSummaryBuilder`, three tests, the IT contract — nothing else) |
| `summarybeans/ad/AdKind` | `ad`: group 30; `cdr`; catalog `dailyAdSummary`, `hourlyAdSummary`; owns `AdSummaryBean`; a call-shaped bean of 30 (`sum_voice_*_30`) is its |
| `summarybeans/wifi/WifiKind` | `wifi`: group 101; `cdr` + `wifi_flow`; no catalog class; the shapes `wifi-session`, `wifi-dst` (the fine-tuning key kept: `WifiBeanKind`, `WifiSummaries`); owns the session and destination beans; a call-shaped bean of 101 is its |
| `registry/internal/KnownKinds` | the kinds listed ONCE (call, ad, wifi): the kind of a group, the kind of a bean (none → refused in words), the kind that makes a shape. `ConfigInstantiatedBeans` asks it for a shape; the refusal of an unknown shape word is the message of before, word for word (its tests unchanged and green) |
| the package docs | `call`, `call/chargeable`, `ad`, `wifi`: each says it is its kind's package |

### 2.2 · The proof

Red first: `KnownKindsTest` (10 tests) does not compile on a4ed2ab — no contract, no kinds, no `call.chargeable` (01:10:28).
Green on a clean snapshot of the item's tree (01:10:28–01:18:37): unit **409 · 0 · 0 · 0** (399 + 10), ITs **86 · 0 · 0 · 3**
(the baseline's exactly). The test scans the compiled `summarybeans/` for every `@Singleton` bean class: each is declared by
exactly one kind and lives in its kind's package; no two kinds name one group; exactly one kind takes the unnamed groups. The
pins of the item's jar: identical to the base on both engines (§4). No behaviour changed in this item.

## 3 · Item 2 — the activation per tenant (a680760)

### 3.1 · As built

| where | what |
|---|---|
| `registry/internal/TenantKinds` | `summary.kinds` (the YAML list, comma-joined by the loader; case and blanks ignored; a repeat is one kind). None named = `[call]`. A word that is no kind: `REFUSING TO START: summary.kinds names 'sms', which is no kind — the kinds are call, ad, wifi` |
| `SummaryBootstrap` | `onStart` the same named steps; `registerBeans` became `registerTheBeansOfTheEnabledKinds`: the kinds → the beans the names make (as before: a name that makes none is said and skipped) → **refuse the beans of a kind not enabled** (all named, each with its kind, before ANY bean is registered — D1) → register → tell the chargeable roll-up the enabled groups → tell the gate what to count → say `KINDS …` in one line. Print-only: the same check, the `KINDS` line, exit 4 with the refusal's words |
| `bean/spi/CrossKindReader`, `ChargeableSummaryBean` | the one bean whose input crosses the kinds: told the enabled kinds' groups it sums no leg of a record of another group (the record's group = its customer leg's, the call beans' rule); untold, every group (tests, embedders) |
| `outbox/spi/RowCensus`, `OutboxReader` | the reader hears, AFTER each committed step, the decoded rows that step consumed — only for the bean the census counts with. A rolled-back step is not heard; a quarantined row is not; a reader built without a census (the old constructor, kept) hears nothing |
| `registry/internal/KindGate` | counts the records of the kinds not enabled — ONE counting bean per tier (the first `cdr` bean the profile enables), so a row is counted once (its bookmark: exactly once, across restarts too). A record's kind: its customer leg's `servicegroup`, else its `Cdr.ServiceGroup`, else 0 (the call's). ONE WARN per tenant (tier schema), kind and day (`summary.zone`): `KIND_NOT_ENABLED <kind> <n> rows — tenant <tier>, day <day>: the cdr rows of the <kind> kind (<kind> = service group <g>) are not summed here, <the tenant's kinds>; their outbox rows are consumed as every other. Said once per tenant, kind and day; the day's total is said when the day turns and at stop`; then `KIND_NOT_ENABLED <kind> <n> rows in all — tenant …, day … (the day's total; not summed)` (INFO) when the day turns and at stop. A row it cannot read is said (WARN) and never thrown into the drain |
| the profiles | `btcl/lab` and `btcl/radius2`: `kinds: [call, ad, wifi]` with its comment; `tcbl/dev`: none (its six beans are the call's). Comment lines else (the chargeable's moved path) |

The start's line on the bed's profile: `KINDS call, ad, wifi (summary.kinds) — call = service groups 10, 11 and every group no
kind names; ad = service group 30; wifi = service group 101` (seen in the e2e's service logs of the new jar). On a voice
profile: `KINDS call (summary.kinds names none: the call alone, as before kinds) — call = … . NOT enabled: ad = service group
30; wifi = service group 101 — their cdr rows are summed by no bean here; they are counted and said (KIND_NOT_ENABLED, once
per tenant, kind and day)`.

### 3.2 · The proof

Red first: the item's 7 new test classes (and the IT contract's new test) do not compile on item 1 (01:19:31). Green on a
clean copy of the item's tree (01:19:31–01:27:33): unit **436 · 0 · 0 · 0** (409 + 27: `TenantKindsTest` 6, `KindGateTest` 5,
`OutboxReaderCensusTest` 4, `ChargeableReadsTheEnabledKindsTest` 3, `SummaryBootstrapKindsTest` 7, `ProfileKindsTest` 2), ITs
**88 · 0 · 0 · 3** (PostgreSQL 41 + 9, MySQL 32 + 2 — `a_call_tenant_sums_its_own_records_counts_the_other_kinds_records_once_and_consumes_every_outbox_row`
on each engine: a call tenant, two outbox rows mixing a call of 10, one of 11, two views, a session; the counting bean and the
chargeable roll-up drain both; the call's sums, the chargeable without a leg of 30/101, the bookmarks at 2, the gate's ad 4 /
wifi 2 — counted ONCE though two beans read the rows — a re-drain counts nothing, the reaper trims both rows). One existing
test's SETUP changed: the SS-0006 bootstrap test enables WiFi beans on the voice profile, so it now names `summary.kinds:
call,wifi` (its assertions unchanged). `ProfileKindsTest`: the three shipped profiles start — every bean they enable is of a
kind they enable.

On the packaged jar, both engines (the pins, §4): a voice tenant (6 beans, no kind named) over the corpus → its voice tables
identical to a4ed2ab's; its chargeable tables = a4ed2ab's minus exactly the rows of groups 30 and 101 (day 12 → 8, hour
20 → 15; the rows of 10, 11 and 4 identical); the service said `KIND_NOT_ENABLED ad 7 rows …` and `KIND_NOT_ENABLED wifi 5
rows …` at the first such row (the corpus's third and fourth outbox rows) and, at stop, `ad 9 rows in all`, `wifi 7 rows in
all` — the corpus holds 9 views and 7 sessions; every bookmark at the head, 0 dead letters, 0 ERROR lines.

## 4 · Item 3 — the sums unchanged (2d0e226; the after-pins in §4.1)

**The harness** (`tools/lab/kinds-pin-e2e.sh`, `testkit/LabPinCorpus`, committed BEFORE item 1): ONE corpus — voice of 10
(v2, the v1 shape, two destinations, a rate, across midnight) and 11; ad views of 30 (leaf, root, refused, never shown, a
content, units, the S18 route); WiFi sessions of 101 as BC-0008 writes them (two BRAS, none, money, zero seconds); a record of
a group no kind names (4); ONE outbox row mixing every kind; a `subtract` correction; a second visit of the same windows; two
`wifi_flow` rows — 9 outbox rows written as billing-core writes them (one row, one transaction) into a fresh tier schema; a
packaged app (`SS_PIN_APP`) started by its own `run-lab.sh` with every bean of the service (`all`: 16 beans, kinds call, ad,
wifi) or a voice tenant's (`call`: 6 beans, no kind named); every bookmark awaited; every `sum_*` table digested (its rows
without the id, sorted), the bookmarks, the dead letters. The digests are kept in `docs/ad-as-call/SS-0008-pins/`.

| the app | profile | PostgreSQL 16 | MySQL 5.7 |
|---|---|---|---|
| a4ed2ab (the base) | all | `d71bdb69…` — 16 tables, 110 lines, 16 of 16 bookmarks at the head, 0 dead letters | `d71bdb69…` (byte-identical to PostgreSQL's) |
| item 1's tree | all | `d71bdb69…` — **identical** | `d71bdb69…` — **identical** |
| item 2's tree | all | `d71bdb69…` — **identical** | `d71bdb69…` — **identical** |
| a4ed2ab | call | `d5f27833…` — 6 tables, 57 lines | `d5f27833…` |
| item 2's tree | call | `cc879e0c…` — voice tables identical; chargeable = base minus exactly its 30/101 rows (§3.2) | `cc879e0c…` — the same |

### 4.1 · The after-pins on a clean copy of the committed head

The after-pins are of the jars built from copies whose `src/` is byte-identical to the commits (`diff -r` against a `git
archive` of bd7721a and of a680760: no difference), each packaged by its own green `mvn verify`. Kept in
`docs/ad-as-call/SS-0008-pins/` with `SHA256SUMS`:

| digest | sha256 | |
|---|---|---|
| `a4ed2ab-all-{postgresql,mysql}` | `d71bdb69…aa56` | the base, every bean |
| `bd7721a-all-{postgresql,mysql}` | `d71bdb69…aa56` | item 1 — identical |
| `a680760-all-{postgresql,mysql}` | `d71bdb69…aa56` | item 2 — **identical**: every sum of every bean unchanged, on both engines |
| `a4ed2ab-call-{postgresql,mysql}` | `d5f27833…0027` | the base, a voice tenant |
| `a680760-call-{postgresql,mysql}` | `cc879e0c…8788` | item 2, a voice tenant: the voice tables identical; the chargeable tables = the base minus exactly their group-30/101 rows (the kinds a voice tenant does not enable) |

The merge head's jar (a clean copy of `master-merge`'s tree — the same code) is pinned again before the merge is committed:
its digests and numbers are in the merge commit's message.

Beside the pins, the existing scenarios' own sums: the unit suite and every IT (their asserted sums) pass unchanged on every
tree of this round (the one setup change of §3.2 aside), and the three SS-0007 stories on item 2's jar: `wifi-beans-e2e`
86 ok, `wifi-gw-upgrade-e2e` 80 ok, `wifi-gw-wayback-e2e` 96 ok, 0 FAIL (01:31–01:38) — the baseline's numbers.

## 5 · The mutants (each ONE text swap in its own fresh copy of the tested tree; its targeted tests; the copy removed)

The worktree never held a mutant, so no stale class could survive a restore (the "fresh mtime" rule is met by construction).
Every one red:

| mutant | red (test:line) |
|---|---|
| M1.1 `KnownKinds.ofServiceGroup` ignores the named groups (every group the call's) | 2 — `KnownKindsTest.a_service_group_is_the_kind_that_names_it…:58`, `a_call_shaped_bean_is_the_kind_of_the_group_it_reads:89` |
| M1.2 `kindOf` decides a call-shaped bean by its class, not its group | 1 — `KnownKindsTest.a_call_shaped_bean…:87` (`bean 'c10' belongs to no kind`) |
| M1.3 `AdKind` names no group (30 falls to the call) | 3 — `KnownKindsTest…:58`, `…:89`, `no_two_kinds_name_one_group…:74` |
| M1.4 a shape word read as written (no trim, no lower case) | 2 — `ConfigInstantiatedBeansTest.the_kind_is_read_whatever_its_case_or_blanks:55`, `KnownKindsTest.the_shapes_are_the_wifi_kinds_two…:107` |
| M2.1 **activation**: a bean of a kind not enabled is NOT refused | 2 — `SummaryBootstrapKindsTest.a_bean_of_a_kind_the_tenant_did_not_enable_refuses_the_start…:108`, `print_only_says_the_kinds_and_exits_with_4…:196` |
| M2.2 **activation**: none named = EVERY kind | 12 — `TenantKindsTest`, `KindGateTest` ×3, `SummaryBootstrapKindsTest`, `ProfileKindsTest.the_voice_profile…:63`, …, and the IT on BOTH engines (`a_call_tenant…:436`: the chargeable sums the 30/101 legs) |
| M2.3 **activation**: the chargeable roll-up not told the enabled groups | 1 — `SummaryBootstrapKindsTest.the_chargeable_roll_up_is_told_the_enabled_kinds_groups:154` |
| M2.4 **skip**: the chargeable filter off (sums every record whatever it was told) | 5 — `ChargeableReadsTheEnabledKindsTest` ×2 (`:79`, `:86`), `SummaryBootstrapKindsTest…:154`, the IT on both engines (`:436`) |
| M2.5 **count**: every bean counts | 3 — `KindGateTest.only_the_one_counting_bean_counts…:95`, the IT on both engines (`:441`: 8 counted for 4 — a row counted once per bean) |
| M2.6 **count**: heard when READ, before the write and the commit | 3 — `OutboxReaderCensusTest`: `a_step_that_rolls_back_is_never_heard:92`, `…heard_once_after_the_commit_with_the_tier:69`, `a_quarantined_row_is_not_heard…:105` |
| M2.7 **count**: the WARN at every committed step, not once per tenant, kind and day | 1 — `KindGateTest.one_warn_per_tenant_kind_and_day…:132` |
| M2.8 **count**: the day on UTC, not the tenant's zone | 1 — `KindGateTest.one_warn_per_tenant_kind_and_day…:133` (`day 2026-10-23` for the 24th) |

Batches: 01:41–01:45 (all 12; but my runner joined test classes with `+`, surefire's method separator, so M1.1, M1.4, M2.1 and
the unit part of M2.2/M2.4/M2.5 ran no test — F6) and 02:23–02:26 (those six again, test classes comma-joined, all red).

## 6 · Item 4 — the merge plan (`master-merge`)

| | |
|---|---|
| how | `master-merge` made from `master` ec2546e; `kinds` merged `--no-ff`; the suite run on the merge head's tree from a clean copy BEFORE the commit; the commit's message carries the numbers. Not pushed; `master` not touched |
| textual conflicts | **none**: `master` ec2546e is an ancestor of `kinds` (`git merge-base master kinds` = ec2546e; 0 commits on master that kinds lacks, 68 on kinds that master lacks); `git merge-tree --write-tree master kinds` exits 0 and its tree is `kinds`' own |
| the merge's tree | `git diff kinds master-merge` is empty |

What changes for anyone who RUNS master's jar (no conflict in git, but each is a step for a deployment of master — none is
deployed that I know of: master's one tenant `tcbl/dev` points at CCL's database, never cut over):

| | master (ec2546e) | master-merge |
|---|---|---|
| the store | `quarkus.datasource.*` in the profile (MySQL, the Quarkus extension) | `summary.store.*` (`kind: mysql \| postgresql`, `url`, `username`, `password` / `password-ref: env:NAME`) — a profile written for master's jar names no store for the new one: with autostart on, the start is refused in words (`names no store`). `tcbl/dev` in the jar is already rewritten |
| the ad beans | entity `ad_cdr` (`AdCdr`, ad-sphere's own writer) | entity `cdr`, service group 30 (an ad is a Call): `ad_cdr` rows are no longer read by anyone (and the reaper trims `cdr` only) |
| tenants | one schema | `summary.tenants.mode: single` (default, as before) or `tree` (every tier schema of the root's tree in one process) |
| the kinds | — | `summary.kinds`: none = the call alone. A master-era profile that lists the ad beans and names no kind is REFUSED (D1); master's own `tcbl/dev` lists only call beans — it starts as before |
| the chargeable package | `summarybeans/chargeable` | `summarybeans/call/chargeable` (an embedder importing it changes one import) |
| everything else of postgres-ad-call | — | as decisions §16 and SS-0001…SS-0007 describe (PostgreSQL, the WiFi beans, the BRAS split, the secrets by variable name, the start's endpoints, …) |

## 7 · The kind contract

```java
public interface SummaryKind {
    String word();                                   // call | ad | wifi — the word in summary.kinds
    Set<Integer> serviceGroups();                    // the groups whose cdr rows are its
    default boolean takesEveryUnnamedServiceGroup()  // the call: every group no kind names (as before kinds)
    Set<String> entityTypes();                       // cdr; the wifi kind also wifi_flow
    Set<String> catalogBeans();                      // its window classes (CDI singletons), by name
    default Set<String> shapes()                     // the words of summary.beans.<name>.kind it makes (wifi-session, wifi-dst)
    default SummaryBean<?> shaped(name, shape, window, context)
    boolean owns(SummaryBean<?> bean);               // its own-shaped beans; a call-shaped bean is the kind of its group
}
```

| kind | class | groups | entities | its bean set (generator · builder · table) |
|---|---|---|---|---|
| `call` | `summarybeans/call/CallKind` | 10, 11, and every group no kind names | `cdr` | `DailySummary` / `HourlySummary` + `CallSummaries` extras → `sum_voice_<w>_<suffix>` (CallSummaryGenerator · CallSummaryBuilder, `beans/Daily\|HourlySummaryBuilder` · SumVoiceDdl); `chargeable/DailyChargeableSummary` / `HourlyChargeableSummary` → `sum_chargeable_<w>` (ChargeableSummaryGenerator · ChargeableSummaryBuilder, `beans/Daily\|HourlyChargeableSummaryBuilder` · SumChargeableDdl) |
| `ad` | `summarybeans/ad/AdKind` | 30 | `cdr` | `DailyAdSummary` / `HourlyAdSummary` → `sum_ad_<w>_30` (AdSummaryGenerator · AdSummaryBuilder · SumAdDdl); a call-shaped bean of 30 → `sum_voice_<w>_30` |
| `wifi` | `summarybeans/wifi/WifiKind` | 101 | `cdr`, `wifi_flow` | shape `wifi-session` → `sum_wifi_<w>_101` (WifiSessionSummaryGenerator · WifiSessionSummaryBuilder · SumWifiSessionDdl); shape `wifi-dst` → `sum_wifi_dst_<w>` (WifiDestinationSummaryGenerator · WifiDestinationSummaryBuilder · SumWifiDestinationDdl); a call-shaped bean of 101 → `sum_voice_<w>_101` |

The known kinds are listed once: `registry/internal/KnownKinds` (`List.of(new CallKind(), new AdKind(), new WifiKind())`). It
answers three questions: `ofServiceGroup(g)` (the kind naming g, else the call), `kindOf(bean)` (a `ServiceGroupReader` by its
group, else the kind that owns its class; none → refused in words), `shaped(name, shape, …)` (the kind whose shape it is; an
unknown word refused naming every shape). The activation around it: `TenantKinds` (the tenant's), `KindGate` (the count),
`CrossKindReader` (the chargeable), `RowCensus` (the reader's hook) — decisions §17.

## 8 · How to add `sms`

1. **The package** `summarybeans/sms/` (today a `package-info` placeholder), the shape of every kind: the window classes
   `DailySmsSummary` / `HourlySmsSummary` (`@Singleton`, a `NAME` each) over `internal/SmsSummaryBean` (implements
   `SummaryBean<SmsSummary>`; if it keeps the records of ONE service group, also `ServiceGroupReader`), its generator
   (`extends SummaryGenerator<I, SmsSummary>`), its builder, its table (`SumSmsDdl` — a `SummaryTableSpec`, `.keptUpToDate()`
   for a table the service owns alone), and `model/SmsSummary` (implements `SummaryEntity<SmsSummary>`). If the SMS rides the
   `cdr` stream (billing-core writes an SMS as a `cdr` row of its own service group), read the blob with the call's model
   (`call/model/CdrBlobEntry`, `CdrBlobMapper`); if it is its own outbox entity, say so in `entityTypes()`. A builder in
   `beans/` (`extends SummaryBeanBuilder`, decisions §12e).
2. **The kind** `summarybeans/sms/SmsKind implements SummaryKind`: `word()` `"sms"`, `serviceGroups()` the SMS group(s) (a group
   no other kind names — `KnownKindsTest` fails on a group named twice), `entityTypes()`, `catalogBeans()` its two `NAME`s,
   `owns(bean)` its bean classes. Shapes only if it config-instantiates beans by `summary.beans.<name>.kind`.
3. **One line** in `KnownKinds`: `List.of(new CallKind(), new AdKind(), new WifiKind(), new SmsKind())`. Nothing else in the core
   changes — not the engine, the reader, the bootstrap, the gate or the reaper.
4. **The tests** (they exist for every kind; add the SMS rows): `KnownKindsTest` (its group → `sms`; its catalog classes in its
   package — the scan finds them by itself), the beans' unit tests, a contract IT on both engines (`OutboxConsumerContract`),
   `ProfileKindsTest` for the profile that names it, and a record of its shape in the pins' corpus (`LabPinCorpus`).
5. **The profile** of a tenant that sends SMS: `summary.kinds: [call, sms]` (or with the others) and the beans in
   `enabledSummary`. A tenant that does not name `sms` keeps working: its SMS records — now of a kind it did not enable — are
   summed by no bean and counted (`KIND_NOT_ENABLED sms <n> rows`, once per tenant, kind and day). Before the SMS kind exists
   its group is one no kind names, so it is the call's: the chargeable roll-up sums its legs, as today. Adding `SmsKind` moves
   that group to `sms`: a voice tenant that has been summing SMS chargeables must then name `sms` to keep them (F8).

## 9 · Findings, decisions, the round's own reds

| # | finding / decision | proposal |
|---|---|---|
| D1 | a bean of a kind not enabled REFUSES the start rather than being skipped: a skipped bean does not hold the outbox (the reaper trims past the registered beans), so its rows would be deleted and its summaries lost for good when it came back | **the bed's profile gains `kinds: [call, ad, wifi]` before this jar** (deploy page §1, §3, §4, §6) |
| D2 | the call kind owns 10, 11 and every group no kind names (none named = today's behaviour for every group but 30 and 101) | none |
| D3 | a call-shaped bean is the kind of its group; the chargeable pair is the call's and reads the enabled kinds' records only. An ad-only tenant that wants the chargeable summary names `call` too | none, or rule otherwise |
| D4/D5 | counted once per row by one bean per tier after its commit; one WARN per tenant, KIND and day + the day's total in an INFO | none |
| D6 | the chargeable package moved under the call kind | none |
| D7 | mutants in fresh copies, never in the worktree | none |
| F1 | the reaper trims the `cdr` entity only (`summary.outbox.entity-type`): `wifi_flow` rows are never trimmed, with or without the wifi kind — pre-existing, not changed | a reaper per entity of the registered beans, in its own round |
| F2 | a tenant whose profile enables no `cdr` bean counts nothing (no bean reads the cdr rows; they are not consumed either, as before) | none |
| F3 | the day's running count lives in the process: a restart in the middle of a day says that day's total in two parts (before and after); the per-row exactness holds (the counting bean's bookmark) | none — the WARN and the INFO lines are the record |
| F4 | `summary.beans.<name>.kind` (a bean's SHAPE, the WiFi kind's fine-tuning) and `summary.kinds` (the tenant's kinds) share a word; renaming the older key would break every deployed profile | none |
| F5 | `CallSummaryBuilder` keeps its per-group branches (10, 11, 30, 101): the call-shaped view of each kind is the call's code; moving them into the kinds would be a rewrite of pinned sums, not a move | none |
| F6 | the round's own reds, none a defect of the service: my first verify helper split the MySQL URL at its `&` (no test ran; 21 minutes of the lock queue lost); the pin script's first two runs (a `local` reading itself; the database clients swallowing the corpus on stdin — now on fd 3); the mutant runner's `+` (six mutants rerun) | none |
| F7 | `SummaryBootstrap.onStart` was 14 lines before this round and is 14 now (one step renamed); the new methods are short named steps | none |
| F8 | adding an SMS kind takes its group away from the call (§8 step 5) | say it in that round |

## 10 · The rules, kept

Java 21 (Corretto 21.0.9); Maven 3.9.10 offline, EVERY run under `flock /home/mustafa/telcobright-projects/.one-maven.lock`, the
head repository `~/ss8-work/m2-head` EMPTY after each (0 files), nothing installed, nothing written to `~/.m2`. Labs: my own two
containers on 127.0.0.1:7437 and 127.0.0.1:7346 (no password exists in either); the service's lab listener 127.0.0.1:7671
(checked free before each run); no Kafka (7692 closed: the Kafka ITs self-skipped). No password on a command line, in a file, a
commit or a message. No sudo, no systemctl, no `pkill`. The jar never started bare: every start through `tools/lab/run-lab.sh`
(the endpoints printed first, all loopback). Commits per item, the trailer on each; nothing pushed; `postgres-ad-call` and
`master` not touched; the main checkout not touched; billing-core's repository read only (`git show`). `date` before every
time written. The session broke once (the owner switched accounts, ~01:46–02:22): nothing of mine was running; the batches'
logs held every result.
