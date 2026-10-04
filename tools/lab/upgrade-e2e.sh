#!/usr/bin/env bash
# The UPGRADE of a deployment that HAS ROWS, with two PACKAGED jars — briefs S13 (the content in the key) and S14
# (no value wider than its column): an ad table an earlier version made is brought up to date by the service itself.
#
#   tools/lab/upgrade-e2e.sh [an older commit — default 4cb77a1, the last head before S13]
#
#   1. the OLDER version — a detached worktree of that commit under target/, packaged there — runs ITS OWN tree story
#      (tools/lab/tree-e2e.sh keep) and is left as it stands: three tier schemas, billing-core's tables and rows, and
#      the older sum_ad_* tables (tup_rulecode 20 wide, no tup_contentid) with the views it summed
#   2. it is stopped; THIS version starts on the same schemas. It says each change it makes to an older table (WARN),
#      and what the tables hold is as it was: every row its id, its numbers, its rule code — and '' as its content
#   3. a view after the upgrade: its content gets a row of its own, beside the older row
#   4. this version is started AGAIN: it finds nothing to change
#
# Everything is on this box (tools/lab/pg-lab.sh: PostgreSQL 16 on 127.0.0.1:7643, Kafka on 127.0.0.1:7692); each
# service is started by tools/lab/run-lab.sh of its own version. What it makes is removed at the end.
set -euo pipefail
cd "$(dirname "$0")/../.."

OLDER=${1:-4cb77a1}
PG=ss-pg16-lab; KA=ss-kafka-lab
PG_URL=jdbc:postgresql://127.0.0.1:7643/routesphere
KAFKA=127.0.0.1:7692
PING_TOPIC=cdr_summary_ping_btcl
WORK=target/lab-e2e
OLD=target/upgrade-e2e/older
TIERS="btcl res_44 res_45"
mkdir -p "$WORK"
[ -f target/quarkus-app/quarkus-run.jar ] || { echo "no packaged application — run: mvn package -DskipTests"; exit 2; }

ask() { docker exec -i "$PG" psql -U "$1" -d routesphere -tA -c "$2"; }
say() { printf '\n== %s ==\n' "$*"; }
service_pid=""
stop_service() { [ -n "$service_pid" ] && kill "$service_pid" 2>/dev/null || true; service_pid=""; sleep 2; }
cleanup() {
  stop_service
  [ -d "$OLD" ] && (cd "$OLD" && tools/lab/tree-e2e.sh down >/dev/null 2>&1) || true     # its service, its stand-in, the three schemas
  git worktree remove --force "$OLD" >/dev/null 2>&1 || true
  git worktree prune >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

# what a tier's day table holds, row by row — the facts that must not change
rows() { ask ad_sphere "select string_agg(concat_ws('/', id, tup_campaignid, tup_rulecode, tup_zone, tup_outcome, views, chargedamount), ' ; ' order by id) from $1.sum_ad_day_30 where $2"; }
columns() { ask summary_service "select string_agg(column_name || coalesce('(' || character_maximum_length || ')', ''), ' ' order by ordinal_position) from information_schema.columns where table_schema = '$1' and table_name = 'sum_ad_day_30' and column_name in ('tup_rulecode', 'tup_contentid', 'chargedunits')"; }
start_this_version() {   # $1 = the log
  tools/lab/run-lab.sh btcl/lab -Dsummary.outbox.poll-interval-seconds=600 > "$1" 2>&1 &
  service_pid=$!
  for _ in $(seq 1 240); do [ "$(grep -c "is served: 6 of 6" "$1" 2>/dev/null || true)" -ge 3 ] && break; grep -q "NOT STARTED\|REFUSING" "$1" && break; sleep 0.5; done
  [ "$(grep -c "is served: 6 of 6" "$1")" -ge 3 ] || { echo "this version did not serve the three tiers:"; grep -E "ERROR|WARN|REFUS|NOT STARTED" "$1" | cut -c1-260 | tail -15; exit 1; }
  for _ in $(seq 1 120); do grep -q "ping listener is hearing" "$1" && break; sleep 0.5; done
}

say "1 · the OLDER version ($OLDER): packaged in a worktree of its own, runs its own tree story, and is left as it stands"
git worktree add --detach -q "$OLD" "$OLDER"
(cd "$OLD" && mvn -o -q package -DskipTests > /dev/null 2>&1) || { echo "the older version could not be packaged"; exit 1; }
(cd "$OLD" && tools/lab/tree-e2e.sh keep) > "$WORK/upgrade-older.log" 2>&1 || { echo "the older version's story failed:"; tail -20 "$WORK/upgrade-older.log"; exit 1; }
echo "older version: $(git -C "$OLD" log --oneline -1 | cut -c1-100)"
for tier in $TIERS; do
  echo "$tier.sum_ad_day_30  columns: $(columns "$tier")   rows (id/campaign/rule/zone/outcome/views/money): $(rows "$tier" true)"
  eval "before_$tier=\$(rows $tier true)"
done
[ -z "$(ask summary_service "select 1 from information_schema.columns where table_schema = 'btcl' and table_name = 'sum_ad_day_30' and column_name = 'tup_contentid'")" ] \
  || { echo "the older version's table already has tup_contentid — name a commit before S13"; exit 2; }
kill "$(cat "$OLD/$WORK/service.pid")" 2>/dev/null || true; sleep 2
echo "the older version is stopped; its stand-in of prime-context stays (the tree btcl > res_44, res_45)"

say "2 · THIS version starts on the same schemas"
start_this_version "$WORK/upgrade-1.log"
grep -o "schema=[a-z0-9_]* bean=[A-Za-z]* table [a-z0-9_]* was made by an earlier version — bringing it up to its description: .*" "$WORK/upgrade-1.log" | sort | cut -c1-230
changes=$(grep -c "was made by an earlier version" "$WORK/upgrade-1.log" || true)
echo "changes said: $changes (3 tiers x 2 tables x 2 changes = 12)"
[ "$changes" = "12" ] || { echo "FAILED: not the twelve changes"; exit 1; }
for tier in $TIERS; do
  now=$(rows "$tier" true); was=$(eval "echo \$before_$tier")
  [ "$now" = "$was" ] || { echo "FAILED: $tier's rows changed: was [$was], is [$now]"; exit 1; }
  [ "$(ask ad_sphere "select count(*) from $tier.sum_ad_day_30 where tup_contentid <> ''")" = "0" ] || { echo "FAILED: an older row of $tier has a content"; exit 1; }
  echo "$tier.sum_ad_day_30  columns: $(columns "$tier")   its rows are as they were, each with '' as its content"
done

say "3 · a view after the upgrade (content c-81)"
[ -s "$WORK/cp.txt" ] || mvn -o -q dependency:build-classpath -Dmdep.outputFile="$WORK/cp.txt" -Dmdep.includeScope=test >/dev/null
java -cp "target/classes:target/test-classes:$(cat "$WORK/cp.txt")" com.telcobright.summary.testkit.BillingLabWriter "$PG_URL" "$KAFKA" "$PING_TOPIC" view-0009 2>/dev/null
for _ in $(seq 1 150); do [ "$(ask ad_sphere "select coalesce(sum(views),0) from res_44.sum_ad_day_30 where tup_contentid = 'c-81'" 2>/dev/null || echo 0)" = "1" ] && [ "$(ask ad_sphere "select coalesce(sum(views),0) from btcl.sum_ad_day_30 where tup_contentid = 'c-81'" 2>/dev/null || echo 0)" = "1" ] && break; sleep 0.2; done
for tier in btcl res_44; do
  echo "$tier.sum_ad_day_30  by content: $(ask ad_sphere "select string_agg(quote_literal(tup_contentid) || ' views ' || views || ' money ' || chargedamount, ' ; ' order by tup_contentid) from $tier.sum_ad_day_30")"
  [ "$(ask ad_sphere "select views from $tier.sum_ad_day_30 where tup_contentid = 'c-81'")" = "1" ] || { echo "FAILED: the content's row is not there in $tier"; exit 1; }
  [ "$(rows "$tier" "tup_contentid = ''")" = "$(eval "echo \$before_$tier")" ] || { echo "FAILED: $tier's older rows changed"; exit 1; }
done
echo "the content has a row of its own; the older rows are untouched: history is not rebuilt"

say "4 · this version is started again"
stop_service
start_this_version "$WORK/upgrade-2.log"
again=$(grep -c "was made by an earlier version" "$WORK/upgrade-2.log" || true)
echo "changes said at the second start: $again"
[ "$again" = "0" ] || { echo "FAILED: the second start changed a table"; exit 1; }
[ "$(ask ad_sphere "select sum(views) from res_44.sum_ad_day_30")" = "$(ask billing_core "select count(*) from res_44.cdr where ServiceGroup = 30")" ] || { echo "FAILED: res_44's views are not its cdr rows"; exit 1; }
echo "res_44: $(ask ad_sphere "select sum(views) from res_44.sum_ad_day_30") views in the summary = its cdr rows; nothing was counted twice"

echo; echo "THE UPGRADE HOLDS: the older tables were brought up to date by the service, their rows are as they were, a content has its own row, and a second start changes nothing."
echo "removing: the services, the stand-in, the three schemas, the older version's worktree"
