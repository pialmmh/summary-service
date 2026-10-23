#!/usr/bin/env bash
# The PINS of SS-0008 (item 3: "pin the sums of every existing scenario on both engines before the change; the same after"):
# ONE corpus (src/test/.../testkit/LabPinCorpus: every record shape the existing scenarios use — voice 10/11 in v2 and v1, ad
# views of 30, WiFi sessions of 101 with and without a BRAS, a group no kind names, one row mixing every kind, a correction,
# a second visit of the same windows, two wifi_flow rows) written into a fresh tier schema as billing-core writes it (one row,
# one transaction), then a PACKAGED app run over it with a profile that enables EVERY bean of the service, then every
# summary table digested — its rows without the id, sorted — with the bookmarks and the dead letters. Run it with the app
# before the change and with the app after: the digests of one engine must be identical.
#
#   SS_PIN_APP=<a project directory, packaged: its target/quarkus-app and tools/lab/run-lab.sh> \
#   SS_PIN_PROFILE=all|call  SS_PIN_OUT=<dir>  tools/lab/kinds-pin-e2e.sh [pg | mysql | both]      (default both)
#
#   SS_PIN_PROFILE  all  (default) — the sixteen beans: the voice pairs of 10 and 11, the call-shaped pairs of 30 and 101,
#                        the ad pair, the WiFi session and destination pairs, the chargeable pair; summary.kinds: [call, ad, wifi]
#                   call — a voice tenant: the voice pairs of 10 and 11 and the chargeable pair; summary.kinds not named (= call)
#   SS_PIN_APP      default: this project directory. SS_PIN_OUT default: target/lab-e2e/pin.
#
# The labs are THIS BOX, as tools/lab/wifi-beans-e2e.sh has them: PostgreSQL = the container SS_LAB_PG on 127.0.0.1:SS_LAB_PG_PORT
# (trust — no password exists); MySQL = 127.0.0.1:SS_LAB_MYSQL_PORT, root, a password — if it needs one — by the NAME of a
# variable, SS_LAB_MYSQL_PASSWORD_VAR=<NAME> (on no command line, in no output). The app is started by ITS OWN
# tools/lab/run-lab.sh (the endpoints shown first; every host this box); no broker is needed — the workers drain on the poll.
# The corpus tool's classpath is this project's target/test-classes + target/lab-e2e/cp.txt (made beforehand by
#   mvn -o dependency:build-classpath -Dmdep.outputFile=target/lab-e2e/cp.txt -Dmdep.includeScope=test
# — this script never starts Maven). What the run makes is removed at the end; the containers stay.
set -euo pipefail
cd "$(dirname "$0")/../.."

ENGINES=${1:-both}
APP=$(cd "${SS_PIN_APP:-.}" && pwd)
PROFILE=${SS_PIN_PROFILE:-all}
OUT=${SS_PIN_OUT:-target/lab-e2e/pin}; mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
PG=${SS_LAB_PG:-ss-pg16-lab}; PG_PORT=${SS_LAB_PG_PORT:-7643}
MY_HOST=127.0.0.1; MY_PORT=${SS_LAB_MYSQL_PORT:-7633}; MY_USER=${SS_LAB_MYSQL_USER:-root}; MY_PASSWORD_VAR=${SS_LAB_MYSQL_PASSWORD_VAR:-}
SCHEMA=lab_kindspin
WORK=$(pwd)/target/lab-e2e; CONF=$WORK/pin-config
[ -f "$APP/target/quarkus-app/quarkus-run.jar" ] && [ -x "$APP/tools/lab/run-lab.sh" ] || { echo "SS_PIN_APP=$APP has no packaged target/quarkus-app or no tools/lab/run-lab.sh"; exit 2; }
[ -s "$WORK/cp.txt" ] && [ -d target/test-classes ] || { echo "no target/test-classes or $WORK/cp.txt (see the header: this script never starts Maven)"; exit 2; }

case "$PROFILE" in
  all)  BEANS=16 ;;
  call) BEANS=6 ;;
  *)    echo "SS_PIN_PROFILE must be all or call"; exit 2 ;;
esac

say() { printf '\n== %s ==\n' "$*"; }
service_pid=""
stop_service() { [ -n "$service_pid" ] && kill "$service_pid" 2>/dev/null || true; wait "$service_pid" 2>/dev/null || true; service_pid=""; }
pg()  { local role=$1; shift; docker exec -i "$PG" psql -U "$role" -d routesphere -q -v ON_ERROR_STOP=1 "$@"; }
pgv() { docker exec -i "$PG" psql -U "$1" -d routesphere -tA -c "$2" 2>/dev/null; }
my()  { if [ -n "$MY_PASSWORD_VAR" ]; then MYSQL_PWD="${!MY_PASSWORD_VAR}" mysql -h "$MY_HOST" -P "$MY_PORT" -u "$MY_USER" -N "$@"; else mysql -h "$MY_HOST" -P "$MY_PORT" -u "$MY_USER" -N "$@"; fi; }
myv() { my -e "$1" 2>/dev/null; }
q()   { if [ "$1" = pg ]; then pgv postgres "$2"; else myv "$2"; fi; }
cleanup() {
  stop_service
  pg postgres -c "DROP SCHEMA IF EXISTS $SCHEMA CASCADE" >/dev/null 2>&1 || true
  myv "DROP DATABASE IF EXISTS $SCHEMA" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

# ---- the profile: every bean of the service (all), or a voice tenant's (call) ------------------------------------------
write_profile() {
  mkdir -p "$CONF/config/tenants/btcl/pin"
  {
    echo "summary:"
    echo "  endpoints: {local-only: true}"
    echo "  store: {kind: postgresql, url: 'jdbc:postgresql://127.0.0.1:1/routesphere', username: summary_service, password: '', min-size: 0, max-size: 8}"
    echo "  tenants: {mode: single}"
    [ "$PROFILE" = all ] && echo "  kinds: [call, ad, wifi]"
    if [ "$PROFILE" = all ]; then
      echo "  enabledSummary: [dailyCallSummary, hourlyCallSummary, dailyCallSummarySg11, hourlyCallSummarySg11, dailyCallSummarySg30, hourlyCallSummarySg30, dailyCallSummarySg101, hourlyCallSummarySg101, dailyAdSummary, hourlyAdSummary, dailyWifiSessionSummary, hourlyWifiSessionSummary, dailyWifiDstSummary, hourlyWifiDstSummary, dailyChargeableSummary, hourlyChargeableSummary]"
    else
      echo "  enabledSummary: [dailyCallSummary, hourlyCallSummary, dailyCallSummarySg11, hourlyCallSummarySg11, dailyChargeableSummary, hourlyChargeableSummary]"
    fi
    echo "  beans:"
    echo "    dailyCallSummary: {service-group: 10, table-suffix: '03'}"
    echo "    hourlyCallSummary: {service-group: 10, table-suffix: '03'}"
    echo "    dailyCallSummarySg11: {window: daily, service-group: 11, table-suffix: '02'}"
    echo "    hourlyCallSummarySg11: {window: hourly, service-group: 11, table-suffix: '02'}"
    echo "    dailyCallSummarySg30: {window: daily, service-group: 30, table-suffix: '30'}"
    echo "    hourlyCallSummarySg30: {window: hourly, service-group: 30, table-suffix: '30'}"
    echo "    dailyCallSummarySg101: {window: daily, service-group: 101, table-suffix: '101'}"
    echo "    hourlyCallSummarySg101: {window: hourly, service-group: 101, table-suffix: '101'}"
    echo "    dailyWifiSessionSummary: {window: daily, kind: wifi-session}"
    echo "    hourlyWifiSessionSummary: {window: hourly, kind: wifi-session}"
    echo "    dailyWifiDstSummary: {window: daily, kind: wifi-dst}"
    echo "    hourlyWifiDstSummary: {window: hourly, kind: wifi-dst}"
    echo "  outbox: {entity-type: cdr, ping-topic: cdr_summary_ping_btcl, ping-bootstrap-servers: '127.0.0.1:7692', poll-interval-seconds: 1, max-rows-per-tx: 1, segment-size: 1000, reaper-interval-seconds: 600, quarantine-after: 8}"
  } > "$CONF/config/tenants/btcl/pin/profile-pin.yml"
}

setup_pg() {
  pg prime_context -c "CREATE SCHEMA $SCHEMA" -c "GRANT USAGE, CREATE ON SCHEMA $SCHEMA TO summary_service, billing_core" >/dev/null 2>&1
  pg billing_core -c "SET search_path TO $SCHEMA" -c "CREATE TABLE summary_affected (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, entity_type varchar(32) NOT NULL,
    op varchar(8) NOT NULL DEFAULT 'add' CHECK (op IN ('add', 'subtract')), data text NOT NULL)" -c "GRANT SELECT, DELETE ON summary_affected TO summary_service" >/dev/null
  OPTIONS=(-Dsummary.config.dir="$CONF" "-Dsummary.store.url=jdbc:postgresql://127.0.0.1:$PG_PORT/routesphere?currentSchema=$SCHEMA")
}
setup_mysql() {
  myv "CREATE DATABASE $SCHEMA" >/dev/null
  OPTIONS=(-Dsummary.config.dir="$CONF" -Dsummary.store.kind=mysql "-Dsummary.store.username=$MY_USER" -Dsummary.ddl.partition-start=2026-09-01 -Dsummary.ddl.partition-days=60
           "-Dsummary.store.url=jdbc:mysql://$MY_HOST:$MY_PORT/$SCHEMA?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true")
  [ -n "$MY_PASSWORD_VAR" ] && OPTIONS+=("-Dsummary.store.password-ref=env:$MY_PASSWORD_VAR")
  true
}
write_row() {   # $1 engine, $2 entity, $3 op, $4 data: ONE outbox row in ONE transaction, as billing-core writes it
  if [ "$1" = pg ]; then pgv billing_core "INSERT INTO $SCHEMA.summary_affected (entity_type, op, data) VALUES ('$2', '$3', '$4')" >/dev/null
  else myv "START TRANSACTION; INSERT INTO $SCHEMA.summary_affected (entity_type, op, data) VALUES ('$2', '$3', '$4'); COMMIT" >/dev/null; fi
}
caughtUp() { q "$1" "select count(*) from $SCHEMA.summary_offset o where o.last_offset >= (select coalesce(max(a.id), 0) from $SCHEMA.summary_affected a where a.entity_type = o.entity_type)"; }
waitCaughtUp() {
  for _ in $(seq 1 600); do [ "$(caughtUp "$1")" = "$BEANS" ] && { echo "$BEANS of $BEANS beans at the last row of their entity"; return 0; }; sleep 0.2; done
  echo "NOT CAUGHT UP: $(caughtUp "$1") of $BEANS"; return 1
}
start_service() {   # $1 log
  "$APP/tools/lab/run-lab.sh" btcl/pin "${OPTIONS[@]}" > "$1" 2>&1 &
  service_pid=$!
  for _ in $(seq 1 600); do grep -q "is served: $BEANS of $BEANS\|could NOT be served\|NOT STARTED\|REFUSING" "$1" && break; sleep 0.5; done
}
# every summary table: its rows without the id, sorted; then the bookmarks; then the dead letters
digest() {   # $1 engine
  local t cols
  if [ "$1" = pg ]; then
    for t in $(q pg "select table_name from information_schema.tables where table_schema = '$SCHEMA' and table_name like 'sum\_%' order by table_name"); do
      cols=$(q pg "select string_agg(column_name, ',' order by column_name) from information_schema.columns where table_schema = '$SCHEMA' and table_name = '$t' and column_name <> 'id'")
      echo "## $t ($(q pg "select count(*) from $SCHEMA.$t") rows)"
      q pg "select concat_ws('|', $cols) from $SCHEMA.$t" | LC_ALL=C sort
    done
  else
    for t in $(q mysql "select table_name from information_schema.tables where table_schema = '$SCHEMA' and table_name like 'sum\\_%' order by table_name"); do
      cols=$(q mysql "select group_concat(column_name order by column_name separator ',') from information_schema.columns where table_schema = '$SCHEMA' and table_name = '$t' and column_name <> 'id'")
      echo "## $t ($(q mysql "select count(*) from $SCHEMA.$t") rows)"
      q mysql "select concat_ws('|', $cols) from $SCHEMA.$t" | LC_ALL=C sort
    done
  fi
  echo "## summary_offset"; q "$1" "select concat_ws('|', entity_type, bean_name, last_offset) from $SCHEMA.summary_offset" | LC_ALL=C sort
  echo "## summary_affected_dlq ($(q "$1" "select count(*) from $SCHEMA.summary_affected_dlq") rows)"
}

story() {
  local engine=$1
  local log="$OUT/pin-$PROFILE-$engine.log" file="$OUT/pin-$PROFILE-$engine.digest"
  say "$engine · the app $APP ($(git -C "$APP" rev-parse --short HEAD 2>/dev/null || echo 'not a git checkout')), profile $PROFILE ($BEANS beans)"
  "setup_$engine"
  start_service "$log"
  grep -q "is served: $BEANS of $BEANS" "$log" || { echo "not served:"; grep -E "ERROR|REFUS|NOT STARTED" "$log" | tail -8 | cut -c1-260; return 1; }
  if [ "$engine" = mysql ]; then for _ in $(seq 1 60); do [ -n "$(myv "show tables in $SCHEMA like 'summary_affected'")" ] && break; sleep 0.5; done; fi
  local n=0 entity op data
  # the corpus on its own descriptor: the database clients below read their standard input
  while read -r entity op data <&3; do write_row "$engine" "$entity" "$op" "$data"; n=$((n + 1)); done \
    3< <(java -cp "target/test-classes:target/classes:$(cat "$WORK/cp.txt")" com.telcobright.summary.testkit.LabPinCorpus)
  echo "$n outbox rows written"
  waitCaughtUp "$engine" || return 1
  digest "$engine" > "$file"
  echo "the digest: $file — $(grep -c '^## sum_' "$file") summary tables, $(grep -vc '^##' "$file") lines, sha256 $(sha256sum "$file" | cut -c1-16)"
  grep '^## ' "$file" | sed 's/^/   /'
  echo "KIND_NOT_ENABLED lines of the service: $(grep -c "KIND_NOT_ENABLED" "$log" || true); ERROR lines: $(grep -c " ERROR " "$log" || true)"
  grep "KIND_NOT_ENABLED" "$log" | sed 's/^.*KIND_NOT_ENABLED/   KIND_NOT_ENABLED/' | cut -c1-200 || true
  stop_service
}

write_profile
result=0
case "$ENGINES" in
  pg)    story pg || result=1 ;;
  mysql) story mysql || result=1 ;;
  both)  story pg || result=1; cleanup; story mysql || result=1 ;;
  *)     echo "usage: $0 [pg | mysql | both]"; exit 2 ;;
esac
echo; [ "$result" = 0 ] && echo "the pin run COMPLETED on $ENGINES (digests in $OUT)" || echo "the pin run DID NOT COMPLETE — see above"
exit "$result"
