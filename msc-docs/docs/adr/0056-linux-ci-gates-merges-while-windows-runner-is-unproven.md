# ADR 0056: Linux CI gates merges while the Windows runner is unproven; core-tools audits deferred

Status: accepted, 2026-11-19, decided by Claude for the owner (standing rule: decide, record, merge verified PRs).

## Context

CI moved to Gitea Actions: Linux on omen for every PR, Windows on Flux's self-hosted runner for PRs labelled
`full-ci` and by hand (`ci-windows.yml`). The Windows job has never passed on Gitea. On PR #6 (#755, #756, ADR 0054)
it failed three times on tests the PR does not touch, a different set each run: `AgentSmokeTest` timing out with 16
Gradle workers, then `DevCycleCrashTest`, `DevCycleRecoveryTest` and `BenchRunnerTest` process checks after the
worker cap. The runner is a Windows service, so its account, drive and process view differ from a desktop session.
The same change passed the full build and `e2eTest` on Flux by hand, 20 of 20 repeat runs.

The perfection campaign (ADR 0029) leaves core-tools needing two clean audit rounds in a row after #755 lands. Each
round is two fresh agents reading the whole piece; the owner asked to save usage.

## Decision

1. A PR merges when Linux build and test pass, plus the Minestom e2e job when it runs. A red Windows job blocks a
   merge only once Windows has passed on `main` on Gitea; until then a Windows failure is filed against the runner.
2. Making the Windows runner pass on `main` is its own task.
3. The two core-tools audit rounds are deferred until the owner asks for them; v0.6.0 is cut without them.

## Consequences

- A Windows-only regression can merge unnoticed until the runner is fixed; release notes do not claim a Windows
  CI pass for v0.6.0.
- core-tools stays "not done" in [campaign-2026-11.md](../campaign-2026-11.md) until the deferred rounds run.
