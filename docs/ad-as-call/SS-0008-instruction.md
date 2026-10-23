# SS-0008 — summary-service: one bean set per kind (call · ad · wifi), switched on per tenant by config (READY)

Written 2026-10-23 ~22:45 +06 by the architect; launched 2026-10-24 00:22 +06 on the owner's word ("launch the billing and summary agents too,
don't wait"). The owner's ruling of 2026-10-23 (routesphere `docs/architecture/routesphere-seed-flow-requirements.md` R13 — READ IT FIRST,
then `docs/architecture/ad-is-a-call.md` §3.3): the summary service gets *"new logic beans close to the current call processing,
extended to the new services"* — S17 of SS-0003 §5 ("the kinds as config-activated processors"), which waited for the owner's word.
The road is: every switch publishes its CDR to Kafka; billing-core writes `cdr` + the `summary_affected` input in ONE transaction and pings
`cdr_summary_ping`; summary-service reads the outbox per schema and builds the hourly/daily sums.

## 0 · Where things are

- summary-service branch **`postgres-ad-call`** 72d5875 = what the bed runs: the outbox reader per schema (`JdbcOutboxStore`), the ping
  listener, the generic `bean/spi/SummaryGenerator` contract, the call sums (`summarybeans/call`, `sum_voice_*`), the ad sums
  (`summarybeans/ad`: `DailyAdSummary`, `AdCallCdr`, `SumAdDdl`; the group-30 key `<ruleId>/<app>` S18), the WiFi sums (`summarybeans/wifi`:
  `WifiSessionSummaryBean` by hour/day keyed by BRAS, `WifiDestinationSummary…`, SS-0005/0006/0007) — the WiFi beans are already
  config-instantiated by `summary.beans.<name>.kind` (`WifiBeanKind`). `master` ec2546e has the ad only.
- Your branch **`kinds`** from `postgres-ad-call`, worktree `summary-service/worktrees/kinds` (this checkout). Work ONLY here.

## A · Items

1. **The kind contract** (`bean/spi/SummaryKind`): a kind declares its bean set (generators, builders, DDL) and the service groups it reads;
   `CallKind`, `AdKind`, `WifiKind` — one package each, the existing beans moved under them (moved, not rewritten; the WiFi kind keeps
   `summary.beans.<name>.kind` as its fine-tuning).
2. **Activation per tenant**: `summary.kinds: [call, ad, wifi]` in the tenant profile; none named = `call` (today's behaviour). A cdr row in the
   blob whose service group belongs to a kind the tenant did not enable is not summed and is counted (one WARN per tenant and day,
   `KIND_NOT_ENABLED <kind> <n> rows`); the outbox row is consumed as today (nothing stuck, nothing lost without a word).
3. **Unchanged sums**: pin the sums of every existing scenario on both engines before the change; the same after.
4. **The merge plan**: `postgres-ad-call` → `master` — prepare the merge on a branch `master-merge` (from `master`, merging `kinds`), list
   every conflict and how you resolved it, run the suite on the merge head; the architect merges.
5. **Docs + report** (`docs/ad-as-call/SS-0008-done.md`): the kind contract, how to add `sms`.

## B · Rules

- EVERY mvn under `flock /home/mustafa/telcobright-projects/.one-maven.lock mvn -o …` (other agents build at the same time; the lock
  serializes). Never install into `~/.m2`. Never push. No sudo/systemctl. Never `pkill -f` (stop by PID). Passwords from the environment only;
  never in a file, a command line or a message. Never cd outside this worktree; never touch another worktree or the main checkout.
- Your lab ports (both engines, started and removed by you): **MySQL/MariaDB 127.0.0.1:7346, PostgreSQL 127.0.0.1:7437**. Never
  3306 / 7433 / 7306 / 7434 / 7379 / 7681–7687 / 7336 / 7436 (other sessions' and agents' labs). Check `ss -ltn` before binding.
- Baseline first (both engines, as SS-0007 ran them; name the numbers); red first; mutants on the activation and the skip-count (each red,
  restored with a fresh mtime); commit per item `SS-0008 <n>: …` ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`;
  `docs/ad-as-call/SS-0008-state.md` after every item (so a stop loses nothing); the report drafted early.
- billing-core's agent runs BC-0009 in parallel — the `summary_affected` blob shape is the contract between you: UNCHANGED.
- A road that cannot reach its service (a database, Kafka, a REST end point) is reported in the state file, not fixed.

## C · Out of scope

The switches, billing-core, the bed. No push.
