#!/usr/bin/env bash
# Start the PACKAGED summary-service on a lab profile — and ONLY when every endpoint it resolved is on THIS BOX.
#
# The rule (the architect, 2026-10-04): BEFORE a service starts in a lab, print the endpoints it resolved — the
# database URL, the Kafka bootstrap, each configuration source's base URL — and do not start it unless every host
# is this box: localhost, a loopback address, or an address one of this box's own interfaces holds (a real
# prime-context never listens on loopback). A host name is never looked up. An address of another box is never
# dialled from a lab, not even for a read.
#
#   tools/lab/run-lab.sh btcl/lab [-Dkey=value ...]          show the profile and the endpoints, then start (foreground; Ctrl-C stops)
#   tools/lab/run-lab.sh --show btcl/lab [-Dkey=value ...]   show them and stop
#
# A profile from outside the jar: -Dsummary.config.dir=<the directory that holds config/tenants/...> (or SUMMARY_CONFIG_DIR).
# The PROFILE line says which file was read.
#
# How: the service itself resolves its endpoints from the profile and the -D options given here, exactly as the real
# start will (summary.endpoints.print-only=true: it prints them and exits; it starts no worker, no listener, and dials
# nothing). THIS script reads the hosts and decides. The real start then also carries
# summary.endpoints.local-only=true, so the service refuses by itself if anything changed in between.
#
# A secret is never given here: a profile that names its password's variable (summary.store.password-ref: env:NAME)
# takes it from THIS shell's environment. Exit codes: 3 = a host is not this box, 4 = the configuration refuses the
# start (that variable is not set; the engine contradicts the URL; a password rides in the URL; the profile has a fault).
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

echo "== the profile and the endpoints this start resolved ($tenant) =="
set +e
resolved=$(java "${common[@]}" -Dsummary.endpoints.print-only=true -Dquarkus.log.level=WARN -jar "$JAR" 2>&1)
shown=$?
set -e
lines=$(printf '%s\n' "$resolved" | grep '^ENDPOINT ' || true)
if [ -z "$lines" ]; then
  echo "the service printed no endpoint — NOT STARTED. Its output:"; printf '%s\n' "$resolved" | tail -20; exit 3
fi
printf '%s\n' "$resolved" | grep '^PROFILE ' || true     # which tenant, what named it, which profile FILE was read
printf '%s\n' "$lines"

# this script's own reading of the hosts: every one must be this box — a loopback name, or an address that one of
# this box's own interfaces holds (asked of the kernel here, not of the service)
own=" $(ip -o addr show 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | tr '\n' ' ')"
elsewhere=0
while IFS= read -r line; do
  hosts=$(printf '%s\n' "$line" | sed -n 's/.* hosts=\([^ ]*\) .*/\1/p')
  verdict=${line##* }
  [ "$verdict" = "NOT-SET" ] && continue
  if [ -z "$hosts" ] || [ "$hosts" = "-" ]; then elsewhere=$((elsewhere + 1)); echo "   ^ no host could be read: not taken for local"; continue; fi
  IFS=',' read -ra each <<< "$hosts"
  for host in "${each[@]}"; do
    if [[ "$host" =~ ^(127\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|localhost|::1)$ ]]; then continue; fi
    if [[ "$host" =~ ^[0-9a-fA-F.:]+$ ]] && [ "$host" != "0.0.0.0" ] && [ "$host" != "::" ] && [[ "$own" == *" $host "* ]]; then
      echo "   ^ $host is an address of this box's own interface"; continue
    fi
    elsewhere=$((elsewhere + 1)); echo "   ^ $host is NOT this box"
  done
done <<< "$lines"

if [ "$elsewhere" -ne 0 ]; then
  echo "== NOT STARTED: $elsewhere host(s) are not this box (localhost, a loopback address, an address of its own interfaces). A lab never dials another box, not even for a read. =="
  exit 3
fi
echo "== every host is this box =="
# where the store's password comes from — the NAME of its variable, never a value. A variable the profile names
# and this environment does not hold, or another fault of the store's configuration: the service would refuse the
# start; it is not started. The service says why, as its start would (REFUSING TO START: ...).
printf '%s\n' "$resolved" | grep -E '^SECRET |^REFUSING TO START' || true
if [ "$shown" -eq 4 ]; then
  echo "== NOT STARTED: the configuration refuses the start (the line above says why) =="
  exit 4
fi
$show_only && exit 0

echo "== starting $tenant (the service refuses by itself if an endpoint is not on this box) =="
exec java "${common[@]}" -Dsummary.endpoints.local-only=true -Dsummary.autostart=true -jar "$JAR"
