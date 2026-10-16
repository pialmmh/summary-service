#!/usr/bin/env bash
# The WiFi SESSION sums split by BRAS (SS-0007 item 2): tup_gw = the cdr's InMgwId (the meta gw billing-core writes there,
# WifiColumns.java:84) — and the UPGRADE of a deployment whose sum_wifi_hr_101 / sum_wifi_day_101 already HOLD ROWS, with
# two PACKAGED jars, on BOTH engines ("a table made by 3829e18's spec that holds rows -> the new version's first use adds
# tup_gw and the old rows read ''; new rows carry their BRAS; a second start changes nothing"):
#
#   1. the OLDER version — SS_OLDER_APP, a project directory holding its own packaged target/quarkus-app and its own
#      tools/lab/run-lab.sh (a clean copy of 3829e18: git archive 3829e18, then mvn -o package -DskipTests there) —
#      serves the schema with the lab profile's twelve beans and sums four sessions into the two WiFi session tables, which
#      it makes WITHOUT tup_gw (the records name their BRAS already: it does not read it); it is stopped, its rows are read
#   2. THIS version starts on the same schema: one WARN line per older table — the ALTER it ran — and no other table is
#      changed; the rows it finds are as they were (every id, every number), each with tup_gw = ''
#   3. five sessions after the upgrade: mirpur-10 on bras-1 twice and on bras-2 once -> a row of each BRAS; one whose
#      record names no BRAS -> into mirpur-10's older '' row; uttara-7 on bras-2 -> a row beside uttara-7's older one; the
#      101 VOICE tables count every session as before (the call beans do not split by BRAS)
#   4. THIS version starts again: no change said; the rows as they were; nothing counted twice
#
#   SS_OLDER_APP=<dir> tools/lab/wifi-gw-upgrade-e2e.sh [pg | mysql | both]     (default both)
#
# The labs are THIS BOX, as tools/lab/wifi-beans-e2e.sh has them: PostgreSQL = the container SS_LAB_PG (default
# ss-pg16-lab) on 127.0.0.1:SS_LAB_PG_PORT (default 7643; trust — no password exists); MySQL = 127.0.0.1:SS_LAB_MYSQL_PORT
# (default 7633), root, a password — if it needs one — by the NAME of a variable, SS_LAB_MYSQL_PASSWORD_VAR=<NAME> (the
# service gets summary.store.password-ref=env:<NAME>, this script's client reads the same variable; the value is on no
# command line and in no output). Each version is started by ITS OWN tools/lab/run-lab.sh (the endpoints shown first;
# every host this box; no broker — the workers drain on the poll). Maven reads MAVEN_ARGS (a private repository's flags)
# when the row tool's classpath has to be built. What the story makes is removed at the end; the containers stay.
set -euo pipefail
cd "$(dirname "$0")/../.."

ENGINES=${1:-both}
OLDER=${SS_OLDER_APP:?"SS_OLDER_APP must name the older version's project directory, packaged (its target/quarkus-app and tools/lab/run-lab.sh)"}
PG=${SS_LAB_PG:-ss-pg16-lab}; PG_PORT=${SS_LAB_PG_PORT:-7643}
MY_HOST=127.0.0.1; MY_PORT=${SS_LAB_MYSQL_PORT:-7633}; MY_USER=${SS_LAB_MYSQL_USER:-root}; MY_PASSWORD_VAR=${SS_LAB_MYSQL_PASSWORD_VAR:-}
SCHEMA=lab_wifigw
BEANS=12
WORK=target/lab-e2e; JAR=target/quarkus-app/quarkus-run.jar
mkdir -p "$WORK"
[ -f "$JAR" ] || { echo "no packaged application — run: mvn package -DskipTests"; exit 2; }
[ -f "$OLDER/$JAR" ] && [ -x "$OLDER/tools/lab/run-lab.sh" ] || { echo "SS_OLDER_APP=$OLDER has no packaged $JAR or no tools/lab/run-lab.sh"; exit 2; }

say() { printf '\n== %s ==\n' "$*"; }
service_pid=""
stop_service() {   # the service, and wait until it is gone (the next version takes the same HTTP port)
  [ -n "$service_pid" ] || return 0
  kill "$service_pid" 2>/dev/null || true
  for _ in $(seq 1 60); do kill -0 "$service_pid" 2>/dev/null || break; sleep 0.5; done
  service_pid=""
}
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

# ---- the sessions (2026-10-16 09:xx, the tenant's wall clock), as BC-0008 writes them, each with the BRAS its record names ----
# start | site | zone | tier | category | package | seconds | bytesUp | bytesDn | unit | amount | cause | validFlag | gw ('-' = the record names none)
BEFORE=(
  "2026-10-16T09:02 mirpur-10 dhaka-north paid 2 wifi-30 1800 1000000 10000000 TF_min 30 NORMAL_CLEARING 1 bras-2"
  "2026-10-16T09:05 mirpur-10 dhaka-north paid 2 wifi-30 1800 1000000 10000000 TF_min 30 NORMAL_CLEARING 1 bras-2"
  "2026-10-16T09:08 mirpur-10 dhaka-north paid 2 wifi-30 600 500 5000 TF_min 10 NORMAL_CLEARING 1 bras-1"
  "2026-10-16T09:11 uttara-7 dhaka-north free 1 free-15 900 300 3000 TF_min 0 NORMAL_CLEARING 1 bras-2"
)
AFTER=(
  "2026-10-16T09:20 mirpur-10 dhaka-north paid 2 wifi-30 1800 1000000 10000000 TF_min 30 NORMAL_CLEARING 1 bras-1"
  "2026-10-16T09:23 mirpur-10 dhaka-north paid 2 wifi-30 1800 1000000 10000000 TF_min 30 NORMAL_CLEARING 1 bras-1"
  "2026-10-16T09:26 mirpur-10 dhaka-north paid 2 wifi-30 600 500 5000 TF_min 10 NORMAL_CLEARING 1 bras-2"
  "2026-10-16T09:29 mirpur-10 dhaka-north paid 2 wifi-30 900 200 2000 TF_min 15 NORMAL_CLEARING 1 -"
  "2026-10-16T09:32 uttara-7 dhaka-north free 1 free-15 900 300 3000 TF_min 0 NORMAL_CLEARING 1 bras-2"
)
TABLES="sum_wifi_day_101 sum_wifi_hr_101"

# ---- one engine's story ------------------------------------------------------------------------------------------
setup_pg() {
  pg prime_context -c "CREATE SCHEMA $SCHEMA" -c "GRANT USAGE, CREATE ON SCHEMA $SCHEMA TO summary_service, billing_core" >/dev/null 2>&1
  pg billing_core <<SQL
SET search_path TO $SCHEMA;
CREATE TABLE cdr (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, servicegroup int NOT NULL, starttime timestamp, durationsec numeric(18,6),
  intrunkadditionalinfo text, inmgwid text);
CREATE TABLE summary_affected (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, entity_type varchar(32) NOT NULL,
  op varchar(8) NOT NULL DEFAULT 'add' CHECK (op IN ('add', 'subtract')), data text NOT NULL);
GRANT SELECT, DELETE ON summary_affected TO summary_service;
SQL
  OPTIONS=(-Dsummary.tenants.mode=single "-Dsummary.store.url=jdbc:postgresql://127.0.0.1:$PG_PORT/routesphere?currentSchema=$SCHEMA")
}
setup_mysql() {
  myv "CREATE DATABASE $SCHEMA" >/dev/null
  myv "CREATE TABLE $SCHEMA.cdr (id bigint AUTO_INCREMENT PRIMARY KEY, servicegroup int NOT NULL, starttime datetime, durationsec decimal(18,6),
    intrunkadditionalinfo varchar(64), inmgwid varchar(64))" >/dev/null
  OPTIONS=(-Dsummary.tenants.mode=single -Dsummary.store.kind=mysql "-Dsummary.store.username=$MY_USER"
           -Dsummary.ddl.partition-start=2026-09-01 -Dsummary.ddl.partition-days=60
           "-Dsummary.store.url=jdbc:mysql://$MY_HOST:$MY_PORT/$SCHEMA?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true")
  [ -n "$MY_PASSWORD_VAR" ] && OPTIONS+=("-Dsummary.store.password-ref=env:$MY_PASSWORD_VAR")
  true
}
sql() { if [ "$1" = pg ]; then pgv billing_core "$2" >/dev/null; else myv "START TRANSACTION; $2; COMMIT" >/dev/null; fi; }   # one transaction, as billing-core writes
q() { if [ "$1" = pg ]; then pgv postgres "$2"; else myv "$2"; fi; }                                                         # a read as the lab's superuser
agg() { if [ "$1" = pg ]; then echo "string_agg($2, ';' order by $3)"; else echo "group_concat($2 order by $3 separator ';')"; fi; }
write_session() {   # $1 engine, the 14 facts: ONE cdr row + ONE outbox row (BC-0008's blob) in ONE transaction
  local e=$1 start=$2 site=$3 sec=$8 gw=${15}
  local blob; blob=$(row wifi-bc0008 "${@:2:13}" "$gw")
  local raw="'$gw'"; [ "$gw" = "-" ] && raw=NULL
  sql "$e" "INSERT INTO $SCHEMA.cdr (servicegroup, starttime, durationsec, intrunkadditionalinfo, inmgwid) VALUES (101, '${start/T/ }:00', $sec, '$site', $raw);
     INSERT INTO $SCHEMA.summary_affected (entity_type, op, data) VALUES ('cdr', 'add', '$blob')"
}
written()  { q "$1" "select coalesce(max(id),0) from $SCHEMA.summary_affected"; }
caughtUp() { q "$1" "select count(*) from $SCHEMA.summary_offset o where o.last_offset >= (select coalesce(max(a.id), 0) from $SCHEMA.summary_affected a where a.entity_type = o.entity_type)"; }
waitCaughtUp() {   # $1 engine, $2 seconds at most
  local last; last=$(written "$1"); local started; started=$(date +%s)
  for _ in $(seq 1 $(( $2 * 5 ))); do
    if [ "$(caughtUp "$1")" = "$BEANS" ]; then echo "$(( $(date +%s) - started )) s: $BEANS of $BEANS beans at outbox row $last"; return 0; fi
    sleep 0.2
  done
  echo "NOT CAUGHT UP after $2 s: $(caughtUp "$1") of $BEANS beans at row $last"; return 1
}
# every row of a session table as it stood before this version (no tup_gw): id|site|unit|family|sessions|connected|total|avg|up|dn|cost
olderRows() { q "$1" "select $(agg "$1" "concat_ws('|', id, tup_site, tup_customercurrency, tup_causefamily, sessions, sessions_connected, total_duration_sec, avg_session_sec, bytes_up, bytes_dn, customercost)" id) from $SCHEMA.$2"; }
# every row with its BRAS: id|gw|site|sessions|total|cost
gwRows()    { q "$1" "select $(agg "$1" "concat_ws('|', id, tup_gw, tup_site, sessions, total_duration_sec, customercost)" id) from $SCHEMA.$2"; }
gwRow() {    # $1 engine, $2 table, $3 site, $4 gw: "sessions|connected|total|avg|up|dn|cost"
  q "$1" "select coalesce((select concat_ws('|', sessions, sessions_connected, total_duration_sec, avg_session_sec, bytes_up, bytes_dn, customercost) from $SCHEMA.$2 where tup_site = '$3' and tup_gw = '$4'), 'none')"
}
gwColumn() { q "$1" "select concat_ws('|', column_name, data_type, character_maximum_length, is_nullable, coalesce(column_default, 'NULL'), ordinal_position) from information_schema.columns where table_schema = '$SCHEMA' and table_name = '$2' and column_name = 'tup_gw'"; }
check() { if [ "$2" = "$3" ]; then echo "ok    $1 = $3"; else echo "FAIL  $1: expected $2, read $3"; verdict=1; fi; }
start_version() {   # $1 the version's project directory, $2 the log: started by ITS OWN run-lab.sh; waits for the start's verdict
  "$1/tools/lab/run-lab.sh" btcl/lab "${OPTIONS[@]}" > "$2" 2>&1 &
  service_pid=$!
  for _ in $(seq 1 600); do grep -q "is served: $BEANS of $BEANS\|could NOT be served\|NOT STARTED\|REFUSING" "$2" && break; sleep 0.5; done
  grep -q "is served: $BEANS of $BEANS" "$2" || { echo "not served:"; grep -E "ERROR|REFUS|NOT STARTED" "$2" | tail -8 | cut -c1-260; return 1; }
  grep -E "^ENDPOINT |^SECRET " "$2" | cut -c1-200
}
changesSaid() { grep -o "table [a-z0-9_]* was made by an earlier version — bringing it up to its description: .*" "$1" || true; }
errorLines() { grep -c " ERROR " "$1" || true; }
olderIdAt() {   # $1 table, $2 site: the id the older version gave that site's row (from the rows read before the upgrade)
  local rows; if [ "$1" = sum_wifi_day_101 ]; then rows=$before_day; else rows=$before_hr; fi
  printf '%s\n' "$rows" | tr ';' '\n' | grep "^[0-9]*|$2|" | cut -d'|' -f1
}

story() {
  local engine=$1 verdict=0 t said n add before_day before_hr   # olderIdAt reads the two before_* (bash scope: a callee sees them)
  local log1="$WORK/wifi-gw-older-$engine.log" log2="$WORK/wifi-gw-this-$engine.log" log3="$WORK/wifi-gw-again-$engine.log"

  say "$engine · 1 · the OLDER version ($(cd "$OLDER" && pwd)) serves $SCHEMA and sums ${#BEFORE[@]} sessions; it makes the WiFi session tables without tup_gw"
  "setup_$engine"
  start_version "$OLDER" "$log1" || return 1
  if [ "$engine" = mysql ]; then for _ in $(seq 1 60); do [ -n "$(myv "show tables in $SCHEMA like 'summary_affected'")" ] && break; sleep 0.5; done; fi
  for s in "${BEFORE[@]}"; do read -ra F <<< "$s"; write_session "$engine" "${F[@]}"; done
  waitCaughtUp "$engine" 90 || verdict=1
  check "ERROR lines of the older service" "0" "$(errorLines "$log1")"
  stop_service
  for t in $TABLES; do
    check "$t made by the older version: no tup_gw" "" "$(gwColumn "$engine" "$t")"
    check "$t rows (the older version's sums)" "2" "$(q "$engine" "select count(*) from $SCHEMA.$t")"
  done
  check "sum_wifi_day_101 mirpur-10 (three sessions, two of them named bras-2, one bras-1 — one row: the older key has no BRAS)" \
        "3|3|4200|1400.000000|2000500|20005000|70.000000" "$(q "$engine" "select concat_ws('|', sessions, sessions_connected, total_duration_sec, avg_session_sec, bytes_up, bytes_dn, customercost) from $SCHEMA.sum_wifi_day_101 where tup_site = 'mirpur-10'")"
  before_day=$(olderRows "$engine" sum_wifi_day_101); before_hr=$(olderRows "$engine" sum_wifi_hr_101)   # read by olderIdAt too
  echo "sum_wifi_day_101 as the older version left it (id|site|unit|family|sessions|connected|total|avg|up|dn|cost): $before_day"

  say "$engine · 2 · THIS version starts on the same schema: it brings the older tables up to its description"
  start_version . "$log2" || return 1
  said=$(changesSaid "$log2"); n=$(printf '%s' "$said" | grep -c . || true)
  printf '%s\n' "$said" | sed 's/^/   /'
  check "changes said (the two WiFi session tables, one ADD COLUMN each; no other table)" "2" "$n"
  if [ "$engine" = pg ]; then add="ADD COLUMN IF NOT EXISTS"; else add="ADD COLUMN"; fi
  for t in $TABLES; do
    check "$t: the ALTER this version ran" "1" "$(printf '%s\n' "$said" | grep -cxF "table $t was made by an earlier version — bringing it up to its description: ALTER TABLE $t $add tup_gw VARCHAR(64) NOT NULL DEFAULT ''" || true)"
    echo "   $t tup_gw now (name|type|width|nullable|default|position): $(gwColumn "$engine" "$t")"
    check "$t tup_gw: 64 wide, NOT NULL" "64|NO" "$(q "$engine" "select concat_ws('|', character_maximum_length, is_nullable) from information_schema.columns where table_schema = '$SCHEMA' and table_name = '$t' and column_name = 'tup_gw'")"
    check "$t: the older rows read the empty BRAS" "0" "$(q "$engine" "select count(*) from $SCHEMA.$t where tup_gw <> ''")"
  done
  check "sum_wifi_day_101: the older rows as they were (every id, every number)" "$before_day" "$(olderRows "$engine" sum_wifi_day_101)"
  check "sum_wifi_hr_101: the older rows as they were (every id, every number)" "$before_hr" "$(olderRows "$engine" sum_wifi_hr_101)"
  check "ERROR lines of this version, its first start" "0" "$(errorLines "$log2")"

  say "$engine · 3 · ${#AFTER[@]} sessions after the upgrade: mirpur-10 on bras-1 ×2 and bras-2 ×1, one naming no BRAS; uttara-7 on bras-2"
  for s in "${AFTER[@]}"; do read -ra F <<< "$s"; write_session "$engine" "${F[@]}"; done
  waitCaughtUp "$engine" 90 || verdict=1
  for t in $TABLES; do
    check "$t mirpur-10 '' (the three older sessions + the one naming no BRAS)" "4|4|5100|1275.000000|2000700|20007000|85.000000" "$(gwRow "$engine" "$t" mirpur-10 '')"
    check "$t mirpur-10 bras-1" "2|2|3600|1800.000000|2000000|20000000|60.000000" "$(gwRow "$engine" "$t" mirpur-10 bras-1)"
    check "$t mirpur-10 bras-2" "1|1|600|600.000000|500|5000|10.000000" "$(gwRow "$engine" "$t" mirpur-10 bras-2)"
    check "$t uttara-7 '' (the older row, untouched)" "1|1|900|900.000000|300|3000|0.000000" "$(gwRow "$engine" "$t" uttara-7 '')"
    check "$t uttara-7 bras-2" "1|1|900|900.000000|300|3000|0.000000" "$(gwRow "$engine" "$t" uttara-7 bras-2)"
    check "$t rows" "5" "$(q "$engine" "select count(*) from $SCHEMA.$t")"
    check "$t sum(sessions) = the cdr rows of group 101" "$(q "$engine" "select count(*) from $SCHEMA.cdr where servicegroup = 101")" "$(q "$engine" "select coalesce(sum(sessions),0) from $SCHEMA.$t")"
    check "$t: the session naming no BRAS went into the OLDER row (its id kept)" "$(olderIdAt "$t" mirpur-10)" \
          "$(q "$engine" "select id from $SCHEMA.$t where tup_site = 'mirpur-10' and tup_gw = ''")"
  done
  check "the 101 VOICE tables count every session (they do not split by BRAS)" "9" "$(q "$engine" "select coalesce(sum(totalcalls),0) from $SCHEMA.sum_voice_day_101")"
  check "sum_voice_day_101 rows (mirpur-10 and uttara-7 as before: one each)" "2" "$(q "$engine" "select count(*) from $SCHEMA.sum_voice_day_101")"
  local after_day after_hr; after_day=$(gwRows "$engine" sum_wifi_day_101); after_hr=$(gwRows "$engine" sum_wifi_hr_101)
  echo "sum_wifi_day_101 now (id|gw|site|sessions|total|cost): $after_day"
  check "ERROR lines of this version after the sessions" "0" "$(errorLines "$log2")"
  stop_service

  say "$engine · 4 · THIS version starts again"
  start_version . "$log3" || return 1
  waitCaughtUp "$engine" 30 || verdict=1
  check "changes said at the second start" "0" "$(changesSaid "$log3" | grep -c . || true)"
  check "sum_wifi_day_101 as it was (every id, BRAS and number)" "$after_day" "$(gwRows "$engine" sum_wifi_day_101)"
  check "sum_wifi_hr_101 as it was" "$after_hr" "$(gwRows "$engine" sum_wifi_hr_101)"
  check "nothing counted twice: sum(sessions) = the cdr rows" "9" "$(q "$engine" "select sum(sessions) from $SCHEMA.sum_wifi_day_101")"
  check "ERROR lines of this version, its second start" "0" "$(errorLines "$log3")"
  stop_service

  [ "$verdict" = 0 ] && echo "$engine HOLDS: the older tables got tup_gw from the service at its first use, their rows read '' as they were, the new rows carry their BRAS, a second start changes nothing" \
                     || echo "$engine DOES NOT HOLD — see above (logs: $log1 $log2 $log3)"
  return "$verdict"
}

result=0
case "$ENGINES" in
  pg)    story pg || result=1 ;;
  mysql) story mysql || result=1 ;;
  both)  story pg || result=1; cleanup; story mysql || result=1 ;;
  *)     echo "usage: SS_OLDER_APP=<dir> $0 [pg | mysql | both]"; exit 2 ;;
esac
echo; [ "$result" = 0 ] && echo "SS-0007 item 2 HOLDS on $ENGINES: the WiFi session sums split by BRAS; the older tables brought up to date by the service, their rows as they were with tup_gw = ''; a second start changes nothing." \
                        || echo "SS-0007 item 2 DOES NOT HOLD — see above."
echo "removing: the service, the schema / database (the containers stay)"
exit "$result"
