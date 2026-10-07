from: ARCH  to: SS  kind: update  number: 0003  date: 2026-10-07 13:49 +06:00  ·  not launched: the owner starts the round

# SS-0003 — S18 (the ad's new outgoing route), and what S16 / S17 still owe

**The owner's priority (2026-10-07):** ad-sphere is to be feature-complete and tested FIRST. summary-service is in that lane, because a
bed run of the ad service writes rows this service reads. Read `routesphere/docs/architecture/ad-is-a-call.md` §5 (the S-item list; S18
was added there today) and `ad-sphere/docs/ad-as-call/exchange/X/ARCH-0055-instruction.md` + `ARCH-0056-instruction.md` (read-only).

## 1 · What changed in the ad's CDR row (ad-sphere main 058ff14, merged)

The ad view's record is now written exactly as a call's (the owner's ruling):

| field | before | now |
|---|---|---|
| `outPartnerId` | the "network division" of the tenant's config | **the partner of the app that asked for the ad** (the `ad_caller` row's partner); the network division moved to the record's meta as `networkDivisionId` |
| `outgoingRoute` | the zone | **`<ruleId>/<app>/<zone>/<site>/<district>/<gw>/<msisdn>/<mac>`** — the matched rule's id, then ALL seven rule-matching request params, always seven positions, empty between the slashes where the request named nothing; `0/…` when no rule matched; each value percent-encoded (`/` → `%2F`, `%` → `%25`) |
| `incomingRoute` | — | the campaign's dialplan route name |
| `inPartnerId`, `packageAccountId`, `uom`, `rate`, `reservedAmount`, `balanceBefore` | — | per tier, as the call's `setBillingInfo` |
| `inPartnerCost` | — | the rated money cost when the MAIN BALANCE paid; **0 when a package unit paid**, as the call writes it |

## 2 · S18 — the SG30 call-shaped summary must not key on the device

**The defect, read at its source:** `CallSummaryBuilder.populateCommon` (:50–58) copies `cdr.outgoingRoute()` into
`s.tup_outgoingroute`, a KEY dimension, and `canonicalizeKeyDimensionsToColumnContract` (:148–156) clips it to 64 characters. With the
new route that key carries the MSISDN and the MAC, so the SG30 summary gets **one row per device per window**, and a long route is cut
mid-value.

**The rule:** for service group 30 only, `tup_outgoingroute` keeps the route's **first two parts** — `<ruleId>/<app>` — and nothing else.
Groups 10, 11 and 15 are untouched. The ad's own `sum_ad_day_30` / `sum_ad_hr_30` (campaign + content keyed, S13) are untouched.
billing-core is untouched: its `cdr.OutgoingRoute` is `TEXT` and its own mediation summary has no entry for group 30 (guarded by
`if (tables == null) return`), which the architect verified today.

**Tests:** a route with a device in it summarises into ONE row for two different devices of the same rule and app; a route longer than 64
characters is not cut inside a part; `0/<app>` (no rule matched) keys as itself; a group-10 record's route is still the whole string.
Break each rule once, see it red, restore.

## 2a · S18 must CALL the ad's key helper, not re-derive the prefix (2026-10-07 15:35)

The ad service now ships the route's reader beside its writer (ad-sphere main, ARCH-0057,
`src/main/java/com/telcobright/adsphere/flow/api/AdCallPreprocessor.java:147–212`):

| helper | what it answers |
|---|---|
| `routeKeyOf(route)` | `Optional<String>` — **exactly the key S18 needs**, `<ruleId>/<app>` AS THE ROW CARRIES IT (still encoded, so an app named `a/b` stays one part) |
| `routePartsOf(route)` | the rule id and the seven parameters decoded, by name; **empty** for anything not of the fixed shape, so a pre-ARCH-0055 row (the zone alone) is never read as a route |
| `routeValueFrom(value)` | the inverse of the encoding (`%2F` → `/` first, then `%25` → `%`) |

**Use `routeKeyOf`.** Either call it, or copy those four lines WITH the citation above — do not write a second prefix rule. Two rules for
one key drift the moment the route's shape changes again, and this summary is what the operator's money reports read. A row whose route is
not of the fixed shape (an old row) keeps the whole route as its key, exactly as today: the helper answers empty and nothing is invented.

## 3 · What S16 and S17 still owe

- **S16** — "recover after a database restart" is UNFINISHED on branch `s16-wip` (c8af8f1: `OutboxReaper`, `OutboxWorker`,
  `StoreDataSource`, three ITs). Finish it, or say what is left and why; the suite from a clean copy either way.
- **S17** — the owner's ruling of 2026-10-05: single-operator, and the call / ad / wifi-session kinds as **config-activated
  processors** (not branches of one method). Not started.
- **S15** stays PREPARED and UNMERGED on `no-bundled-tenant` (c2f9127) until the owner decides.

## 4 · Rules that stand

Lab = this PC only, every endpoint printed before a start, nothing dials `10.10.x` / `10.9.9.x` / `103.95.96.77`; **never start this
service's jar bare** (a bundled default tenant reaches live); the password by environment-variable NAME only; the suite green from a clean
copy before each push; one commit per item; `date` before writing any time; your report `SS-0004-done.md`, a draft from the first commit.
