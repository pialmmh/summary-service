# The profile of a PostgreSQL tenant with the ad beans

One page for the person who deploys summary-service for the wifi (ad) tenant. An ad view is a Call
(`routesphere docs/architecture/ad-is-a-call.md`): billing-core writes each tier's record as a `cdr` row of
service group 30 in the tier's own schema, with one `summary_affected` row. summary-service sums them, for every
tier of the root's tree, in one process.

## 1 · The profile

A profile holds `summary.*` keys only. This is the wifi tenant's; `<…>` is the box's own.

```yaml
summary:
  autostart: true                          # the workers start with the service (the jar's default is false)

  store:                                   # the switch database; a tenant is a SCHEMA of it
    kind: postgresql                       # mysql | postgresql — chosen here, at run time; one jar serves either
    url: jdbc:postgresql://<pg host>:5432/routesphere
    username: summary_service
    password-ref: env:TENANT_BTCL_SWITCH_SUMMARY_SERVICE_PASSWORD   # the NAME of the variable; the value is in the unit's environment only
    max-size: 16

  tenants:
    mode: tree                             # every tier schema of the root's tree, in this one process
    root: btcl                             # the root tenant = the root schema
    prime-context:
      base-url: http://<prime-context host>:7091
    doorbell:
      bootstrap-servers: <kafka host>:9092 # config_event_loader_btcl is heard here

  outbox:
    ping-topic: cdr_summary_ping_btcl      # the ping's topic carries the ROOT. The SAME name on billing-core's side
                                           # (its billing.summary.ping-topic). The topic must exist
    ping-bootstrap-servers: <kafka host>:9092

  enabledSummary:
    - dailyAdSummary                       # sum_ad_day_30
    - hourlyAdSummary                      # sum_ad_hr_30
    - dailyCallSummarySg30                 # sum_voice_day_30
    - hourlyCallSummarySg30                # sum_voice_hr_30
    - dailyChargeableSummary               # sum_chargeable_day
    - hourlyChargeableSummary              # sum_chargeable_hr
  beans:
    dailyCallSummarySg30:  { window: daily,  service-group: 30, table-suffix: "30" }
    hourlyCallSummarySg30: { window: hourly, service-group: 30, table-suffix: "30" }
```

The lab's copy of it, with the lab's addresses: `src/main/resources/config/tenants/btcl/lab/profile-lab.yml`.

## 2 · Where the profile lives, and how a start names it

A deployment keeps its configuration OUTSIDE the jar, in one directory — the one that holds `config/tenants/…`:

```
<the directory>/config/tenants/<tenant>/<profile>/profile-<profile>.yml     the profile
<the directory>/config/tenants.yml                                         (optional) which tenant this deployment serves
```

| | |
|---|---|
| the directory | named by ONE key: `-Dsummary.config.dir=<directory>`, or `SUMMARY_CONFIG_DIR` in the unit's environment. Not named: the unit's working directory (where Quarkus also reads `config/application.properties`). The files stay source-controlled; the directory is where the deploy tool puts them |
| the file | found there, it **wins** — whole: it is not merged with the jar's. Not found there: the jar's own profile of that name is read. So the jar is not rebuilt for a tenant or for a value, a root the jar does not name has its profile, and no box's address is committed in this repository |
| the tenant | the start names it: `SUMMARY_ACTIVE_TENANT=btcl/<profile>` (or `-Dsummary.active-tenant=btcl/<profile>`). Or a file says it: the directory's own `config/tenants.yml` with one entry `enabled: true`. With neither, the first enabled entry of the jar's `config/tenants.yml` is taken — today `tcbl/dev`. **A deployment always says it** |
| the start's first line | says which tenant, what named it, and which FILE was read: `PROFILE tenant btcl, profile bed (named by SUMMARY_ACTIVE_TENANT): the file /etc/summary-service/config/tenants/btcl/bed/profile-bed.yml`. A start that fell back to the jar says `THE JAR'S OWN config/tenants/…`. Read this line at every start |
| Quarkus's own keys | the listener (`quarkus.http.host`, `quarkus.http.port`) go into `config/application.properties` beside the working directory, or into the unit's environment. A profile is not asked for them. The house rule: bind the host's 10.10.x.x address. The service serves `/q/health` only |
| the password | by the NAME of its variable (`password-ref: env:NAME`). The value comes from secreteer's `/etc/secreteer/<tenant>/<app>.env` through the unit's `EnvironmentFile=`. It is never in the profile, a URL or a command line. Wherever a configured value is printed, a password in it is hidden |

```
[Service]
WorkingDirectory=/opt/summary-service                        # config/application.properties here: Quarkus's own keys
Environment=SUMMARY_CONFIG_DIR=/etc/summary-service          # holds config/tenants/btcl/<profile>/profile-<profile>.yml
Environment=SUMMARY_ACTIVE_TENANT=btcl/<profile>             # or: /etc/summary-service/config/tenants.yml enables it
EnvironmentFile=/etc/secreteer/btcl/summary-service.env
ExecStart=/usr/bin/java -jar /opt/summary-service/quarkus-app/quarkus-run.jar
```

## 3 · What the start says, and what it refuses

The first lines of a start, before anything is dialled:

```
PROFILE tenant btcl, profile <profile> (named by SUMMARY_ACTIVE_TENANT): the file /etc/summary-service/config/tenants/btcl/<profile>/profile-<profile>.yml
ENDPOINT store jdbc:postgresql://<pg host>:5432/routesphere hosts=<pg host> …
ENDPOINT ping-kafka <kafka host>:9092 hosts=<kafka host> …
ENDPOINT tree-prime-context http://<prime-context host>:7091 hosts=<prime-context host> …
ENDPOINT doorbell-kafka <kafka host>:9092 hosts=<kafka host> …
ENDPOINT listens-on <address>:<port> …
the store's password: from the environment variable TENANT_BTCL_SWITCH_SUMMARY_SERVICE_PASSWORD (summary.store.password-ref)
```

| a start is refused, in words, when | |
|---|---|
| `summary.config.dir` / `SUMMARY_CONFIG_DIR` names something that is not a directory | the jar's profile is not read behind a wrong directory |
| the tenant the start serves has no profile file, in the directory or in the jar | a slip in the name does not come up green with nothing to serve. It says where it looked |
| the profile file cannot be parsed, or the tenant is not written `<tenant>/<profile>` | the jar's profile is not read in its place |
| the variable `password-ref` names is not set, or is empty | it names the variable. Nothing was dialled |
| `password` and `password-ref` are both set; `password-ref` is not `env:NAME`; the URL carries a password (a parameter, or `user:password@` before a host) | what was written there is not shown |
| `kind` says one engine and the URL is the other's; `kind` is not `mysql` or `postgresql` | |
| the workers are to start (`autostart`) and the profile names no store | it asks whether the start named its tenant |
| `tenants.mode: tree` without `root` or `prime-context.base-url` (when the workers are to start) | |
| (a lab) `summary.endpoints.local-only: true` and an endpoint is not THIS BOX | every such endpoint is named. This box = `localhost`, a loopback address, or an address one of this box's own interfaces holds. A host name is never looked up, so a name is refused; so is a listener on every interface (`0.0.0.0`) |

`-Dsummary.endpoints.print-only=true` prints these lines and exits: nothing is started and nothing is dialled.
Exit code 0 = every host is this box, 3 = not, 4 = the configuration refuses the start (a line
`REFUSING TO START: …` says why: most often the named password variable is not set).

What does **not** refuse a start: a store, a broker or a prime-context that does not answer. The service stays
up, says it (ERROR), and tries again: after `retry-seconds`, each try a little later, up to `refresh-seconds`.

## 4 · What it needs, and what it makes

| | |
|---|---|
| the role | `summary_service`, LOGIN, its password in secreteer |
| in each tier schema | USAGE and CREATE (prime-context's provisioning gives them) |
| on billing-core's tables | SELECT; DELETE on `summary_affected` only (billing-core grants it when it makes the table). The reaper deletes the outbox rows every bean has passed — nothing else |
| it makes, at its first use of a schema | `summary_offset` (the bookmarks), `summary_affected_dlq` (poison rows), and one table per enabled bean: `sum_ad_day_30`, `sum_ad_hr_30`, `sum_voice_day_30`, `sum_voice_hr_30`, `sum_chargeable_day`, `sum_chargeable_hr`. Plain tables, the table and its indexes in one transaction |
| it never makes | `summary_affected`, `cdr`, `cdrerror`, `acc_chargeable`. Only billing-core makes them. A tier that has no `summary_affected` yet WAITS: one WARN names the schema and the table, and it is picked up when the table appears — no restart |
| who reads its tables | `ad_sphere`, through prime-context's default privileges. The DDL of the ad pair: `src/main/resources/db/postgres/sum_ad.sql`; its columns: the README |
| after an upgrade | an ad table made by an earlier version is brought up to the new description at its first use — a column it lacks is added (`tup_contentid`; the old rows read `''`), a text column that is narrower is widened (`tup_rulecode`, 20 → 64; what it holds stays). A WARN line says each change. A table that is as described gets no statement. The service owns its tables, so it needs no new right |
| on its own connections | the tier's schema and `standard_conforming_strings = off` are set with `SET LOCAL`, inside each transaction. Nothing stays on a connection; no other session is touched |
| Kafka | it only listens: `cdr_summary_ping_<root>` (billing-core) and `config_event_loader_<root>` (prime-context). It publishes nothing and makes no topic. **Both topics must exist** before billing-core starts: without the ping's topic billing-core's ingest waits 60 s per tier per batch, and this service runs on the poll alone, with about one WARN a second that names the topic; made later, it is heard with no restart. A broker that is away costs latency only: the poll and the refresh go on |
| one process per root | a second process on the same tree would wait on the first one's bookmark locks; it is not a way to scale |

## 5 · How it behaves

| | |
|---|---|
| a view | billing-core commits a tier's batch and pings `{tenant, entity, rows}`. That tier's workers wake. Measured in the lab: the view is in the summary about 0.1 s after its ping. With no ping the poll finds it (`poll-interval-seconds`) |
| a reseller made at run time | prime-context rebuilds the tree, then rings `config_event_loader_<root>`. The tree is read again; the new schema's tables are made and its workers start. No restart. The tree is also read every `refresh-seconds` |
| a schema seen for the first time | every bean starts at offset 0: what billing-core wrote before is summed |
| a bean switched on later | on a schema already served it starts at the outbox head: it sums from now |
| a tier the tree lost | its workers stop. Its bookmarks and tables stay; served again, it goes on from them |
| `tup_tenant` | always the schema's own name. One pair of ad tables per tier schema |
| money and units | `chargedamount` = what was paid in money (the chargeable's unit is BDT). `chargedunits` = what was paid from a package, in its unit. Never added |
| a long text | a text of the key is as wide as its source (zone, site, app, content id, rule code: 64), so it is stored whole. A wider one is cut to its column by the service; the database never refuses a row over a width |
| per content | a row of `sum_ad_*` is one content of one campaign (`tup_contentid` = the record's `contentId`; `''` when it carries none). The campaign's numbers are the sum over its contents |
| time | a window is cut on the `cdr`'s own wall clock (the tenant's, Asia/Dhaka). No zone is converted; the JVM's zone does not matter |

Do not delete all the bookmarks of a schema: it would then look new and be summed again from 0. To retire one
bean, delete that bean's row only.

## 6 · Every key

What a START is given (an option of the JVM or a variable of the unit — these are read before any profile):

| option / variable | what |
|---|---|
| `-Dsummary.config.dir` / `SUMMARY_CONFIG_DIR` | the directory that holds `config/tenants/…`. Not set: the working directory |
| `-Dsummary.active-tenant` / `SUMMARY_ACTIVE_TENANT` | `<tenant>/<profile>`: the tenant this start serves. Not set: the first enabled entry of the directory's `config/tenants.yml`, else of the jar's |

The keys of a profile:

| key | default | what |
|---|---|---|
| `summary.autostart` | `false` | start the workers, the ping listener and the reaper. Off: the beans are registered and nothing is dialled |
| `summary.store.kind` | from the URL | `mysql` or `postgresql` |
| `summary.store.url` | — | the JDBC URL. PostgreSQL: the switch database. In tree mode it names no schema |
| `summary.store.username` | empty | |
| `summary.store.password-ref` | — | `env:NAME`: the password is that environment variable's value |
| `summary.store.password` | empty | the inline form, kept for the deployments that have it. Never with `password-ref` |
| `summary.store.min-size` / `max-size` | `0` / `16` | the pool. A worker holds a connection only for the length of one transaction |
| `summary.store.acquisition-timeout-seconds` | `30` | how long a worker waits for a connection before its drain fails (and is tried again) |
| `summary.tenants.mode` | `single` | `single`: one schema, the URL's own. `tree`: every schema of the root's tree |
| `summary.tenants.root` | — | the root tenant's schema; names the doorbell's topic |
| `summary.tenants.prime-context.base-url` | — | where `POST /get-specific-tenant-root` is asked (a read road) |
| `summary.tenants.doorbell.topic-base` | `config_event_loader` | the topic is `<base>_<root>` |
| `summary.tenants.doorbell.bootstrap-servers` | the ping's | |
| `summary.tenants.refresh-seconds` | `300` | the tree is read again, doorbell or not. `0` = never on a timer |
| `summary.tenants.retry-seconds` | `15` | the first wait before something that could not be served is tried again |
| `summary.tenants.reload-debounce-ms` | `1000` | several rings in a burst are one read |
| `summary.outbox.ping-topic` | `cdr_summary_ping` | on a deployment: `cdr_summary_ping_<root>`, the same on billing-core's side |
| `summary.outbox.ping-bootstrap-servers` | `127.0.0.1:9092` | |
| `summary.outbox.poll-interval-seconds` | `5` | the fallback drain |
| `summary.outbox.max-rows-per-tx` | `1` | outbox rows per transaction (one row = one billing batch) |
| `summary.outbox.segment-size` | `1000` | rows per multi-row INSERT |
| `summary.outbox.reaper-interval-seconds` | `60` | |
| `summary.outbox.quarantine-after` | `8` | tries on a poison row before it goes to `summary_affected_dlq` |
| `summary.outbox.entity-type` | `cdr` | the entity the reaper trims |
| `summary.enabledSummary` | — | the beans to run |
| `summary.beans.<name>.window` / `service-group` / `table-suffix` | — | a call bean made from the profile alone (group 30: suffix `"30"`) |
| `summary.zone` | `Asia/Dhaka` | MySQL only: "this year" of the partition horizon |
| `summary.endpoints.local-only` | `false` | a lab's guard: refuse a start that would reach, or listen on, anything but this box (its first name, `summary.endpoints.loopback-only`, is the same switch). Never set on a deployment |

Threads: one per (schema, bean) — 6 for each tier with this profile. They sleep until a ping or the poll.

## 7 · The lab

Everything on 127.0.0.1, in containers named `ss-*`; no password exists.

| | |
|---|---|
| `tools/lab/pg-lab.sh up \| mysql \| kafka \| all \| down` | PostgreSQL 16 (7643), MySQL 5.7.44 (7633), Kafka 3.9 (7692) |
| `tools/lab/run-lab.sh <tenant>/<profile>` | the only way a service is started in the lab: it shows the profile and the endpoints the start resolved, and starts only when every host is this box. `--show` stops after showing |
| `tools/lab/tree-e2e.sh` | the tree's story with the packaged jar: billing-core's tables from its own DDL file, rows written as it writes them, a reseller made at run time |
| `tools/lab/reconcile-with-billing-core.sh` | the summaries beside billing-core's OWN rows (its lab command first; the file's head says how) |
| `tools/lab/secret-e2e.sh` | the password by its variable's name, against a role that needs one |
| `tools/lab/upgrade-e2e.sh [an older commit]` | an upgrade of a deployment that has rows, with two packaged jars: the older version sums, this one starts on the same schemas, brings its tables up to date and leaves the rows as they were |
| `tools/lab/ping-topic-e2e.sh` | a ping topic that does not exist (a broker of its own, auto-create off): what it costs, and that it is heard, with no restart, once made |
| `tools/lab/this-box-e2e.sh` | the lab's key: an address of this box's own interface is this box (a real prime-context never listens on loopback); another box's refuses the start |

`mvn verify` runs the integration tests against the three containers; a test whose lab is away is skipped,
never passed. MySQL's lab is named with
`-Dsummary.it.mysql.url='jdbc:mysql://127.0.0.1:7633/?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true'`.
