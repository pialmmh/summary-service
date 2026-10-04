#!/usr/bin/env bash
# Brief S11 with the PACKAGED jar: the lab key (summary.endpoints.local-only) means "this box", not "loopback".
#
#   tools/lab/this-box-e2e.sh
#
# A real prime-context never listens on loopback (its bind guard admits 10.10.x.x only), so a lab gives it an
# address of this box — a bridge, or a network namespace's own address. The story:
#   1. prime-context on an address NO box holds (192.0.2.1, TEST-NET-1): the endpoint is NOT-THIS-BOX, run-lab.sh
#      does not start, and the service started directly with the key refuses, in words, before anything is dialled.
#   2. inside a private network namespace whose loopback ALSO holds 10.10.250.1 (the shape of the rehearsal's
#      chain): a stand-in of prime-context listens on 10.10.250.1; the endpoint is THIS-BOX; the service starts
#      with the key and reads the tree from that address. (The store is not in the namespace: it says so and tries
#      again — that part is tree-e2e.sh's.)
#   3. the SAME address outside the namespace, where no interface holds it: NOT-THIS-BOX.
#
# Nothing leaves this box: step 1 dials nothing (print-only, then a refused start), step 2 runs where the only
# interface is the namespace's own loopback, with no route. Needs: unshare (user + network namespace), ip.
set -euo pipefail
cd "$(dirname "$0")/../.."
JAR=target/quarkus-app/quarkus-run.jar
[ -f "$JAR" ] || { echo "no $JAR — run: mvn package -DskipTests"; exit 2; }
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
say() { echo; echo "== $* =="; }
ADDRESS=10.10.250.1
OPTIONS=(-Dsummary.active-tenant=btcl/lab -Dquarkus.http.host=127.0.0.1 -Dquarkus.http.port=7671)
if ip -o addr show | grep -q " $ADDRESS/"; then echo "this box already holds $ADDRESS: step 3 would not show a refusal — pick another address"; exit 2; fi

say "1 · prime-context on an address no box holds (192.0.2.1)"
set +e
tools/lab/run-lab.sh --show btcl/lab -Dsummary.tenants.prime-context.base-url=http://192.0.2.1:7091 > "$WORK/1-show.log" 2>&1; shown=$?
java "${OPTIONS[@]}" -Dsummary.tenants.prime-context.base-url=http://192.0.2.1:7091 -Dsummary.autostart=true -jar "$JAR" > "$WORK/1-start.log" 2>&1; started=$?
set -e
grep -E "^ENDPOINT tree-prime-context|is NOT this box|^== NOT STARTED" "$WORK/1-show.log" | cut -c1-200
echo "run-lab.sh --show exit code: $shown (3 = a host is not this box)"
echo "the service's own start (the lab profile carries the key): exit code $started; it said:"
grep -m1 -o "REFUSING TO START: summary.endpoints.local-only is set (a lab start) and [0-9]* endpoint(s) are not on this box — [^;]*;" "$WORK/1-start.log"
[ "$shown" = "3" ] && [ "$started" != "0" ] || { echo "FAILED: a start that would dial another box was not refused"; exit 1; }
if grep -qE "store: postgresql|worker started|is served|the tree of" "$WORK/1-start.log"; then echo "FAILED: something was dialled or started"; exit 1; fi
echo "nothing was dialled: no tree was asked for, no pool was made, no worker started"

say "2 · inside a namespace whose loopback also holds $ADDRESS: prime-context listens there"
printf '{"dbName":"btcl","parent":null,"context":{},"children":{"res_44":{"dbName":"res_44","parent":"btcl","children":{},"context":{}}}}' > "$WORK/tree.json"
if ! unshare -r -n true 2>/dev/null; then echo "SKIPPED: this box does not allow an unprivileged user + network namespace (unshare -r -n)"; exit 0; fi
unshare -r -n bash -s "$WORK" "$ADDRESS" "$JAR" <<'INSIDE' > "$WORK/2.log" 2>&1 || true
WORK=$1; ADDRESS=$2; JAR=$3
ip link set lo up && ip addr add "$ADDRESS/32" dev lo
echo "interfaces: $(ip -o -4 addr show | awk '{print $2 "=" $4}' | tr '\n' ' ')"
python3 tools/lab/prime-context-standin.py 7691 "$WORK/tree.json" "$ADDRESS" > /dev/null 2> "$WORK/standin.log" &
standin=$!
OPTIONS=(-Dsummary.active-tenant=btcl/lab -Dquarkus.http.host=127.0.0.1 -Dquarkus.http.port=7671 "-Dsummary.tenants.prime-context.base-url=http://$ADDRESS:7691")
java "${OPTIONS[@]}" -Dsummary.endpoints.print-only=true -Dquarkus.log.level=WARN -jar "$JAR" 2>&1 | grep -E "^ENDPOINT tree-prime-context|^ENDPOINTS"
echo "print-only exit code: ${PIPESTATUS[0]}"
java "${OPTIONS[@]}" -Dsummary.endpoints.local-only=true -Dsummary.autostart=true -jar "$JAR" > "$WORK/2-start.log" 2>&1 &
service=$!
for _ in $(seq 1 80); do grep -q "could NOT be served\|REFUSING" "$WORK/2-start.log" && break; sleep 0.5; done
kill "$service" "$standin" 2>/dev/null; wait 2>/dev/null
INSIDE
cat "$WORK/2.log" | cut -c1-200
grep -m1 -o "ENDPOINT tree-prime-context .*" "$WORK/2-start.log" | cut -c1-120
grep -o "tenants: schema [a-z0-9_]* could NOT be served" "$WORK/2-start.log" | sort -u | sed 's/^/the tree it read names: /' || true
grep -c "get-specific-tenant-root" "$WORK/standin.log" | sed 's/^/the stand-in on '"$ADDRESS"' was asked for the tree, times: /'
grep -q "ENDPOINT tree-prime-context http://$ADDRESS:7691 hosts=$ADDRESS THIS-BOX" "$WORK/2.log" || { echo "FAILED: an address of this box's own interface was not taken for this box"; exit 1; }
grep -q "print-only exit code: 0" "$WORK/2.log" || { echo "FAILED: print-only did not exit 0"; exit 1; }
if grep -q "REFUSING" "$WORK/2-start.log"; then echo "FAILED: the start was refused:"; grep -m1 "REFUSING" "$WORK/2-start.log" | cut -c1-300; exit 1; fi
[ "$(grep -c "get-specific-tenant-root" "$WORK/standin.log")" -ge 1 ] || { echo "FAILED: the tree was never asked of $ADDRESS"; tail -5 "$WORK/2-start.log"; exit 1; }
echo "the start was not refused, and the tree was read from $ADDRESS (the store is not in the namespace: $(grep -c 'could NOT be served' "$WORK/2-start.log") schema(s) said so and are tried again)"

say "3 · the same address outside the namespace: no interface of this box holds it"
set +e
java "${OPTIONS[@]}" "-Dsummary.tenants.prime-context.base-url=http://$ADDRESS:7691" -Dsummary.endpoints.print-only=true -Dquarkus.log.level=WARN -jar "$JAR" > "$WORK/3.log" 2>&1; outside=$?
set -e
grep -E "^ENDPOINT tree-prime-context|^ENDPOINTS" "$WORK/3.log" | cut -c1-200
echo "print-only exit code: $outside (3 = a host is not this box)"
[ "$outside" = "3" ] || { echo "FAILED: an address no interface of this box holds was taken for this box"; exit 1; }

echo; echo "S11 HOLDS: an address held by one of this box's own interfaces is this box; an address of another box refuses the start, in words, before anything is dialled."
