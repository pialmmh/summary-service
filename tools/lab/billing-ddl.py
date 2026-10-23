#!/usr/bin/env python3
"""Prints the SQL that makes billing-core's tables in ONE tenant schema of a lab, from billing-core's OWN DDL file
(java/src/main/resources/sql/postgres/billing-tables.sql of its branch postgres-ad-call — read, never changed), the way
its notes say it makes them (BC-0001, BC-0002):

  * each table of the file ("-- @table <name>"), then — for cdr, cdrerror, acc_chargeable — its partitions made in the
    same step: one a MONTH (the month before, this month, three ahead) and a DEFAULT partition; then its indexes
    ("-- @indexes <name>");
  * its grants: summary_service SELECT on the three (and no right at all on a partition), SELECT + DELETE on the
    outbox; ad_sphere SELECT on the four.

    billing-ddl.py <billing-tables.sql> <schema> [<first month YYYY-MM>]   |   psql -U billing_core -d routesphere
"""
import datetime
import re
import sys

ddl_file, schema = sys.argv[1], sys.argv[2]
if not re.fullmatch(r"[a-z_][a-z0-9_]{0,62}", schema):
    sys.exit(f"'{schema}' is not a schema's name")
today = datetime.date.today()
first = (datetime.datetime.strptime(sys.argv[3], "%Y-%m").date() if len(sys.argv) > 3
         else (today.replace(day=1) - datetime.timedelta(days=1)).replace(day=1))          # the month before this one
PARTITION_KEY = {"cdr": "StartTime", "cdrerror": "StartTime", "acc_chargeable": "transactionTime"}

sections, current = {}, None
for line in open(ddl_file, encoding="utf-8"):
    mark = re.match(r"-- @(table|indexes) (\w+)", line)
    if mark:
        current = (mark.group(1), mark.group(2))
        sections[current] = []
    elif current and not line.lstrip().startswith("--"):
        sections[current].append(line)


def month_after(day):
    return (day.replace(day=28) + datetime.timedelta(days=4)).replace(day=1)


out = [f"SET search_path TO {schema};", "BEGIN;"]
partitions = []
for (kind, table) in [key for key in sections if key[0] == "table"]:
    out.append("".join(sections[("table", table)]).strip())
    if table in PARTITION_KEY:
        month = first
        for _ in range(5):                                                               # the month before, this month, three ahead
            after = month_after(month)
            name = f"{table}_p{month:%Y%m}"
            out.append(f"CREATE TABLE {name} PARTITION OF {table} FOR VALUES FROM ('{month} 00:00:00') TO ('{after} 00:00:00');")
            partitions.append(name)
            month = after
        out.append(f"CREATE TABLE {table}_pdefault PARTITION OF {table} DEFAULT;")
        partitions.append(f"{table}_pdefault")
    out.append("".join(sections.get(("indexes", table), [])).strip())
out += [
    "REVOKE ALL ON cdr, cdrerror, acc_chargeable FROM summary_service;",
    "GRANT SELECT ON cdr, cdrerror, acc_chargeable TO summary_service;",
    "GRANT SELECT, DELETE ON summary_affected TO summary_service;                       -- the ruled line (N2)",
    "GRANT SELECT ON cdr, cdrerror, acc_chargeable, summary_affected TO ad_sphere;",
    "REVOKE ALL ON " + ", ".join(partitions) + " FROM summary_service;                  -- no right at all on a partition",
    "COMMIT;",
]
print("\n".join(part for part in out if part))
