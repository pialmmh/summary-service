#!/usr/bin/env bash
# The whole tree in ONE process, on the lab, with the PACKAGED jar — briefs S6, S7, S8 from end to end.
#
#   tools/lab/tree-e2e.sh          run the story, print what was found, remove what it made (the containers stay)
#   tools/lab/tree-e2e.sh keep     leave the schemas, the stand-in and the service running, to look around
#   tools/lab/tree-e2e.sh down     remove what an earlier "keep" left
#
# Everything is on this machine: my own containers (tools/lab/pg-lab.sh: PostgreSQL 16 on 127.0.0.1:7643, Kafka on
# 127.0.0.1:7692), a stand-in for prime-context's ONE read road on 127.0.0.1:7691, the service on 127.0.0.1:7671. The
# service is started by tools/lab/run-lab.sh, which SHOWS the profile and the endpoints it resolved and starts only
# when every host is this box. No password exists (PostgreSQL trusts loopback). The ping's topic carries the root
# (cdr_summary_ping_btcl), as on a deployment; both topics are made first — a deployment's broker makes none by itself.
#
# The schemas are made as the real ones are: prime-context's provisioning statements (SchemaSharing, main 1b974a1),
# then billing-core's tables from ITS OWN DDL file with its partitions and its grants (tools/lab/billing-ddl.py).
# The rows are written the way billing-core writes them (BillingLabWriter): per tier, under its advisory lock, ONE
# transaction with the cdr row, the acc_chargeable row and ONE summary_affected row — then its ping.
#
# The story:
#   1. billing-core writes a view through btcl > res_44 BEFORE summary-service ever started          (S7)
#   2. summary-service starts on the tree btcl > res_44: both schemas get the view's rows              (S6, S7)
#   3. a second view: its ping brings it into the summary within seconds (the poll is set to 10 min)   (S8)
#   4. a reseller res_45 is provisioned at run time, the doorbell rings: a view through btcl > res_45
#      gets its rows, with no restart                                                                   (S6)
set -euo pipefail
cd "$(dirname "$0")/../.."

PG=ss-pg16-lab; KA=ss-kafka-lab
PG_URL=jdbc:postgresql://127.0.0.1:7643/routesphere
KAFKA=127.0.0.1:7692
PC_PORT=7691
PING_TOPIC=cdr_summary_ping_btcl          # the profile's summary.outbox.ping-topic: the ping's topic carries the ROOT
BILLING_DDL=${BILLING_DDL:-$HOME/telcobright-projects/telcobright-billing-core/java/src/main/resources/sql/postgres/billing-tables.sql}
WORK=target/lab-e2e
MODE=${1:-run}

psql_as() { local role=$1; shift; docker exec -i "$PG" psql -U "$role" -d routesphere -q -v ON_ERROR_STOP=1 "$@"; }
ask()     { docker exec -i "$PG" psql -U "$1" -d routesphere -tA -c "$2"; }
say()     { printf '\n== %s ==\n' "$*"; }

down() {
  [ -f "$WORK/service.pid" ] && kill "$(cat "$WORK/service.pid")" 2>/dev/null || true
  [ -f "$WORK/standin.pid" ] && kill "$(cat "$WORK/standin.pid")" 2>/dev/null || true
  rm -f "$WORK/service.pid" "$WORK/standin.pid"
  if docker ps --format '{{.Names}}' | grep -qx "$PG"; then
    for schema in btcl res_44 res_45; do psql_as prime_context -c "DROP SCHEMA IF EXISTS $schema CASCADE" 2>/dev/null || true; done
  fi
}
if [ "$MODE" = "down" ]; then down; echo "removed"; exit 0; fi
# whatever happens, nothing of this story is left running (a "keep" run says where it is and how to remove it)
trap '[ "$MODE" = "keep" ] || down' EXIT

[ -f "$BILLING_DDL" ] || { echo "billing-core's DDL file is not at $BILLING_DDL (set BILLING_DDL) — this story needs its own tables"; exit 2; }
[ -f target/quarkus-app/quarkus-run.jar ] || { echo "no packaged application — run: mvn package -DskipTests"; exit 2; }
mkdir -p "$WORK"
down

# as prime-context provisions a tier (SchemaSharing.grant, main 1b974a1 — W12 not landed: SELECT, DELETE by default)
provision_tier() {
  psql_as prime_context <<SQL
CREATE SCHEMA $1;
GRANT USAGE, CREATE ON SCHEMA $1 TO ad_sphere, billing_core, summary_service;
ALTER DEFAULT PRIVILEGES FOR ROLE billing_core IN SCHEMA $1 GRANT SELECT ON TABLES TO ad_sphere;
ALTER DEFAULT PRIVILEGES FOR ROLE summary_service IN SCHEMA $1 GRANT SELECT ON TABLES TO ad_sphere;
ALTER DEFAULT PRIVILEGES FOR ROLE billing_core IN SCHEMA $1 GRANT SELECT, DELETE ON TABLES TO summary_service;
SQL
}
billing_tables() { python3 tools/lab/billing-ddl.py "$BILLING_DDL" "$1" 2026-09 | psql_as billing_core; }
tree() {   # the tree prime-context answers: the root and its resellers
  local children="" sep=""
  for reseller in "$@"; do children="$children$sep\"$reseller\":{\"dbName\":\"$reseller\",\"parent\":\"btcl\",\"children\":{},\"context\":{}}"; sep=","; done
  printf '{"dbName":"btcl","parent":null,"context":{"partners":{}},"children":{%s}}' "$children" > "$WORK/tree.json"
}
CP="target/classes:target/test-classes:$(cat "$WORK/cp.txt" 2>/dev/null || true)"
billing_writes() {   # one view, the way billing-core writes it; $1 = the session id, the rest: wireTenant=schema
  java -cp "$CP" com.telcobright.summary.testkit.BillingLabWriter "$PG_URL" "$KAFKA" "$PING_TOPIC" "$@" 2>/dev/null
}
wait_for() {   # $1 = role, $2 = query, $3 = the answer wanted, $4 = seconds
  local until=$(( $(date +%s) + $4 ))
  while [ "$(date +%s)" -lt "$until" ]; do
    [ "$(ask "$1" "$2" 2>/dev/null || true)" = "$3" ] && return 0
    sleep 0.2
  done
  echo "TIMED OUT after $4 s: $2 never answered '$3' (it says '$(ask "$1" "$2" 2>&1 || true)')"; return 1
}

say "the lab"
tools/lab/pg-lab.sh up
tools/lab/pg-lab.sh kafka
for topic in "$PING_TOPIC" config_event_loader_btcl; do
  docker exec "$KA" /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$KAFKA" --create --if-not-exists --topic "$topic" --partitions 1 --replication-factor 1 >/dev/null 2>&1
done
[ -s "$WORK/cp.txt" ] || mvn -o -q dependency:build-classpath -Dmdep.outputFile="$WORK/cp.txt" -Dmdep.includeScope=test >/dev/null
CP="target/classes:target/test-classes:$(cat "$WORK/cp.txt")"
for tier in btcl res_44; do provision_tier "$tier"; billing_tables "$tier"; done
echo "tiers btcl, res_44: provisioned as prime-context does; billing-core's tables from its own DDL file ($(ask billing_core "select count(*) from pg_tables where schemaname='btcl'") tables in btcl, partitions counted)"
tree res_44
python3 tools/lab/prime-context-standin.py "$PC_PORT" "$WORK/tree.json" > /dev/null 2> "$WORK/standin.log" &
echo $! > "$WORK/standin.pid"

say "1 · billing-core writes a view through btcl > res_44 BEFORE summary-service has ever started (S7)"
billing_writes view-0001

say "2 · summary-service starts (the endpoints first)"
tools/lab/run-lab.sh btcl/lab -Dsummary.outbox.poll-interval-seconds=600 > "$WORK/service.log" 2>&1 &
echo $! > "$WORK/service.pid"
for _ in $(seq 1 120); do grep -q "started in\|NOT STARTED\|REFUSING" "$WORK/service.log" && break; sleep 0.5; done
sed -n '/^== the endpoints/,/^== starting\|^== NOT STARTED/p' "$WORK/service.log"
grep -q "started in" "$WORK/service.log" || { echo "the service did not start:"; tail -20 "$WORK/service.log"; exit 1; }
# a listener is a NEW consumer group: it hears from the moment the broker gives it its partitions (and looks once then)
for _ in $(seq 1 120); do
  grep -q "ping listener is hearing" "$WORK/service.log" && grep -q "doorbell listener is hearing" "$WORK/service.log" && break; sleep 0.5
done
grep -q "ping listener is hearing" "$WORK/service.log" || { echo "the ping listener never got its partitions:"; tail -20 "$WORK/service.log"; exit 1; }
wait_for ad_sphere "select coalesce((select sum(views) from res_44.sum_ad_day_30),0) || ',' || coalesce((select sum(views) from btcl.sum_ad_day_30),0)" "1,1" 60
echo "the view written BEFORE the start is in both schemas' summaries: every row was summed (first bookmarks = 0)"

say "3 · a second view: from its ping to the summary (the poll is 600 s — only the ping explains it) (S8)"
billing_writes view-0002
t0=$(date +%s%3N)                    # both tiers are committed and both pings are sent
wait_for ad_sphere "select coalesce((select sum(views) from res_44.sum_ad_day_30),0) || ',' || coalesce((select sum(views) from btcl.sum_ad_day_30),0)" "2,2" 30
echo "in both schemas' summaries no later than $(( $(date +%s%3N) - t0 )) ms after billing-core's last ping (this check itself asks every 200 ms)"

say "4 · a reseller res_45 is provisioned at RUN TIME; the doorbell rings; a view through btcl > res_45 (S6)"
provision_tier res_45; billing_tables res_45
billing_writes view-0003 res_44=res_45
tree res_44 res_45
printf '%s\n' '{"type":"config_reload","source":"prime-context","changes":["btcl.tenant_registry:INSERT"]}' \
  | docker exec -i "$KA" /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server "$KAFKA" --topic config_event_loader_btcl >/dev/null 2>&1
t1=$(date +%s%3N)
wait_for ad_sphere "select coalesce((select sum(views) from res_45.sum_ad_day_30),0) || ',' || coalesce((select sum(views) from btcl.sum_ad_day_30),0)" "1,3" 60
echo "res_45 is served $(( $(date +%s%3N) - t1 )) ms after the doorbell: its tables made, its view summed — the service was not restarted (pid $(cat "$WORK/service.pid"))"

say "what is in the schemas (read as ad_sphere)"
for tier in btcl res_44 res_45; do
  ask ad_sphere "select '$tier.sum_ad_day_30     ' || concat_ws(' | ', tup_starttime, tup_tenant, 'partner ' || tup_partnerid, 'campaign ' || tup_campaignid, 'content ' || quote_literal(tup_contentid), 'rule ' || tup_rulecode, tup_zone, tup_app, tup_outcome, 'views ' || views, 'shown ' || shown, 'money ' || chargedamount, 'units ' || chargedunits) from $tier.sum_ad_day_30"
  ask ad_sphere "select '$tier.sum_ad_hr_30      ' || concat_ws(' | ', tup_starttime, 'views ' || views) from $tier.sum_ad_hr_30"
  ask ad_sphere "select '$tier.sum_voice_day_30  ' || concat_ws(' | ', tup_starttime, 'in-partner ' || tup_inpartnerid, tup_incomingroute || ' -> ' || tup_outgoingroute, 'calls ' || totalcalls, 'connected ' || connectedcalls, 'cost ' || customercost || ' ' || tup_customercurrency) from $tier.sum_voice_day_30"
  ask ad_sphere "select '$tier.sum_chargeable_day ' || concat_ws(' | ', 'group ' || tup_servicegroup, tup_billeduom, 'count ' || totalcount, 'billed ' || billedamount) from $tier.sum_chargeable_day"
  ask summary_service "select '$tier bookmarks          ' || string_agg(bean_name || '=' || last_offset, ' ' order by bean_name) from $tier.summary_offset"
done

say "the rights, as each role (D2)"
echo "summary_service: DELETE FROM btcl.cdr              -> $(ask summary_service "delete from btcl.cdr" 2>&1 | head -1)"
echo "summary_service: DELETE FROM btcl.cdr_p202610      -> $(ask summary_service "delete from btcl.cdr_p202610" 2>&1 | head -1)"
echo "summary_service: SELECT count(*) FROM btcl.cdr     -> $(ask summary_service "select count(*) from btcl.cdr" 2>&1 | head -1) row(s)"
echo "summary_service: DELETE FROM btcl.summary_affected WHERE id < 0 -> $(ask summary_service "delete from btcl.summary_affected where id < 0" 2>&1 | head -1 | sed 's/^$/allowed (0 rows)/')"
echo "ad_sphere:       DELETE FROM btcl.sum_ad_day_30    -> $(ask ad_sphere "delete from btcl.sum_ad_day_30" 2>&1 | head -1)"
echo "owner of btcl.summary_affected: $(ask prime_context "select tableowner from pg_tables where schemaname='btcl' and tablename='summary_affected'");  of btcl.sum_ad_day_30: $(ask prime_context "select tableowner from pg_tables where schemaname='btcl' and tablename='sum_ad_day_30'")"

say "the service's own words"
grep -E "is served|first bookmarks|is hearing|tenants:|no outbox|ERROR|WARN" "$WORK/service.log" | grep -v "kafka.clients\|ConsumerConfig\|AppInfoParser" | cut -c1-230 | head -40

if [ "$MODE" = "keep" ]; then echo; echo "left running (service pid $(cat "$WORK/service.pid"), log $WORK/service.log); remove with: $0 down"; exit 0; fi
echo; echo "removing: the service, the stand-in, the three schemas (the containers stay; tools/lab/pg-lab.sh down removes them)"
