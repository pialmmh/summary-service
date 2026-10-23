#!/usr/bin/env bash
# The WAY BACK after sessions with a BRAS exist (SS-0007 item 5), with two PACKAGED jars, on BOTH engines:
#
#   1. the OLDER version (SS_OLDER_APP: a packaged clean copy of 3829e18 — its target/quarkus-app and tools/lab/run-lab.sh)
#      sums four sessions: mirpur-10 and uttara-7 get their '' rows
#   2. THIS version upgrades the tables (two ALTERs) and sums eight sessions on two BRAS in the SAME hour and day: mirpur-10
#      on bras-1, bras-2 and one naming none (into the '' row); uttara-7 on bras-2; banani-11 on bras-2 then bras-1 (a group
#      whose lowest id names a BRAS); gulshan-2 on bras-1 alone (one row, a BRAS). It is stopped; the totals per OLD key,
#      summed across tup_gw, are read, and a digest of every OTHER table of the schema
#   3. the way back — tools/lab/wifi-gw-wayback.sh — collapses the two tables: per table it says merged 3, deleted 4, bras
#      cleared 2, READY; run again: 0, 0, 0, READY. One row per old key, its totals = the totals read in 2, every average
#      derived as the bean derives it, no BRAS, every other table as it was
#   4. the OLDER version starts again: its two WiFi session beans RUN — no "duplicate existing row", their offsets move to
#      the outbox head — and two more sessions merge into the collapsed rows (mirpur-10, banani-11)
#   5. forward again: THIS version starts on the collapsed tables cleanly — no change said, no ERROR — and a session on
#      bras-2 opens its own row beside the collapsed '' row
#
#   SS_OLDER_APP=<dir> tools/lab/wifi-gw-wayback-e2e.sh [pg | mysql | both]     (default both)
#
# The labs are THIS BOX, as tools/lab/wifi-gw-upgrade-e2e.sh has them (SS_LAB_PG / SS_LAB_PG_PORT, SS_LAB_MYSQL_PORT,
# SS_LAB_MYSQL_PASSWORD_VAR = the NAME of the variable that holds MySQL's password). The way back runs as devops runs it:
# the host's psql / mysql on 127.0.0.1 (the PostgreSQL container publishes its port there), as summary_service on
# PostgreSQL. Each service is started by its own tools/lab/run-lab.sh (the endpoints shown first; every host this box).
# What the story makes is removed at the end; the containers stay.
set -euo pipefail
cd "$(dirname "$0")/../.."

ENGINES=${1:-both}
OLDER=${SS_OLDER_APP:?"SS_OLDER_APP must name the older version's project directory, packaged (its target/quarkus-app and tools/lab/run-lab.sh)"}
PG=${SS_LAB_PG:-ss-pg16-lab}; PG_PORT=${SS_LAB_PG_PORT:-7643}
MY_HOST=127.0.0.1; MY_PORT=${SS_LAB_MYSQL_PORT:-7633}; MY_USER=${SS_LAB_MYSQL_USER:-root}; MY_PASSWORD_VAR=${SS_LAB_MYSQL_PASSWORD_VAR:-}
SCHEMA=lab_wifiwayback
BEANS=12
TABLES="sum_wifi_day_101 sum_wifi_hr_101"
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

# ---- the sessions (2026-10-16 09:xx, the tenant's wall clock; one hour, one day), as BC-0008 writes them -----------------
# start | site | zone | tier | category | package | seconds | bytesUp | bytesDn | unit | amount | cause | validFlag | gw ('-' = none)
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
  "2026-10-16T09:32 uttara-7 dhaka-north free 1 free-15 300 300 3000 TF_min 0 NORMAL_CLEARING 1 bras-2"
  "2026-10-16T09:35 banani-11 dhaka-north paid 2 wifi-30 1200 400 4000 TF_min 20 NORMAL_CLEARING 1 bras-2"
  "2026-10-16T09:38 banani-11 dhaka-north paid 2 wifi-30 600 100 1000 TF_min 10 NORMAL_CLEARING 1 bras-1"
  "2026-10-16T09:41 gulshan-2 dhaka-north paid 2 wifi-30 300 50 500 TF_min 5 NORMAL_CLEARING 1 bras-1"
)
ROLLED_BACK=(   # summed by the OLDER version after the way back (the records name a BRAS: it does not read them)
  "2026-10-16T09:44 mirpur-10 dhaka-north paid 2 wifi-30 1800 1000000 10000000 TF_min 30 NORMAL_CLEARING 1 bras-1"
  "2026-10-16T09:47 banani-11 dhaka-north paid 2 wifi-30 600 100 1000 TF_min 10 NORMAL_CLEARING 1 bras-2"
)
FORWARD=(
  "2026-10-16T09:50 mirpur-10 dhaka-north paid 2 wifi-30 1800 1000000 10000000 TF_min 30 NORMAL_CLEARING 1 bras-2"
)
OLD_KEY="tup_switchid, tup_inpartnerid, tup_site, tup_zone, tup_granttier, tup_category, tup_packagecode, tup_customercurrency, tup_causefamily, tup_starttime"

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
  WAYBACK=(env WAYBACK_HOST=127.0.0.1 "WAYBACK_PORT=$PG_PORT" WAYBACK_DB=routesphere WAYBACK_USER=summary_service tools/lab/wifi-gw-wayback.sh postgresql "$SCHEMA")
}
setup_mysql() {
  myv "CREATE DATABASE $SCHEMA" >/dev/null
  myv "CREATE TABLE $SCHEMA.cdr (id bigint AUTO_INCREMENT PRIMARY KEY, servicegroup int NOT NULL, starttime datetime, durationsec decimal(18,6),
    intrunkadditionalinfo varchar(64), inmgwid varchar(64))" >/dev/null
  OPTIONS=(-Dsummary.tenants.mode=single -Dsummary.store.kind=mysql "-Dsummary.store.username=$MY_USER"
           -Dsummary.ddl.partition-start=2026-09-01 -Dsummary.ddl.partition-days=60
           "-Dsummary.store.url=jdbc:mysql://$MY_HOST:$MY_PORT/$SCHEMA?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true")
  [ -n "$MY_PASSWORD_VAR" ] && OPTIONS+=("-Dsummary.store.password-ref=env:$MY_PASSWORD_VAR")
  WAYBACK=(env WAYBACK_HOST=$MY_HOST "WAYBACK_PORT=$MY_PORT" "WAYBACK_USER=$MY_USER" "WAYBACK_PASSWORD_VAR=$MY_PASSWORD_VAR" tools/lab/wifi-gw-wayback.sh mysql "$SCHEMA")
  true
}
sql() { if [ "$1" = pg ]; then pgv billing_core "$2" >/dev/null; else myv "START TRANSACTION; $2; COMMIT" >/dev/null; fi; }   # one transaction, as billing-core writes
q() { if [ "$1" = pg ]; then pgv postgres "$2"; else myv "$2"; fi; }                                                         # a read as the lab's superuser
agg() { if [ "$1" = pg ]; then echo "string_agg($2, ';' order by $3)"; else echo "group_concat($2 order by $3 separator ';')"; fi; }
derived() {   # the average as the bean derives it, of the columns $2 (total) and $3 (connected)
  if [ "$1" = pg ]; then echo "(CASE WHEN $3 = 0 THEN 0 ELSE round($2::numeric(38,18) / $3, 6) END)"
  else echo "(CASE WHEN $3 = 0 THEN 0 ELSE ROUND(CAST($2 AS DECIMAL(38,18)) / $3, 6) END)"; fi
}
write_session() {   # $1 engine, the 14 facts: ONE cdr row + ONE outbox row (BC-0008's blob) in ONE transaction
  local e=$1 start=$2 site=$3 sec=$8 gw=${15}
  local blob; blob=$(row wifi-bc0008 "${@:2:13}" "$gw")
  local raw="'$gw'"; [ "$gw" = "-" ] && raw=NULL
  sql "$e" "INSERT INTO $SCHEMA.cdr (servicegroup, starttime, durationsec, intrunkadditionalinfo, inmgwid) VALUES (101, '${start/T/ }:00', $sec, '$site', $raw);
     INSERT INTO $SCHEMA.summary_affected (entity_type, op, data) VALUES ('cdr', 'add', '$blob')"
}
write_all() { local e=$1; shift; local s; for s in "$@"; do read -ra F <<< "$s"; write_session "$e" "${F[@]}"; done; }
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
sessionOffsets() { q "$1" "select $(agg "$1" "concat_ws('=', bean_name, last_offset)" bean_name) from $SCHEMA.summary_offset where bean_name in ('dailyWifiSessionSummary', 'hourlyWifiSessionSummary')"; }
# the totals per OLD key, summed across tup_gw: site|unit|family|bucket|sessions|connected|total|up|dn|cost
perOldKey() { q "$1" "select $(agg "$1" "concat_ws('|', tup_site, tup_customercurrency, tup_causefamily, tup_starttime, s, c, t, u, d, m)" "tup_site") from (select $OLD_KEY, sum(sessions) s, sum(sessions_connected) c, sum(total_duration_sec) t, sum(bytes_up) u, sum(bytes_dn) d, sum(customercost) m from $SCHEMA.$2 group by $OLD_KEY) k"; }
oldKeysTwice() { q "$1" "select count(*) from (select 1 from $SCHEMA.$2 group by $OLD_KEY having count(*) > 1) k"; }
siteRow() {   # $1 engine, $2 table, $3 site: "sessions|connected|total|avg|up|dn|cost" of its one '' row
  q "$1" "select coalesce((select concat_ws('|', sessions, sessions_connected, total_duration_sec, avg_session_sec, bytes_up, bytes_dn, customercost) from $SCHEMA.$2 where tup_site = '$3' and tup_gw = ''), 'none')"
}
othersDigest() {   # every OTHER table of the schema, digested: what the way back must never touch
  local e=$1 tables t
  tables=$(q "$e" "select $(agg "$e" table_name table_name) from information_schema.tables where table_schema = '$SCHEMA' and table_name not in ('sum_wifi_day_101', 'sum_wifi_hr_101')")
  if [ "$e" = pg ]; then
    for t in ${tables//;/ }; do printf '%s=%s ' "$t" "$(q pg "select md5(coalesce(string_agg(x::text, '|' order by x::text), '')) from $SCHEMA.$t x")"; done
  else
    myv "CHECKSUM TABLE $(printf '%s\n' ${tables//;/ } | sed "s/^/$SCHEMA./" | paste -sd, -)" | tr '\t\n' '= '
  fi
}
check() { if [ "$2" = "$3" ]; then echo "ok    $1 = $3"; else echo "FAIL  $1: expected $2, read $3"; verdict=1; fi; }
start_version() {   # $1 the version's project directory, $2 the log: started by ITS OWN run-lab.sh; waits for the start's verdict
  "$1/tools/lab/run-lab.sh" btcl/lab "${OPTIONS[@]}" > "$2" 2>&1 &
  service_pid=$!
  for _ in $(seq 1 600); do grep -q "is served: $BEANS of $BEANS\|could NOT be served\|NOT STARTED\|REFUSING" "$2" && break; sleep 0.5; done
  grep -q "is served: $BEANS of $BEANS" "$2" || { echo "not served:"; grep -E "ERROR|REFUS|NOT STARTED" "$2" | tail -8 | cut -c1-260; return 1; }
}
changesSaid() { grep -c "was made by an earlier version" "$1" || true; }
wayback_counts() {   # $1 table, $2 the way back's output: "merged|deleted|bras cleared" as it printed them
  awk -v t="$1" '$1 == t { step = (NF == 4 ? $2 " " $3 : $2); v[step] = $NF } END { print v["merged"] "|" v["deleted"] "|" v["bras cleared"] }' "$2"
}
errorLines() { grep -c " ERROR " "$1" || true; }
stuckLines() { grep -c "duplicate existing row" "$1" || true; }

story() {
  local engine=$1 verdict=0 t said before_day before_hr others offsets
  local log1="$WORK/wifi-wayback-older-$engine.log" log2="$WORK/wifi-wayback-this-$engine.log" log3="$WORK/wifi-wayback-older-again-$engine.log" log4="$WORK/wifi-wayback-forward-$engine.log"
  local wb1="$WORK/wifi-wayback-script-1-$engine.log" wb2="$WORK/wifi-wayback-script-2-$engine.log"

  say "$engine · 1 · the OLDER version ($(cd "$OLDER" && pwd)) sums ${#BEFORE[@]} sessions"
  "setup_$engine"
  start_version "$OLDER" "$log1" || return 1
  if [ "$engine" = mysql ]; then for _ in $(seq 1 60); do [ -n "$(myv "show tables in $SCHEMA like 'summary_affected'")" ] && break; sleep 0.5; done; fi
  write_all "$engine" "${BEFORE[@]}"
  waitCaughtUp "$engine" 90 || verdict=1
  stop_service

  say "$engine · 2 · THIS version: the upgrade, then ${#AFTER[@]} sessions on two BRAS in the same hour and day"
  start_version . "$log2" || return 1
  check "the upgrade's changes said (one ADD COLUMN per table)" "2" "$(changesSaid "$log2")"
  write_all "$engine" "${AFTER[@]}"
  waitCaughtUp "$engine" 90 || verdict=1
  stop_service
  for t in $TABLES; do
    check "$t rows before the way back (mirpur-10 '' bras-1 bras-2, uttara-7 '' bras-2, banani-11 bras-2 bras-1, gulshan-2 bras-1)" "8" "$(q "$engine" "select count(*) from $SCHEMA.$t")"
    check "$t old keys held by more than one row" "3" "$(oldKeysTwice "$engine" "$t")"
  done
  before_day=$(perOldKey "$engine" sum_wifi_day_101); before_hr=$(perOldKey "$engine" sum_wifi_hr_101)
  echo "sum_wifi_day_101 per old key across tup_gw (site|unit|family|bucket|sessions|connected|total|up|dn|cost): $before_day"
  others=$(othersDigest "$engine")
  echo "every other table, digested: $(printf '%s' "$others" | wc -w) tables"
  check "ERROR lines of this version" "0" "$(errorLines "$log2")"

  say "$engine · 3 · the way back: tools/lab/wifi-gw-wayback.sh, then again"
  "${WAYBACK[@]}" > "$wb1" 2>&1 && said=0 || said=$?
  sed 's/^/   /' "$wb1"
  check "the way back's exit" "0" "$said"
  for t in $TABLES; do
    check "$t merged | deleted | bras cleared" "3|4|2" "$(wayback_counts "$t" "$wb1")"
  done
  check "the way back's sign" "1" "$(grep -c '^READY:' "$wb1" || true)"
  "${WAYBACK[@]}" > "$wb2" 2>&1 && said=0 || said=$?
  check "the second run: exit, and every count 0" "0 0" "$said $(awk '$1 ~ /^sum_wifi/ { n += $NF } END { print n + 0 }' "$wb2")"
  check "the second run's sign" "1" "$(grep -c '^READY:' "$wb2" || true)"
  for t in $TABLES; do
    check "$t rows after the way back (one per old key)" "4" "$(q "$engine" "select count(*) from $SCHEMA.$t")"
    check "$t old keys held by more than one row" "0" "$(oldKeysTwice "$engine" "$t")"
    check "$t rows with a BRAS" "0" "$(q "$engine" "select count(*) from $SCHEMA.$t where tup_gw <> ''")"
    check "$t rows whose average is not the bean's (total / connected, six places, half up)" "0" \
          "$(q "$engine" "select count(*) from $SCHEMA.$t where avg_session_sec <> $(derived "$engine" total_duration_sec sessions_connected)")"
  done
  check "sum_wifi_day_101 per old key = the totals across tup_gw before the way back" "$before_day" "$(perOldKey "$engine" sum_wifi_day_101)"
  check "sum_wifi_hr_101 per old key = the totals across tup_gw before the way back" "$before_hr" "$(perOldKey "$engine" sum_wifi_hr_101)"
  check "sum_wifi_day_101 mirpur-10 (3 + 2 + 1 + 1 sessions, into the older row)" "7|7|9300|1328.571429|4001200|40012000|155.000000" "$(siteRow "$engine" sum_wifi_day_101 mirpur-10)"
  check "sum_wifi_day_101 banani-11 (its lowest row named bras-2: kept, cleared)" "2|2|1800|900.000000|500|5000|30.000000" "$(siteRow "$engine" sum_wifi_day_101 banani-11)"
  check "every other table of the schema as it was" "$others" "$(othersDigest "$engine")"

  say "$engine · 4 · the OLDER version again, on the collapsed tables; ${#ROLLED_BACK[@]} more sessions"
  offsets=$(sessionOffsets "$engine")
  start_version "$OLDER" "$log3" || return 1
  write_all "$engine" "${ROLLED_BACK[@]}"
  waitCaughtUp "$engine" 60 || verdict=1
  check "the older version's 'duplicate existing row' lines" "0" "$(stuckLines "$log3")"
  check "the older version's ERROR lines" "0" "$(errorLines "$log3")"
  check "the two WiFi session beans' offsets moved to the outbox head" "dailyWifiSessionSummary=$(written "$engine");hourlyWifiSessionSummary=$(written "$engine")" "$(sessionOffsets "$engine")"
  echo "   (they were $offsets)"
  stop_service
  for t in $TABLES; do
    check "$t rows (the sessions merged into the collapsed rows)" "4" "$(q "$engine" "select count(*) from $SCHEMA.$t")"
    check "$t mirpur-10 (+1 session)" "8|8|11100|1387.500000|5001200|50012000|185.000000" "$(siteRow "$engine" "$t" mirpur-10)"
    check "$t banani-11 (+1 session, into the row the way back cleared)" "3|3|2400|800.000000|600|6000|40.000000" "$(siteRow "$engine" "$t" banani-11)"
    check "$t sum(sessions) = the cdr rows of group 101" "$(q "$engine" "select count(*) from $SCHEMA.cdr where servicegroup = 101")" "$(q "$engine" "select sum(sessions) from $SCHEMA.$t")"
    check "$t rows whose average is not the bean's" "0" "$(q "$engine" "select count(*) from $SCHEMA.$t where avg_session_sec <> $(derived "$engine" total_duration_sec sessions_connected)")"
  done

  say "$engine · 5 · forward again: THIS version on the collapsed tables; one session on bras-2"
  start_version . "$log4" || return 1
  check "changes said (the column is there)" "0" "$(changesSaid "$log4")"
  write_all "$engine" "${FORWARD[@]}"
  waitCaughtUp "$engine" 60 || verdict=1
  check "ERROR lines of this version" "0" "$(errorLines "$log4")"
  stop_service
  for t in $TABLES; do
    check "$t rows (bras-2 opens its own row beside the collapsed '' row)" "5" "$(q "$engine" "select count(*) from $SCHEMA.$t")"
    check "$t mirpur-10 '' as the older version left it" "8|8|11100|1387.500000|5001200|50012000|185.000000" "$(siteRow "$engine" "$t" mirpur-10)"
    check "$t mirpur-10 bras-2" "1|1|1800|1800.000000|1000000|10000000|30.000000" "$(q "$engine" "select concat_ws('|', sessions, sessions_connected, total_duration_sec, avg_session_sec, bytes_up, bytes_dn, customercost) from $SCHEMA.$t where tup_site = 'mirpur-10' and tup_gw = 'bras-2'")"
    check "$t sum(sessions) = the cdr rows of group 101" "$(q "$engine" "select count(*) from $SCHEMA.cdr where servicegroup = 101")" "$(q "$engine" "select sum(sessions) from $SCHEMA.$t")"
  done

  [ "$verdict" = 0 ] && echo "$engine HOLDS: the way back collapsed the tables to the old key (the totals kept, the average the bean's, no BRAS, nothing else touched), 3829e18's jar ran on them, and forward again was clean" \
                     || echo "$engine DOES NOT HOLD — see above (logs: $log1 $log2 $wb1 $wb2 $log3 $log4)"
  return "$verdict"
}

result=0
case "$ENGINES" in
  pg)    story pg || result=1 ;;
  mysql) story mysql || result=1 ;;
  both)  story pg || result=1; cleanup; story mysql || result=1 ;;
  *)     echo "usage: SS_OLDER_APP=<dir> $0 [pg | mysql | both]"; exit 2 ;;
esac
echo; [ "$result" = 0 ] && echo "SS-0007 item 5 HOLDS on $ENGINES: the way back after sessions with a BRAS exist — 3829e18's jar runs on the collapsed tables, and forward again is clean." \
                        || echo "SS-0007 item 5 DOES NOT HOLD — see above."
echo "removing: the service, the schema / database (the containers stay)"
exit "$result"
