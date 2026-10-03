#!/usr/bin/env bash
# Start the PACKAGED summary-service on a lab profile — and ONLY when every endpoint it resolved is on this machine.
#
# The rule (the architect, 2026-10-04): BEFORE a service starts in a lab, print the endpoints it resolved — the
# database URL, the Kafka bootstrap, each configuration source's base URL — and do not start it unless every host
# is 127.0.0.1 or localhost. An address of a box is never dialled from a lab, not even for a read.
#
#   tools/lab/run-lab.sh btcl/lab [-Dkey=value ...]          show the endpoints, then start (foreground; Ctrl-C stops)
#   tools/lab/run-lab.sh --show btcl/lab [-Dkey=value ...]   show the endpoints and stop
#
# How: the service itself resolves its endpoints from the profile and the -D options given here, exactly as the real
# start will (summary.endpoints.print-only=true: it prints them and exits; it starts no worker, no listener, and dials
# nothing). THIS script reads the hosts and decides. The real start then also carries
# summary.endpoints.loopback-only=true, so the service refuses by itself if anything changed in between.
set -euo pipefail

cd "$(dirname "$0")/../.."
JAR=target/quarkus-app/quarkus-run.jar
HTTP_PORT=${SUMMARY_LAB_HTTP_PORT:-7671}

show_only=false
if [ "${1:-}" = "--show" ]; then show_only=true; shift; fi
tenant=${1:-}
if ! [[ "$tenant" =~ ^[A-Za-z0-9_-]+/[A-Za-z0-9_-]+$ ]]; then
  echo "usage: $0 [--show] <tenant>/<profile> [-Dkey=value ...]   e.g. $0 btcl/lab"; exit 2
fi
shift
[ -f "$JAR" ] || { echo "no $JAR — run: mvn package -DskipTests"; exit 2; }

common=(-Dsummary.active-tenant="$tenant" -Dquarkus.http.host=127.0.0.1 -Dquarkus.http.port="$HTTP_PORT" "$@")

echo "== the endpoints this start resolved ($tenant) =="
set +e
resolved=$(java "${common[@]}" -Dsummary.endpoints.print-only=true -Dquarkus.log.level=WARN -jar "$JAR" 2>&1)
set -e
lines=$(printf '%s\n' "$resolved" | grep '^ENDPOINT ' || true)
if [ -z "$lines" ]; then
  echo "the service printed no endpoint — NOT STARTED. Its output:"; printf '%s\n' "$resolved" | tail -20; exit 3
fi
printf '%s\n' "$lines"

# this script's own reading of the hosts: every one must be a loopback name
elsewhere=0
while IFS= read -r line; do
  hosts=$(printf '%s\n' "$line" | sed -n 's/.* hosts=\([^ ]*\) .*/\1/p')
  verdict=${line##* }
  [ "$verdict" = "NOT-SET" ] && continue
  if [ -z "$hosts" ] || [ "$hosts" = "-" ]; then elsewhere=$((elsewhere + 1)); echo "   ^ no host could be read: not taken for local"; continue; fi
  IFS=',' read -ra each <<< "$hosts"
  for host in "${each[@]}"; do
    if ! [[ "$host" =~ ^(127\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|localhost|::1)$ ]]; then
      elsewhere=$((elsewhere + 1)); echo "   ^ $host is NOT this machine"
    fi
  done
done <<< "$lines"

if [ "$elsewhere" -ne 0 ]; then
  echo "== NOT STARTED: $elsewhere host(s) are not 127.0.0.1 / localhost. A lab never dials a box, not even for a read. =="
  exit 3
fi
echo "== every host is this machine =="
$show_only && exit 0

echo "== starting $tenant (the service refuses by itself if an endpoint is not on this machine) =="
exec java "${common[@]}" -Dsummary.endpoints.loopback-only=true -Dsummary.autostart=true -jar "$JAR"
