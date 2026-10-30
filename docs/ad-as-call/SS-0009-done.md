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
| 3 summary-service on the services store, kind ad | — | |
| 4 the proof: two categories, two databases, two topics | — | |
| 5 the gates and the bed jar | — | |

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
