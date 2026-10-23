# SS-0008 — state (kept current after every item; a stop loses nothing)

Agent: summary-service SS-0008. Worktree `summary-service/worktrees/kinds`, branch `kinds` (from `postgres-ad-call` 72d5875 + the brief a4ed2ab).

## Lab (mine; removed at the end)

| what | where |
|---|---|
| PostgreSQL 16.13 | docker `ss8-pg16`, 127.0.0.1:7437, trust (no password exists); database `routesphere`, roles prime_context / ad_sphere / billing_core / summary_service (as tools/lab/pg-lab.sh makes them) |
| MySQL 5.7.44 | docker `ss8-mysql57`, 127.0.0.1:7346, root with an empty password (no password exists) |
| Kafka | none (KafkaListenersIT self-skips, as SS-0007 ran it) |
| Maven | `flock /home/mustafa/telcobright-projects/.one-maven.lock mvn -o …`, head repository `~/ss8-work/m2-head` (must stay empty), read-only tails `~/.m2/arch-ss-review-repo,~/.m2/summary-service-repo,~/.m2/repository`; nothing installed |
| work files | `~/ss8-work/` (clean copies, logs, mutant originals) — removed at the end |

## Items

| # | item | state | commit |
|---|---|---|---|
| 0 | baseline (unit + ITs both engines, the e2e scripts as SS-0007 ran them) + the sums pinned | DONE: clean copy of a4ed2ab — unit 399/0/0/0, ITs 86/0/0/3 (PG 40+9, MySQL 31+2, BuildBakes 1, Kafka 3 skipped); wifi-beans-e2e both 86 ok/0 FAIL, wifi-gw-upgrade-e2e both 80/0, wifi-gw-wayback-e2e both 96/0 (0 ERROR lines) = SS-0007's numbers; the pins of a4ed2ab's jar: all (16 beans) sha256 d71bdb69… on BOTH engines (PG and MySQL byte-identical), call (6 beans) d5f27833… on both | — |
| 1 | the kind contract `bean/spi/SummaryKind`; CallKind · AdKind · WifiKind, the beans moved under them | COMMITTED. red = KnownKindsTest does not compile on a4ed2ab; green on a clean snapshot: unit 409/0/0/0 (+10), ITs 86/0/0/3; pins all = d71bdb69… both engines (= base); mutants M1.1–M1.4 all red (M1.2, M1.3 in batch 2; M1.1, M1.4 rerun in batch 3) | bd7721a |
| 2 | activation per tenant `summary.kinds`; KIND_NOT_ENABLED | COMMITTED a680760. red = its 7 test classes do not compile on item 1; green on a clean copy: unit 436/0/0/0 (+27), ITs 88/0/0/3 (+1 per engine); pins all = d71bdb69… on both engines (= the base); pins call: the voice tables identical, the chargeable = the base minus exactly its group-30/101 rows (day 12→8, hr 20→15), the gate's lines ad 7 / wifi 5 at first sight, totals ad 9 / wifi 7 = the corpus; e2e on its jar 86 / 80 / 96 ok, 0 FAIL; mutants M2.1–M2.8 all red (batch 2 + batch 3) | a680760 |
| 3 | unchanged sums (the pins identical after 1–2) | DONE: the harness committed before item 1 (2d0e226); the digests in docs/ad-as-call/SS-0008-pins/ (+ SHA256SUMS): all = d71bdb69… for a4ed2ab, bd7721a and a680760 on BOTH engines; call: the voice tables identical, the chargeable = base minus exactly its 30/101 rows | 2d0e226 + e2c99dd |
| 4 | the merge plan `master-merge` | DONE: **c7cc8ec** on branch `master-merge` = master ec2546e + kinds eaa826f (`--no-ff`, made with merge-tree + commit-tree; the worktree never left kinds). 0 conflicts (master is an ancestor of kinds: 0 behind, 71 ahead); its tree 01f867a9 = kinds eaa826f's. The suite on that tree from a clean copy BEFORE the commit (04:04–04:09): unit 436/0/0/0, ITs 88/0/0/3; its jar: pins all d71bdb69… / call cc879e0c… on both engines (= the base / = a680760), e2e 86 / 80 / 96 ok, 0 FAIL. The first merge tree 7e6d250a (before the pin script's mode fix eaa826f) passed the same (03:11–03:21) | c7cc8ec (master-merge) |
| 5 | docs + report SS-0008-done.md | DONE (this commit): decisions §17, README, architecture.md, the deploy page (postgres-ad-profile.md: the kinds line, the refusal, the key, the upgrade step, the pin tool), the SMS placeholder's pointer, SS-0008-done.md | (this commit) |

## Log (newest last)

- 00:34 labs started (ss8-pg16 :7437, ss8-mysql57 :7346); state file written.
- 00:35–00:56 the first baseline try waited 21 min for the lock and ran nothing (my helper split the MySQL URL at its `&`; no test ran, nothing written). Helper fixed (arguments as an array).
- 00:58 batch 1 queued (one lock session): base a4ed2ab verify + classpath; 3829e18 packaged (the older app of the upgrade/wayback e2e); item 1 red (base + KnownKindsTest only: must not compile); the worktree verify (item 1 green + the whole suite).
- Item 1 written in the worktree (uncommitted): bean/spi/SummaryKind + ServiceGroupReader; summarybeans/call/CallKind, ad/AdKind, wifi/WifiKind; registry/internal/KnownKinds; ConfigInstantiatedBeans asks KnownKinds for a shape; CallSummaryBean implements ServiceGroupReader; summarybeans/chargeable MOVED to summarybeans/call/chargeable (git mv + package lines). Test: registry/internal/KnownKindsTest.
- The pin harness written (uncommitted): tools/lab/kinds-pin-e2e.sh + src/test/.../testkit/LabPinCorpus.java.
- Item 2 drafted OUTSIDE the worktree (~/ss8-work/drafts: TenantKinds, KindGate, RowCensus, CrossKindReader, patches for OutboxReader / SummaryBootstrap / ChargeableSummaryBean / tests) — applied after item 1's commit.
- 01:04–01:20 batch 1 (one lock session): base verify 399/0/0/0 + 86/0/0/3; 3829e18 packaged; item 1 red (compile) and green (409 + 86/3); item 2 red (its tests do not compile on item 1: TenantKinds, KindGate, RowCensus, CrossKindReader missing); item 2 green verify running on ~/ss8-work/clean/item2-try (item 1 + item 2).
- 01:10–01:17 the baseline e2e on a4ed2ab's jar: 86 / 80 / 96 ok, 0 FAIL.
- 01:20 the pin script fixed twice before its first real run (a `local` that read itself; the database clients swallowed the corpus on stdin — now on fd 3). Base pin on PG: 9 outbox rows, 16 of 16 beans, 16 tables, 110 lines.
- 01:28 batch 1 done (head repository: 0 files). Pins: base all/call, item 1 all, item 2 all/call — all on both engines (above).
- 01:35 batch 2 (the 12 mutants, each in its own fresh copy of the tree; the worktree never holds a mutant) queued for the lock; the three e2e on item 2's jar running.
- 01:41–01:45 batch 2 (12 mutants, each in a fresh copy): valid reds M1.2, M1.3, M2.3, M2.6, M2.7, M2.8 (single test classes); M2.2, M2.4, M2.5 red in the IT on both engines; but M1.1, M1.4, M2.1 and the UNIT part of M2.2/M2.4/M2.5 ran NO test — my runner joined test classes with `+` (surefire wants commas). Runner fixed (commas, -Dmaven.test.failure.ignore so the ITs run after a unit red, parsing of inherited IT names).
- 01:31–01:38 the three e2e on item 2's jar: wifi-beans 86 ok, wifi-gw-upgrade 80 ok, wifi-gw-wayback 96 ok, 0 FAIL — the baseline's exactly.
- (session break: the owner switched accounts; resumed 02:23. Nothing of mine was running; the worktree holds no mutant — grep of src/ for the swap texts empty, ~/ss8-work/mut empty.)
- 02:23 batch 3 queued: the six mutants to rerun (M1.1, M1.4, M2.1, M2.2, M2.4, M2.5). Next: item 2's commit (after its mutants), item 4, item 5.

## Decisions taken without the owner (unattended; the most defendable default, with the reason)

| # | decision | reason |
|---|---|---|
| D1 | a bean `enabledSummary` names whose kind `summary.kinds` does not enable REFUSES the start (in words, nothing registered) — it is not skipped | a skipped bean does not hold the outbox: the reaper trims past the registered beans, so its rows would be deleted and its summaries lost for good when it came back. A refused start loses nothing. Consequence: the bed's rendered profile must gain `kinds: [call, ad, wifi]` before this jar runs on it (said on the deploy page) |
| D2 | the call kind owns groups 10, 11 AND every group no kind names | "none named = call (today's behaviour)": before kinds every group's chargeables were summed; a voice tenant keeps that for every group but 30 and 101 |
| D3 | a call-shaped bean (`sum_voice_*`) is the kind of the group it reads (`sum_voice_*_30` the ad's, `_101` the WiFi's); the chargeable pair is the call kind's and reads only the enabled kinds' records | the brief: a cdr row of a kind not enabled "is not summed" — by any bean; the chargeable roll-up is the only bean reading every group |
| D4 | the count is made once per outbox row by ONE bean per tier (the first cdr bean the profile enables), after its commit | exactly once by the bean's own bookmark (also across restarts); counting in every bean would say n times n |
| D5 | "one WARN per tenant and day" = one per tenant (tier schema), KIND and day, at the first such record; the day's total in an INFO when the day turns and at stop | the line names one kind (`KIND_NOT_ENABLED <kind> <n> rows`); a word at once, the full count later |
| D6 | the chargeable package moved under the call kind (`summarybeans/call/chargeable`) | "one package each, the existing beans moved under them"; it is the call kind's bean set |
| D7 | mutants run each in its own fresh copy of the tested tree, never in the worktree | no mutant can be left in the worktree or survive as a stale class (the "fresh mtime" rule is met by construction) |
| D8 | no separate clean-copy verify of a680760: the merge head's clean-copy verify covers the same code | a680760's src/ is byte-identical (diff -r vs git archive) to the copy that passed 436 + 88/3; the lock queue costs 15–25 min a session |
| D9 | item 4 is built with git plumbing (merge-tree → commit-tree → branch), my worktree never leaves `kinds`; the suite runs on the merge tree BEFORE the merge commit, whose message carries the numbers | work only inside my worktree; "run the suite on the merge head, commit" taken literally |
- 02:23–02:26 batch 3: M1.1, M1.4, M2.1, M2.2, M2.4, M2.5 rerun with their unit tests — all red; the ITs of M2.2 / M2.4 / M2.5 red on both engines. The 12 mutants: all red. Head repository: 0 files.
- 02:27–02:45 batch 4 (a clean-copy verify of a680760) waited 16 min in the lock queue and was WITHDRAWN before it started (its flock stopped by PID; nothing ran): a680760's src/ is byte-identical to the copy that passed (436 + 88/3) and gave the after-pins, and the merge head (the same code) gets its own clean-copy verify. Decision D8: one lock wait saved.
- 02:45 item 3's after-pins committed (the digests in docs/ad-as-call/SS-0008-pins/). Next: item 5's docs commit, then item 4 (merge-prepare → batch 5 verify of the merge head → pins + e2e on its jar → the merge commit).
- 02:46 item 5 committed (the docs). Next: item 4 as its row says.
- 02:46 merge tree 7e6d250a (= kinds 92377d0's tree; 0 conflicts) → clean copy → batch 5 (03:11–03:16): unit 436/0/0/0, ITs 88/0/0/3, head repository 0 files. The three e2e on its jar 03:16–03:21: 86 / 80 / 96 ok, 0 FAIL. Its pins: the script would not run — committed 100644, without its executable bit.
- 03:23 the mode fixed on kinds (this commit); the merge tree to be rebuilt from this kinds head, its clean copy verified again (batch 5 again), its pins and e2e again, then the merge commit (D9). The code is unchanged: the pins of the 7e6d250a copy (bit set in the copy) are running meanwhile.
- 04:04–04:09 batch 6: the suite on the final merge tree 01f867a9 (clean copy): unit 436/0/0/0, ITs 88/0/0/3, head repository 0 files. 04:09–04:17 its jar's pins (all d71bdb69…, call cc879e0c…, both engines) and e2e (86 / 80 / 96 ok).
- 04:18 `master-merge` c7cc8ec made (parents ec2546e, eaa826f; `git diff eaa826f master-merge` empty). master and postgres-ad-call untouched (= origin). Nothing pushed.
- 04:18 ALL ITEMS DONE. This state commit is on `kinds` only (docs): kinds = master-merge's second parent + this commit. Next: the architect's review and merge of `master-merge` into master; before a bed install of this code the bed's rendered profile gains `kinds: [call, ad, wifi]` (D1). The labs and ~/ss8-work are removed after this commit.
