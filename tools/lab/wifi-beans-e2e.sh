#!/usr/bin/env bash
# The WiFi beans end to end (SS-0006 item 5) with the PACKAGED jar on the lab profile (btcl/lab: the twelve beans), on BOTH
# engines. The story: 12 WiFi sessions as billing-core's BC-0008 rows write them land in sum_wifi_hr_101 / sum_wifi_day_101
# as the known rows (keys by column, the derived average), and in the 101 VOICE tables as before; 3 flow windows (one split
# in two outbox rows, the 5 000 split) land in sum_wifi_dst_hr / sum_wifi_dst_day as the known rows; the 30 VOICE tables are
# byte-for-byte unchanged by the sessions, and the 30 AND 101 VOICE tables byte-for-byte unchanged by the flows. Then the
# owner's two reports as queries — "average session duration per site per day" from sum_wifi_day_101 and "top destinations
# per site per hour" from sum_wifi_dst_hr — each equal to a separate query over the RAW rows of the same hour (the cdr rows and
# the wifi_flow_window rows this script writes beside the outbox rows, as billing-core writes them in one transaction).
#
#   tools/lab/wifi-beans-e2e.sh [pg | mysql | both]     (default both)
#
# The labs are THIS BOX. PostgreSQL: the container SS_LAB_PG (default ss-pg16-lab) on 127.0.0.1:SS_LAB_PG_PORT (default 7643;
# trust — no password exists). MySQL: 127.0.0.1:SS_LAB_MYSQL_PORT (default 7633, root, no password); a MySQL that needs one
# takes it by the NAME of a variable, SS_LAB_MYSQL_PASSWORD_VAR=<NAME>: the service gets summary.store.password-ref=env:<NAME>
# and this script's client reads the same variable — the value is on no command line and in no output. The service is
# started by tools/lab/run-lab.sh (the endpoints shown first; every host this box; the lab profile's Kafka is not needed — the
# workers drain on the poll). Maven reads MAVEN_ARGS (a private repository's flags) when the row tool's classpath has to be
# built. What the story makes is removed at the end; the containers stay. On MySQL the flow rows are the LAB's: in production
# billing-core writes wifi_flow on PostgreSQL only (the dst beans exist on MySQL and idle).
set -euo pipefail
cd "$(dirname "$0")/../.."

ENGINES=${1:-both}
PG=${SS_LAB_PG:-ss-pg16-lab}; PG_PORT=${SS_LAB_PG_PORT:-7643}
MY_HOST=127.0.0.1; MY_PORT=${SS_LAB_MYSQL_PORT:-7633}; MY_USER=${SS_LAB_MYSQL_USER:-root}; MY_PASSWORD_VAR=${SS_LAB_MYSQL_PASSWORD_VAR:-}
SCHEMA=lab_wifibeans
VIEWS=5; BEANS=12
WORK=target/lab-e2e; JAR=target/quarkus-app/quarkus-run.jar
mkdir -p "$WORK"
[ -f "$JAR" ] || { echo "no packaged application — run: mvn package -DskipTests"; exit 2; }

say() { printf '\n== %s ==\n' "$*"; }
service_pid=""
stop_service() { [ -n "$service_pid" ] && kill "$service_pid" 2>/dev/null || true; service_pid=""; sleep 2; }
pg()  { local role=$1; shift; docker exec -i "$PG" psql -U "$role" -d routesphere -q -v ON_ERROR_STOP=1 "$@"; }
pgv() { docker exec -i "$PG" psql -U "$1" -d routesphere -tA -c "$2" 2>/dev/null; }
my()  { if [ -n "$MY_PASSWORD_VAR" ]; then MYSQL_PWD="${!MY_PASSWORD_VAR}" mysql -h "$MY_HOST" -P "$MY_PORT" -u "$MY_USER" -N "$@"; else mysql -h "$MY_HOST" -P "$MY_PORT" -u "$MY_USER" -N "$@"; fi; }
myv() { my -e "$1" 2>/dev/null; }
cleanup() {
  stop_service
  pg postgres -c "DROP SCHEMA IF EXISTS $SCHEMA CASCADE" >/dev/null 2>&1 || true
  myv "DROP DATABASE IF EXISTS $SCHEMA" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

[ -s "$WORK/cp.txt" ] || mvn -o -q dependency:build-classpath -Dmdep.outputFile="$WORK/cp.txt" -Dmdep.includeScope=test >/dev/null
row() { java -cp "target/classes:target/test-classes:$(cat "$WORK/cp.txt")" com.telcobright.summary.testkit.LabOutboxRow "$@" 2>/dev/null; }

# ---- the known hour: 12 sessions (2026-10-16 09:xx, the tenant's wall clock), as BC-0008's columns carry them ------------
# start | site | zone | tier | category | package | seconds | bytesUp | bytesDn | unit | amount | cause | validFlag
SESSIONS=(
  "2026-10-16T09:02 mirpur-10 dhaka-north paid 2 wifi-30 1800 1000000 10000000 TF_min 30 NORMAL_CLEARING 1"
  "2026-10-16T09:05 mirpur-10 dhaka-north paid 2 wifi-30 1800 1000000 10000000 TF_min 30 NORMAL_CLEARING 1"
  "2026-10-16T09:08 mirpur-10 dhaka-north paid 2 wifi-30 1800 1000000 10000000 TF_min 30 NORMAL_CLEARING 1"
  "2026-10-16T09:11 mirpur-10 dhaka-north paid 2 wifi-30 1800 1000000 10000000 TF_min 30 NORMAL_CLEARING 1"
  "2026-10-16T09:14 mirpur-10 dhaka-north paid 2 wifi-30 600 500 5000 TF_min 10 MEDIA_TIMEOUT 1"
  "2026-10-16T09:17 mirpur-10 dhaka-north paid 2 wifi-30 900 200 2000 BDT 3.00 NORMAL_CLEARING 1"
  "2026-10-16T09:20 mirpur-10 dhaka-north paid 2 wifi-30 0 0 0 TF_min 0 NORMAL_CLEARING 1"
  "2026-10-16T09:23 mirpur-10 dhaka-north paid 2 wifi-30 300 0 0 TF_min 5 NORMAL_CLEARING 0"
  "2026-10-16T09:26 uttara-7 dhaka-north free 1 free-15 900 300 3000 TF_min 0 VOLUME_EXHAUSTED 1"
  "2026-10-16T09:29 uttara-7 dhaka-north free 1 free-15 900 300 3000 TF_min 0 VOLUME_EXHAUSTED 1"
  "2026-10-16T09:32 uttara-7 dhaka-north free 1 free-15 900 300 3000 TF_min 0 VOLUME_EXHAUSTED 1"
  "2026-10-16T09:35 uttara-7 dhaka-north free 1 free-15 120 10 100 TF_min 0 deauth:user 1"
)
# the flow windows (UTC on the wire = Dhaka 15:4x): W1 in TWO outbox rows (the 5 000 split), W2, W3 — WifiFlowTestSupport's known hour
W1=2026-10-16T09:41:00Z; W2=2026-10-16T09:42:00Z; W3=2026-10-16T09:43:00Z
W1A="mirpur-10,142.250.74.78,443,6,120,340,15200,410300;mirpur-10,142.250.74.78,443,6,10,20,1000,2000"
W1B="mirpur-10,1.1.1.1,53,17,5,5,300,700"
W2R="mirpur-10,142.250.74.78,443,6,30,60,3000,90000;uttara-7,1.1.1.1,53,17,2,2,100,200"
W3R="mirpur-10,142.250.74.78,443,6,1,1,100,100"
ROW_VIEW=$(row ad)

# ---- one engine's story ------------------------------------------------------------------------------------------
setup_pg() {
  pg prime_context -c "CREATE SCHEMA $SCHEMA" -c "GRANT USAGE, CREATE ON SCHEMA $SCHEMA TO summary_service, billing_core" \
     -c "ALTER DEFAULT PRIVILEGES FOR ROLE summary_service IN SCHEMA $SCHEMA GRANT SELECT ON TABLES TO ad_sphere" >/dev/null 2>&1
  pg billing_core <<SQL
SET search_path TO $SCHEMA;
CREATE TABLE cdr (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, servicegroup int NOT NULL, starttime timestamp, durationsec numeric(18,6),
  intrunkadditionalinfo text, outtrunkadditionalinfo text, matchedprefixy text, category int, matchedprefixcustomer text,
  xamount numeric(20,8), yamount numeric(20,8), validflag int, hangupcause text, inpartneruom text, packageamount numeric(18,6), inpartnercost numeric(18,6));
CREATE TABLE wifi_flow_window (window_start timestamptz NOT NULL, gw text, zone text, site text, mac text, remote text, port int, proto int,
  packets_up bigint, packets_dn bigint, bytes_up bigint, bytes_dn bigint);
CREATE TABLE summary_affected (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, entity_type varchar(32) NOT NULL,
  op varchar(8) NOT NULL DEFAULT 'add' CHECK (op IN ('add', 'subtract')), data text NOT NULL);
GRANT SELECT, DELETE ON summary_affected TO summary_service;
SQL
  OPTIONS=(-Dsummary.tenants.mode=single "-Dsummary.store.url=jdbc:postgresql://127.0.0.1:$PG_PORT/routesphere?currentSchema=$SCHEMA")
}
setup_mysql() {
  myv "CREATE DATABASE $SCHEMA" >/dev/null
  myv "CREATE TABLE $SCHEMA.cdr (id bigint AUTO_INCREMENT PRIMARY KEY, servicegroup int NOT NULL, starttime datetime, durationsec decimal(18,6),
    intrunkadditionalinfo varchar(64), outtrunkadditionalinfo varchar(64), matchedprefixy varchar(16), category int, matchedprefixcustomer varchar(64),
    xamount decimal(20,8), yamount decimal(20,8), validflag int, hangupcause varchar(64), inpartneruom varchar(16), packageamount decimal(18,6), inpartnercost decimal(18,6))" >/dev/null
  myv "CREATE TABLE $SCHEMA.wifi_flow_window (window_start datetime NOT NULL, gw varchar(64), zone varchar(64), site varchar(64), mac varchar(32), remote varchar(45), port int, proto int,
    packets_up bigint, packets_dn bigint, bytes_up bigint, bytes_dn bigint)" >/dev/null
  # the lab's partition horizon (the rows are of October 2026): twelve tables of 731 daily partitions each take minutes on a busy box
  OPTIONS=(-Dsummary.tenants.mode=single -Dsummary.store.kind=mysql "-Dsummary.store.username=$MY_USER"
           -Dsummary.ddl.partition-start=2026-09-01 -Dsummary.ddl.partition-days=60
           "-Dsummary.store.url=jdbc:mysql://$MY_HOST:$MY_PORT/$SCHEMA?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true")
  [ -n "$MY_PASSWORD_VAR" ] && OPTIONS+=("-Dsummary.store.password-ref=env:$MY_PASSWORD_VAR")
  true
}
sql() { if [ "$1" = pg ]; then pgv billing_core "$2" >/dev/null; else myv "START TRANSACTION; $2; COMMIT" >/dev/null; fi; }   # one transaction, as billing-core writes
q() { if [ "$1" = pg ]; then pgv postgres "$2"; else myv "$2"; fi; }                                                         # a read as the lab's superuser
write_views() { sql "$1" "INSERT INTO $SCHEMA.cdr (servicegroup) SELECT 30 FROM $SCHEMA.cdr LIMIT 0; $(for _ in $(seq 1 "$VIEWS"); do echo "INSERT INTO $SCHEMA.cdr (servicegroup) VALUES (30); INSERT INTO $SCHEMA.summary_affected (entity_type, op, data) VALUES ('cdr', 'add', '$ROW_VIEW');"; done)"; }
write_session() {   # $1 engine, the 13 facts: ONE cdr row with the raw columns + ONE outbox row (BC-0008's blob) in ONE transaction
  local e=$1 start=$2 site=$3 zone=$4 tier=$5 cat=$6 pkg=$7 sec=$8 up=$9 dn=${10} unit=${11} amt=${12} cause=${13} valid=${14}
  local blob; blob=$(row wifi-bc0008 "$start" "$site" "$zone" "$tier" "$cat" "$pkg" "$sec" "$up" "$dn" "$unit" "$amt" "$cause" "$valid")
  local minutes=0 money=0; if [ "$unit" = BDT ]; then money=$amt; else minutes=$amt; fi
  sql "$e" "INSERT INTO $SCHEMA.cdr (servicegroup, starttime, durationsec, intrunkadditionalinfo, outtrunkadditionalinfo, matchedprefixy, category, matchedprefixcustomer, xamount, yamount, validflag, hangupcause, inpartneruom, packageamount, inpartnercost)
     VALUES (101, '${start/T/ }:00', $sec, '$site', '$zone', '$tier', $cat, '$pkg', $up, $dn, $valid, '$cause', '$unit', $minutes, $money);
     INSERT INTO $SCHEMA.summary_affected (entity_type, op, data) VALUES ('cdr', 'add', '$blob')"
}
write_window() {   # $1 engine, $2 window_start (UTC), $3 rows: the wifi_flow_window rows + ONE wifi_flow outbox row carrying them, in ONE transaction
  local e=$1 w=$2 rows=$3 blob inserts="" n=0 r; blob=$(row wifi-flow "$w" "$rows")
  local wall; if [ "$e" = pg ]; then wall="'$w'"; else wall="CONVERT_TZ('${w/T/ }', '+00:00', '+06:00')"; wall=${wall/Z\'/\'}; fi
  IFS=';' read -ra R <<< "$rows"
  for r in "${R[@]}"; do IFS=',' read -r site remote port proto pu pd bu bd <<< "$r"; n=$((n + 1))
    inserts+="INSERT INTO $SCHEMA.wifi_flow_window VALUES ($wall, 'bras-2', 'dhaka-north', '$site', 'aa:bb:cc:dd:ee:0$n', '$remote', $port, $proto, $pu, $pd, $bu, $bd); "
  done
  sql "$e" "$inserts INSERT INTO $SCHEMA.summary_affected (entity_type, op, data) VALUES ('wifi_flow', 'add', '$blob')"
}
written()  { q "$1" "select coalesce(max(id),0) from $SCHEMA.summary_affected"; }
# how many of the twelve beans are at the last outbox row OF THEIR OWN ENTITY (a wifi_flow bean never moves over cdr rows, nor the other way)
caughtUp() { q "$1" "select count(*) from $SCHEMA.summary_offset o where o.last_offset >= (select coalesce(max(a.id), 0) from $SCHEMA.summary_affected a where a.entity_type = o.entity_type)"; }
waitCaughtUp() {   # $1 engine, $2 seconds at most
  local last; last=$(written "$1"); local started; started=$(date +%s)
  for _ in $(seq 1 $(( $2 * 5 ))); do
    if [ "$(caughtUp "$1" "$last")" = "$BEANS" ]; then echo "$(( $(date +%s) - started )) s: $BEANS of $BEANS beans at outbox row $last"; return 0; fi
    sleep 0.2
  done
  echo "NOT CAUGHT UP after $2 s: $(caughtUp "$1" "$last") of $BEANS beans at row $last"; return 1
}
rowsOf() {   # $1 engine, $2 a voice table: every row as one text (payer | unit | calls | cost | seconds), to see that nothing of it changed
  if [ "$1" = pg ]; then q pg "select coalesce(string_agg(concat_ws('|', tup_inpartnerid, tup_customercurrency, totalcalls, customercost, actualduration), ';' order by id), '') from $SCHEMA.$2"
  else q mysql "select ifnull(group_concat(concat_ws('|', tup_inpartnerid, tup_customercurrency, totalcalls, customercost, actualduration) order by id separator ';'), '') from $SCHEMA.$2"; fi
}
wifi() {   # $1 engine, $2 a wifi session table, $3 site, $4 unit, $5 family: "sessions|connected|total|avg|up|dn|cost"
  q "$1" "select coalesce((select concat_ws('|', sessions, sessions_connected, total_duration_sec, avg_session_sec, bytes_up, bytes_dn, customercost) from $SCHEMA.$2 where tup_site = '$3' and tup_customercurrency = '$4' and tup_causefamily = '$5'), 'none')"
}
dst() {    # $1 engine, $2 a dst table, $3 site, $4 remote, $5 port, $6 proto: "flows|up|dn|pu|pd"
  q "$1" "select coalesce((select concat_ws('|', flows, bytes_up, bytes_dn, packets_up, packets_dn) from $SCHEMA.$2 where tup_site = '$3' and tup_remote = '$4' and tup_port = $5 and tup_proto = $6), 'none')"
}
check() { if [ "$2" = "$3" ]; then echo "ok    $1 = $3"; else echo "FAIL  $1: expected $2, read $3"; verdict=1; fi; }
start_service() {   # $1 engine, $2 log; waits for the start's verdict
  tools/lab/run-lab.sh btcl/lab "${OPTIONS[@]}" > "$2" 2>&1 &
  service_pid=$!
  for _ in $(seq 1 600); do grep -q "is served: $BEANS of $BEANS\|could NOT be served\|NOT STARTED\|REFUSING" "$2" && break; sleep 0.5; done
}
agg() { if [ "$1" = pg ]; then echo "string_agg($2, ';' order by $3)"; else echo "group_concat($2 order by $3 separator ';')"; fi; }

story() {
  local engine=$1
  local log="$WORK/wifi-beans-$engine.log" verdict=0 day30 hr30 day101 hr101 wday whr s table

  say "$engine · 1 · the service serves $SCHEMA with the lab profile's twelve beans"
  "setup_$engine"
  start_service "$engine" "$log"
  grep -q "is served: $BEANS of $BEANS" "$log" || { echo "not served:"; grep -E "ERROR|REFUS|NOT STARTED" "$log" | tail -8 | cut -c1-260; return 1; }
  grep -E "^ENDPOINT |^SECRET " "$log" | cut -c1-200
  grep "bean registered" "$log" | grep -o "name=[A-Za-z0-9]* .*table=[a-z_0-9]*" | grep -i wifi | sed 's/^/   /'
  if [ "$engine" = mysql ]; then for _ in $(seq 1 60); do [ -n "$(myv "show tables in $SCHEMA like 'summary_affected'")" ] && break; sleep 0.5; done; fi

  say "$engine · 2 · $VIEWS ad views (group 30) first; the 30 tables as they stand after them"
  write_views "$engine"
  waitCaughtUp "$engine" 60 || return 1
  day30=$(rowsOf "$engine" sum_voice_day_30); hr30=$(rowsOf "$engine" sum_voice_hr_30)
  echo "sum_voice_day_30: $day30"

  say "$engine · 3 · 12 WiFi sessions as BC-0008 writes them (one cdr row with the columns + one outbox row each, one transaction) — 8 at mirpur-10 (4 × 30 TF_min, one idle, one in money, one of 0 s, one unmeasured), 4 free at uttara-7"
  for s in "${SESSIONS[@]}"; do read -ra F <<< "$s"; write_session "$engine" "${F[@]}"; done
  waitCaughtUp "$engine" 90 || verdict=1

  say "$engine · 4 · sum_wifi_hr_101 / sum_wifi_day_101: the known rows (sessions|connected|total|avg|up|dn|cost), keys by column"
  for table in sum_wifi_hr_101 sum_wifi_day_101; do
    check "$table mirpur-10 TF_min other (4 × 1800 s + the 300 s unmeasured one, 125 TF_min)" "5|5|7500|1500.000000|4000000|40000000|125.000000" "$(wifi "$engine" "$table" mirpur-10 TF_min other)"
    check "$table mirpur-10 TF_min idle (MEDIA_TIMEOUT)" "1|1|600|600.000000|500|5000|10.000000" "$(wifi "$engine" "$table" mirpur-10 TF_min idle)"
    check "$table mirpur-10 BDT other (paid in money)" "1|1|900|900.000000|200|2000|3.000000" "$(wifi "$engine" "$table" mirpur-10 BDT other)"
    check "$table mirpur-10 no unit (the 0-s session: counted, not connected, not costed)" "1|0|0|0.000000|0|0|0.000000" "$(wifi "$engine" "$table" mirpur-10 '' other)"
    check "$table uttara-7 TF_min volume (free WiFi, VOLUME_EXHAUSTED)" "3|3|2700|900.000000|900|9000|0.000000" "$(wifi "$engine" "$table" uttara-7 TF_min volume)"
    check "$table uttara-7 TF_min deauth (deauth:user)" "1|1|120|120.000000|10|100|0.000000" "$(wifi "$engine" "$table" uttara-7 TF_min deauth)"
    check "$table rows" "6" "$(q "$engine" "select count(*) from $SCHEMA.$table")"
    check "$table sum(sessions) = the cdr rows of group 101" "$(q "$engine" "select count(*) from $SCHEMA.cdr where servicegroup = 101")" "$(q "$engine" "select coalesce(sum(sessions),0) from $SCHEMA.$table")"
    check "$table keyed by the grant tier and the package (paid/2/wifi-30 at mirpur-10, free/1/free-15 at uttara-7)" "free|1|free-15;paid|2|wifi-30" \
          "$(q "$engine" "select $(agg "$engine" "concat_ws('|', tup_granttier, tup_category, tup_packagecode)" "tup_granttier") from (select distinct tup_granttier, tup_category, tup_packagecode from $SCHEMA.$table) k")"
  done
  check "sum_wifi_hr_101 bucket" "2026-10-16 09:00:00" "$(q "$engine" "select min(tup_starttime) from $SCHEMA.sum_wifi_hr_101" | cut -c1-19)"
  check "the 101 VOICE tables count the same 12 sessions (as before this round)" "12" "$(q "$engine" "select coalesce(sum(totalcalls),0) from $SCHEMA.sum_voice_day_101")"
  check "sum_voice_day_30 unchanged by the sessions" "$day30" "$(rowsOf "$engine" sum_voice_day_30)"
  check "sum_voice_hr_30 unchanged by the sessions" "$hr30" "$(rowsOf "$engine" sum_voice_hr_30)"
  day101=$(rowsOf "$engine" sum_voice_day_101); hr101=$(rowsOf "$engine" sum_voice_hr_101); wday=$(q "$engine" "select $(agg "$engine" "concat_ws('|', tup_site, tup_customercurrency, tup_causefamily, sessions, total_duration_sec, bytes_dn)" "id") from $SCHEMA.sum_wifi_day_101")

  say "$engine · 5 · 3 flow windows (W1 in two outbox rows — the 5 000 split — W2, W3): the wifi_flow entity -> sum_wifi_dst_hr / sum_wifi_dst_day"
  write_window "$engine" "$W1" "$W1A"; write_window "$engine" "$W1" "$W1B"; write_window "$engine" "$W2" "$W2R"; write_window "$engine" "$W3" "$W3R"
  waitCaughtUp "$engine" 90 || verdict=1
  for table in sum_wifi_dst_hr sum_wifi_dst_day; do
    check "$table mirpur-10 -> 142.250.74.78:443/6 (r1 + r2 + r4 + r6: two devices, the split seen as one)" "4|19300|502400|161|421" "$(dst "$engine" "$table" mirpur-10 142.250.74.78 443 6)"
    check "$table mirpur-10 -> 1.1.1.1:53/17" "1|300|700|5|5" "$(dst "$engine" "$table" mirpur-10 1.1.1.1 53 17)"
    check "$table uttara-7 -> 1.1.1.1:53/17" "1|100|200|2|2" "$(dst "$engine" "$table" uttara-7 1.1.1.1 53 17)"
    check "$table rows" "3" "$(q "$engine" "select count(*) from $SCHEMA.$table")"
    check "$table sum(flows) = the wifi_flow_window rows" "$(q "$engine" "select count(*) from $SCHEMA.wifi_flow_window")" "$(q "$engine" "select coalesce(sum(flows),0) from $SCHEMA.$table")"
  done
  check "sum_wifi_dst_hr bucket: 09:41Z on the tenant's clock" "2026-10-16 15:00:00" "$(q "$engine" "select min(tup_starttime) from $SCHEMA.sum_wifi_dst_hr" | cut -c1-19)"
  check "the dst beans' bookmarks are their own, at the last row" "2" "$(q "$engine" "select count(*) from $SCHEMA.summary_offset where entity_type = 'wifi_flow' and last_offset = $(written "$engine")")"
  check "sum_voice_day_30 unchanged by the flows" "$day30" "$(rowsOf "$engine" sum_voice_day_30)"
  check "sum_voice_hr_30 unchanged by the flows" "$hr30" "$(rowsOf "$engine" sum_voice_hr_30)"
  check "sum_voice_day_101 unchanged by the flows" "$day101" "$(rowsOf "$engine" sum_voice_day_101)"
  check "sum_voice_hr_101 unchanged by the flows" "$hr101" "$(rowsOf "$engine" sum_voice_hr_101)"
  check "sum_wifi_day_101 unchanged by the flows" "$wday" "$(q "$engine" "select $(agg "$engine" "concat_ws('|', tup_site, tup_customercurrency, tup_causefamily, sessions, total_duration_sec, bytes_dn)" "id") from $SCHEMA.sum_wifi_day_101")"

  say "$engine · 6 · the owner's two reports as queries, each beside a separate query over the RAW rows of the same hour"
  local r1 r1raw r2 r2raw DIV
  # a report over a range recomputes the average from the two totals (never averages the averages); a decimal division on both engines
  if [ "$engine" = pg ]; then DIV="sum(total_duration_sec)::numeric / sum(sessions_connected)"; else DIV="cast(sum(total_duration_sec) as decimal(24,6)) / sum(sessions_connected)"; fi
  r1=$(q "$engine" "select $(agg "$engine" "concat_ws('|', tup_site, avg_sec)" "tup_site") from (select tup_site, round($DIV, 6) as avg_sec from $SCHEMA.sum_wifi_day_101 where tup_starttime = '2026-10-16 00:00:00' group by tup_site) d")
  r1raw=$(q "$engine" "select $(agg "$engine" "concat_ws('|', site, avg_sec)" "site") from (select intrunkadditionalinfo as site, round(sum(durationsec) / sum(case when durationsec > 0 then 1 else 0 end), 6) as avg_sec from $SCHEMA.cdr where servicegroup = 101 and starttime >= '2026-10-16 09:00:00' and starttime < '2026-10-16 10:00:00' group by intrunkadditionalinfo) r")
  echo "average session duration per site per day (sum_wifi_day_101): $r1"
  check "= the same from the raw cdr rows of the hour (the mean over the connected sessions)" "$r1raw" "$r1"
  check "and it is mirpur-10 9000/7, uttara-7 2820/4" "mirpur-10|1285.714286;uttara-7|705.000000" "$r1"
  r2=$(q "$engine" "select $(agg "$engine" "concat_ws('|', tup_site, tup_remote, total)" "tup_site, total desc, tup_remote") from (select tup_site, tup_remote, sum(bytes_up + bytes_dn) as total from $SCHEMA.sum_wifi_dst_hr where tup_starttime = '2026-10-16 15:00:00' group by tup_site, tup_remote) d")
  r2raw=$(q "$engine" "select $(agg "$engine" "concat_ws('|', site, remote, total)" "site, total desc, remote") from (select site, remote, sum(bytes_up + bytes_dn) as total from $SCHEMA.wifi_flow_window group by site, remote) r")
  echo "top destinations per site per hour (sum_wifi_dst_hr): $r2"
  check "= the same from the raw wifi_flow_window rows of the hour" "$r2raw" "$r2"
  check "and it is mirpur-10: 142.250.74.78 then 1.1.1.1; uttara-7: 1.1.1.1" "mirpur-10|142.250.74.78|521700;mirpur-10|1.1.1.1|1000;uttara-7|1.1.1.1|300" "$r2"
  echo "ERROR lines of the service: $(grep -c " ERROR " "$log" || true) (the ping listener's broker is not in this lab: a WARN, the workers drain on the poll)"
  stop_service

  [ "$verdict" = 0 ] && echo "$engine HOLDS: the sessions in the wifi 101 tables as the known rows, the flows in the dst tables, the voice tables unchanged, the two reports equal to the raw rows" \
                     || echo "$engine DOES NOT HOLD — see above (log: $log)"
  return "$verdict"
}

result=0
case "$ENGINES" in
  pg)    story pg || result=1 ;;
  mysql) story mysql || result=1 ;;
  both)  story pg || result=1; cleanup; story mysql || result=1 ;;
  *)     echo "usage: $0 [pg | mysql | both]"; exit 2 ;;
esac
echo; [ "$result" = 0 ] && echo "SS-0006 item 5 HOLDS on $ENGINES: the WiFi session and destination beans sum the known rows on the packaged jar; the voice tables are unchanged; the owner's two reports equal the raw rows." \
                        || echo "SS-0006 item 5 DOES NOT HOLD — see above."
echo "removing: the service, the schema / database (the containers stay)"
exit "$result"
