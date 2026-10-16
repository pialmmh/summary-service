#!/usr/bin/env bash
# The WAY BACK from the WiFi session sums split by BRAS (SS-0007 item 5) — for devops, on ONE tier schema, AFTER the new jar
# is stopped and BEFORE 3829e18's jar is started on it:
#
#   tools/lab/wifi-gw-wayback.sh postgresql <tier schema>    psql to WAYBACK_HOST:WAYBACK_PORT (127.0.0.1:5432), database
#                                                           WAYBACK_DB (routesphere), role WAYBACK_USER (summary_service)
#   tools/lab/wifi-gw-wayback.sh mysql <tier database>       mysql to WAYBACK_HOST:WAYBACK_PORT (127.0.0.1:3306), user
#                                                           WAYBACK_USER (root)
#
# Why: 3829e18's jar reloads a bucket of sum_wifi_hr_101 / sum_wifi_day_101 without tup_gw, so two rows of one OLD key — the
# '' row and a BRAS row, which the new jar makes from the first session after its install — stop its two WiFi session
# beans ("duplicate existing row while populating cache", the offset STUCK). This collapses each table to the old key: per
# table, in ONE transaction, every group of rows that share it is summed into its lowest id (the average derived again as
# the bean derives it), the other rows deleted, tup_gw = '' on every row. The SQL is tools/lab/wifi-gw-wayback-postgres.sql
# / -mysql.sql. It prints what it merged, deleted and cleared; a second run changes nothing (0, 0, 0). No other table is
# read or written; a tier without the two tables, or with tables 3829e18 made and never upgraded, is left as it is.
#
# The sign it gives: READY — neither table holds an old key twice or a BRAS: 3829e18's jar may start on this schema. NOT
# READY (exit 1) — something wrote a BRAS row after the collapse: is the new jar still running?
#
# A password, when the role needs one, by the NAME of a variable: WAYBACK_PASSWORD_VAR=<NAME> — the value is on no command
# line and in no output (psql reads it as PGPASSWORD, mysql as MYSQL_PWD).
set -euo pipefail
cd "$(dirname "$0")"

ENGINE=${1:-}; SCHEMA=${2:-}
usage() { echo "usage: $0 postgresql|mysql <tier schema>"; exit 2; }
[[ "$SCHEMA" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || usage
HOST=${WAYBACK_HOST:-127.0.0.1}
case "$ENGINE" in
  postgresql) PORT=${WAYBACK_PORT:-5432}; DB=${WAYBACK_DB:-routesphere}; WHO=${WAYBACK_USER:-summary_service}; SQL=wifi-gw-wayback-postgres.sql ;;
  mysql)      PORT=${WAYBACK_PORT:-3306}; DB=$SCHEMA; WHO=${WAYBACK_USER:-root}; SQL=wifi-gw-wayback-mysql.sql ;;
  *)          usage ;;
esac
if [ -n "${WAYBACK_PASSWORD_VAR:-}" ]; then
  [ -n "${!WAYBACK_PASSWORD_VAR:-}" ] || { echo "WAYBACK_PASSWORD_VAR names $WAYBACK_PASSWORD_VAR, which this environment does not hold — nothing done"; exit 2; }
  export PGPASSWORD="${!WAYBACK_PASSWORD_VAR}" MYSQL_PWD="${!WAYBACK_PASSWORD_VAR}"
fi

client() {   # the engine's client on the tier schema; the SQL on stdin; one text column per line out
  if [ "$ENGINE" = postgresql ]; then
    psql -h "$HOST" -p "$PORT" -U "$WHO" -d "$DB" -X -q -tA -v ON_ERROR_STOP=1 -v schema="$SCHEMA" -f -
  else
    mysql -h "$HOST" -P "$PORT" -u "$WHO" -N -B "$SCHEMA"
  fi
}

theTables() {   # "<the two tables found> <their tup_gw columns found>" — read only
  if [ "$ENGINE" = postgresql ]; then
    client <<'SQL'
SELECT (SELECT count(*) FROM information_schema.tables WHERE table_schema = :'schema' AND table_name IN ('sum_wifi_day_101', 'sum_wifi_hr_101'))
       || ' ' || (SELECT count(*) FROM information_schema.columns WHERE table_schema = :'schema'
                  AND table_name IN ('sum_wifi_day_101', 'sum_wifi_hr_101') AND column_name = 'tup_gw');
SQL
  else
    client <<'SQL'
SELECT CONCAT((SELECT count(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('sum_wifi_day_101', 'sum_wifi_hr_101')),
       ' ', (SELECT count(*) FROM information_schema.columns WHERE table_schema = DATABASE()
             AND table_name IN ('sum_wifi_day_101', 'sum_wifi_hr_101') AND column_name = 'tup_gw'));
SQL
  fi
}

echo "== the way back on $ENGINE $HOST:$PORT/$DB, tier schema $SCHEMA, as $WHO =="
read -r tables columns <<< "$(theTables)"
case "$tables $columns" in
  "0 0") echo "no WiFi session tables in $SCHEMA — nothing to do"; exit 0 ;;
  "2 0") echo "the WiFi session tables of $SCHEMA have no tup_gw (never upgraded) — 3829e18's jar can start as it is; nothing to do"; exit 0 ;;
  "2 2") ;;
  *)     echo "REFUSED: $SCHEMA holds $tables of the two WiFi session tables and $columns tup_gw column(s) — not a state this tool knows; nothing done"; exit 3 ;;
esac

said=$(client < "$SQL")
printf '%s\n' "$said" | awk -F'|' '$1 == "wayback" { printf "   %-17s %-13s %s\n", $2, $3, $4 }'
notReady=$(printf '%s\n' "$said" | awk -F'|' '$1 == "ready" && ($3 != 0 || $4 != 0) { printf "%s (old keys twice %s, rows with a BRAS %s) ", $2, $3, $4 }')
checked=$(printf '%s\n' "$said" | grep -c '^ready|' || true)
if [ "$checked" != 2 ] || [ -n "$notReady" ]; then
  echo "NOT READY: ${notReady:-the check did not answer for both tables} — is the new jar still running on $SCHEMA? Stop it, then run this again."
  exit 1
fi
echo "READY: sum_wifi_day_101 and sum_wifi_hr_101 of $SCHEMA hold no old key twice and no BRAS — 3829e18's jar may start on it"
