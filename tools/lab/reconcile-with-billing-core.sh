#!/usr/bin/env bash
# The summaries against billing-core's OWN rows — the truest check of S1 ("the sample message, mediated by
# billing-core, gives the sum_ad_* rows") and of S6 (both tiers), on the lab.
#
# BEFORE: billing-core's own code must have made and filled the two tier schemas in THIS lab's database. Its lab
# command does it (it drops and re-makes btcl and res_44 each time) — run it from a CLONE of its repository, never in
# its checkout, and only against this lab:
#
#   git clone --local ~/telcobright-projects/telcobright-billing-core /tmp/bc-clone && cd /tmp/bc-clone
#   git checkout --detach origin/postgres-ad-call
#   mvn -f java/pom.xml test -Dtest=LabSchemaForTheReportRoads -Dsurefire.failIfNoSpecifiedTests=false \
#       -Dbc.lab.pg.url=jdbc:postgresql://127.0.0.1:7643/routesphere
#
# THEN this script: it starts the stand-in for prime-context's read road (the tree btcl > res_44) and the PACKAGED
# summary-service through tools/lab/run-lab.sh (the endpoints first; every host must be this box), waits until
# every bean has passed billing-core's outbox rows, and prints — per tier — what the summaries hold beside what
# billing-core's own cdr and acc_chargeable rows add up to. Every line must say SAME. Then it stops what it started
# (the two schemas stay: they are billing-core's lab output; "tools/lab/tree-e2e.sh down" removes them).
set -euo pipefail
cd "$(dirname "$0")/../.."

PG=ss-pg16-lab
PC_PORT=7691
WORK=target/lab-e2e
mkdir -p "$WORK"
ask() { docker exec -i "$PG" psql -U "$1" -d routesphere -tA -c "$2"; }

stop() {
  [ -f "$WORK/service.pid" ] && kill "$(cat "$WORK/service.pid")" 2>/dev/null || true
  [ -f "$WORK/standin.pid" ] && kill "$(cat "$WORK/standin.pid")" 2>/dev/null || true
  rm -f "$WORK/service.pid" "$WORK/standin.pid"
}
trap stop EXIT
stop

[ "$(ask billing_core "select count(*) from pg_tables where schemaname in ('btcl','res_44') and tablename = 'summary_affected'")" = "2" ] \
  || { echo "btcl and res_44 with billing-core's tables are not in the lab — run its lab command first (see the top of this file)"; exit 2; }
[ -f target/quarkus-app/quarkus-run.jar ] || { echo "no packaged application — run: mvn package -DskipTests"; exit 2; }
echo "billing-core left: btcl cdr=$(ask billing_core 'select count(*) from btcl.cdr') cdrerror=$(ask billing_core 'select count(*) from btcl.cdrerror') outbox=$(ask billing_core 'select count(*) from btcl.summary_affected'); res_44 cdr=$(ask billing_core 'select count(*) from res_44.cdr') outbox=$(ask billing_core 'select count(*) from res_44.summary_affected')"
echo "owner of btcl.summary_affected: $(ask billing_core "select tableowner from pg_tables where schemaname='btcl' and tablename='summary_affected'")"

printf '{"dbName":"btcl","parent":null,"context":{},"children":{"res_44":{"dbName":"res_44","parent":"btcl","children":{},"context":{}}}}' > "$WORK/tree.json"
python3 tools/lab/prime-context-standin.py "$PC_PORT" "$WORK/tree.json" > /dev/null 2> "$WORK/standin.log" &
echo $! > "$WORK/standin.pid"
tools/lab/run-lab.sh btcl/lab > "$WORK/service.log" 2>&1 &
echo $! > "$WORK/service.pid"
for _ in $(seq 1 120); do grep -q "started in\|NOT STARTED\|REFUSING" "$WORK/service.log" && break; sleep 0.5; done
sed -n '/^== the endpoints/,/^== starting\|^== NOT STARTED/p' "$WORK/service.log"
grep -q "started in" "$WORK/service.log" || { echo "the service did not start:"; tail -20 "$WORK/service.log"; exit 1; }

# every bean of both schemas must pass the last outbox row billing-core wrote
for tier in btcl res_44; do
  last=$(ask billing_core "select coalesce(max(id),0) from $tier.summary_affected")
  for _ in $(seq 1 300); do
    [ "$(ask summary_service "select count(*) from $tier.summary_offset where last_offset >= $last" 2>/dev/null || echo 0)" = "6" ] && break; sleep 0.2
  done
  echo "$tier: bookmarks $(ask summary_service "select string_agg(bean_name || '=' || last_offset, ' ' order by bean_name) from $tier.summary_offset")"
done

same() { [ "$2" = "$3" ] && echo "SAME   $1: $2" || { echo "DIFFER $1: the summary says $2, billing-core's rows say $3"; differ=1; }; }
differ=0
for tier in btcl res_44; do
  echo; echo "== $tier: the summaries beside billing-core's own rows =="
  same "sum_ad_day_30   views / shown / failed / watched seconds" \
    "$(ask ad_sphere "select concat_ws(' / ', sum(views), sum(shown), sum(failed), sum(watchedsec)) from $tier.sum_ad_day_30")" \
    "$(ask billing_core "select concat_ws(' / ', count(*), count(AnswerTime), count(*) filter (where HangupCause is distinct from 'NORMAL_CLEARING'), sum(round(DurationSec))::bigint) from $tier.cdr where ServiceGroup = 30")"
  same "sum_ad_hr_30    views (the hours add up to the days)" \
    "$(ask ad_sphere "select sum(views) from $tier.sum_ad_hr_30")" "$(ask billing_core "select count(*) from $tier.cdr where ServiceGroup = 30")"
  same "sum_ad_day_30   money (chargedamount) / units (chargedunits)" \
    "$(ask ad_sphere "select concat_ws(' / ', sum(chargedamount)::numeric(18,6), sum(chargedunits)::numeric(18,6)) from $tier.sum_ad_day_30")" \
    "$(ask billing_core "select concat_ws(' / ', coalesce(sum(BilledAmount) filter (where upper(idBilledUom) = 'BDT'), 0)::numeric(18,6), coalesce(sum(BilledAmount) filter (where upper(idBilledUom) is distinct from 'BDT'), 0)::numeric(18,6)) from $tier.acc_chargeable where servicegroup = 30 and assignedDirection = 1")"
  same "sum_ad_day_30   completed / credited (the meta data's)" \
    "$(ask ad_sphere "select concat_ws(' / ', sum(completed), sum(credited)) from $tier.sum_ad_day_30")" \
    "$(ask billing_core "select concat_ws(' / ', count(*) filter (where (AdditionalMetaData::json ->> 'completed') = 'true'), count(*) filter (where (AdditionalMetaData::json ->> 'credited') = 'true')) from $tier.cdr where ServiceGroup = 30")"
  same "sum_ad_day_30   rows per (day, payer, campaign, content, rule, zone, site, app, media, outcome)" \
    "$(ask ad_sphere "select count(*) from $tier.sum_ad_day_30")" \
    "$(ask billing_core "select count(*) from (select distinct StartTime::date, InPartnerId, coalesce(AdditionalMetaData::json ->> 'campaignId', '0'), coalesce(AdditionalMetaData::json ->> 'contentId', ''), coalesce(OriginatingCalledNumber, ''), coalesce(AdditionalMetaData::json ->> 'zone', ''), coalesce(AdditionalMetaData::json ->> 'site', ''), coalesce(AdditionalMetaData::json ->> 'app', ''), coalesce(Codec, ''), HangupCause is not distinct from 'NORMAL_CLEARING' from $tier.cdr where ServiceGroup = 30) keys")"
  same "sum_ad_day_30   per content: views, money, units ('' = the record carries none)" \
    "$(ask ad_sphere "select string_agg(quote_literal(c) || ' ' || v || ' ' || m || ' ' || u, ', ' order by c) from (select tup_contentid c, sum(views) v, sum(chargedamount)::numeric(18,6) m, sum(chargedunits)::numeric(18,6) u from $tier.sum_ad_day_30 group by 1) x")" \
    "$(ask billing_core "select string_agg(quote_literal(c) || ' ' || v || ' ' || m || ' ' || u, ', ' order by c) from (select coalesce(r.AdditionalMetaData::json ->> 'contentId', '') c, count(*) v, coalesce(sum(a.BilledAmount) filter (where upper(a.idBilledUom) = 'BDT'), 0)::numeric(18,6) m, coalesce(sum(a.BilledAmount) filter (where upper(a.idBilledUom) is distinct from 'BDT'), 0)::numeric(18,6) u from $tier.cdr r left join $tier.acc_chargeable a on a.idEvent = r.IdCall and a.servicegroup = 30 and a.assignedDirection = 1 where r.ServiceGroup = 30 group by 1) x")"
  same "sum_voice_day_30 calls / connected / cost by unit" \
    "$(ask ad_sphere "select concat_ws(' / ', sum(totalcalls), sum(connectedcalls), (select string_agg(c || ' ' || u, ', ' order by u) from (select sum(customercost)::numeric(18,6) c, tup_customercurrency u from $tier.sum_voice_day_30 group by 2) x)) from $tier.sum_voice_day_30")" \
    "$(ask billing_core "select concat_ws(' / ', (select count(*) from $tier.cdr where ServiceGroup = 30), (select count(ConnectTime) from $tier.cdr where ServiceGroup = 30), (select string_agg(c || ' ' || u, ', ' order by u) from (select sum(BilledAmount)::numeric(18,6) c, idBilledUom u from $tier.acc_chargeable where servicegroup = 30 and assignedDirection = 1 group by 2) x))")"
  same "sum_chargeable_day count / billed by unit" \
    "$(ask ad_sphere "select string_agg(n || ' x ' || b || ' ' || u, ', ' order by u) from (select sum(totalcount) n, sum(billedamount)::numeric(20,8) b, tup_billeduom u from $tier.sum_chargeable_day group by 3) x")" \
    "$(ask billing_core "select string_agg(n || ' x ' || b || ' ' || u, ', ' order by u) from (select count(*) n, sum(BilledAmount)::numeric(20,8) b, coalesce(idBilledUom, '') u from $tier.acc_chargeable group by 3) x")"
done
echo
[ "$differ" = "0" ] && echo "EVERY LINE IS THE SAME: the summaries are billing-core's rows, summed." || { echo "SOMETHING DIFFERS — see above."; exit 1; }
