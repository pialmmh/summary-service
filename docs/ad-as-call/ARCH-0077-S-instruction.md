# ARCH-0077-S — summary-service: the WiFi session (service group 101) summed as a domestic call under suffix 101

**To:** the summary-service agent (worktree `summary-service/worktrees/wifi-101`, branch `wifi-101` off `postgres-ad-call` 5098ced; S16/S18 on `s16-wip` are merged by the
architect first — rebase if they land before you) · **From:** the architect.
**Why:** the owner (2026-10-10): the WiFi CDR is the voice's row with service group 101, and the summary goes "in summary service sum_voice_day_xx". billing-core (ARCH-0077-C)
writes ONE customer chargeable per 101 row from the switch's settlement (pre-rated: BilledAmount = the package minutes in TF_min, or money).

## 1 · The items (one commit each; every rule broken once and seen red; push after each)

| # | item | the rule |
|---|---|---|
| 1 | **`CallSummaryBuilder.populateServiceGroup` gains the 101 branch** — today it THROWS "no summary mapping for service group N" for an unknown group. The branch = the 30 branch's stamps WITHOUT the ad's route key: `tup_customerrate` = `unitPriceOrCharge`, `tup_customercurrency` = `idBilledUom` (TF_min and BDT key apart, never summed into one row), `customercost` = `billedAmount`, `tup_matchedprefixcustomer` from the row (empty); plus SG10's package stamps when the row carries them (`longDecimalAmount2` = package amount from `additionalSystemCodes`, `intAmount1` = idpackage from `additionalPartyNumber`); no supplier or tax columns; the ChargingStatus early return as the voice has it (a 0-s session is counted, not costed) | red first: a 101 record throws today; then a unit test of the stamps |
| 2 | **The pre-provisioned voice table set with suffix 101** — `sum_voice_day_101` / `sum_voice_hr_101`, made exactly as the other suffixes are (`SumVoiceDdl` and the DDL files the service ships), for BOTH engines | the DDL applies on the PostgreSQL and the MySQL lab |
| 3 | **The two config-instantiated beans in the bed profile** (`config/tenants/btcl/radius2/profile-radius2.yml`): `dailyCallSummarySg101 {window: daily, service-group: 101, table-suffix: "101"}` and `hourlyCallSummarySg101 {window: hourly, …}` under `summary.beans` + `enabledSummary`, beside the 30 ones; the same in `profile-lab.yml` | a lab run: 101 records through the outbox land in `sum_voice_day_101` / `sum_voice_hr_101` only; the 30 tables unchanged; `sum_chargeable_*` carries the 101 legs as it carries every group |
| 4 | **Report `SS-0005-done.md`**: the reds, the clean-copy suites (`git archive`, fresh private repository) per engine as SS-0004 ran them, the commits | — |

**Not this round:** a WiFi "kind" module (void — the voice branch sums it); the bed.

## 2 · Rules that stand

Java 21; YOUR private Maven repository chained read-only (`-Dmaven.repo.local=$HOME/.m2/summary-wifi-repo -Dmaven.repo.local.tail=$HOME/.m2/repository`); `date` before any
time; the trailer `Co-Authored-By: <the model you run as> <noreply@anthropic.com>`; push after every item (`origin wifi-101`); the lab's PostgreSQL and MySQL are LOCAL
(127.0.0.1, credentials by NAME from the environment); never start the jar bare (a bundled default tenant reaches LIVE); every lab binds 127.0.0.1 and prints its endpoints
first; WireGuard is UP — never reach 172.17.191.1, 10.10.191.x, 10.10.188.x (the radius-2 BED), 10.10.175.x, 10.9.9.x or 103.95.96.77; a test that fails for an unreachable
service: stop and report; nothing left running; the merge is the architect's; never `cd` into another agent's worktree; the house style.
