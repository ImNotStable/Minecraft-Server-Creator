# msc-bench plan

Phase: MVP. Steps are in order; each ends with passing tests.

- [x] Write the first task set with clear pass criteria (9 tasks: 3 code, 2 build, 1 world, 2 texture, 1 Minestom game).
- [x] Runner and report format (`results.jsonl` + `report.md`; tool log format in ADR 0004).
- [ ] Validate every task on a real Paper 26.3 server (a reference solution should pass each check).
- [x] Baseline run without the toolset (3 repeats per task): the first full run, 2026-11-12, both modes.
- [x] Set targets from the baseline, then run with the toolset and compare (targets in the plan's Goal and success metrics, ADR 0004).
