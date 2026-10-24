from: SS (HARDEN-1 B)  to: ARCH  kind: done  date: 2026-10-25  branch: `reaper-entities` (from `kinds` ead3f2b)  ·  nothing pushed

# HARDEN-1 B — summary-service: the reaper trims every entity that has beans, each by its own beans' bookmarks

DRAFT — kept current after every item (the State lines below); finished last.

## State (newest last; real times, `date`)

- 04:3x +06 — worktree `worktrees/reaper-entities` made from `kinds` ead3f2b.
- 05:03 — RED (fast runner, outside Maven; my labs PostgreSQL 16 127.0.0.1:7457 trust, MySQL 5.7.44 127.0.0.1:7357):
  the new tests on the code of before — 5 red: `OutboxReaperTest` (each entity to its own minimum; the trouble said per
  (schema, entity); the retired key said at start) and the contract's per-entity test on BOTH engines. The two "no wifi bean
  registered" tests are green on the code of before too — it trims `cdr` only — they guard the rule against a change.
- 05:08 — GREEN: `OutboxReaper` per (schema, entity); `SummaryBeanRegistry.registeredBeanNamesByEntity`; the retired key
  said once at start; the jar's three profiles no longer set it. Fast runner: 83/83 (the touched unit classes, the new IT
  tests on both engines, `PostgresTreeIT`).
- 05:15 — mutants MB1–MB5 all red (the report §3), each original back with a fresh mtime, equality asserted.
- 05:17 — docs: decisions §18 (+ §17f's line overwritten with the new fact), README, architecture.md, the deploy page
  (the key row, an upgrade row, a behaviour row). NEXT: commit B1 (code + tests) and B2 (docs + this file), then the gate
  (Maven, git-archive clean copy, unit + ITs on both engines, one hold of the lock).
