# ADR 0125: What stays in the public repository

Status: accepted, 2026-11-27. Decided by Claude; Jeremiah asked whether the dot files and development docs should be
ignored and kept private now that the repository is public (ADR 0115).

## Context

GitHub carries `main` only, mirrored from Gitea (ADR 0063), and cloud Claude sessions clone it from GitHub. An audit of
every tracked file and of the full history (650 commits) found no secrets: no tokens, keys or passwords, only test
fixtures. It found three kinds of files besides product code:

- Agent setup: `CLAUDE.md`, `.claude/` (settings, hooks, the `ci-cd` agent), `.serena/project.yml`,
  `.claude-plugin/marketplace.json`.
- Decision records and design notes: `msc-docs/` (plan, ADRs, protocol, tools, playtest findings, plugin survey,
  benchmark and campaign reports, release notes).
- Machine-specific details: a Windows checkout path in `CLAUDE.md` and `agent-efficiency.md`, `/home/jere` in
  `scripts/ci/actcache-prune.sh`, the LAN address of Gitea in the `ci-cd` agent.

## Decision

1. **Agent setup stays tracked.** Cloud sessions get it only from the clone: the environment's setup script runs
   `.claude/hooks/session-start.sh` (ADR 0116), the `ci-cd` agent and the hooks come from `.claude/`, and Serena reads
   `.serena/project.yml` (ADR 0120). Untracking them breaks every cloud thread. They hold no secrets, and committed
   agent config is normal in public repositories; it helps contributors who use the same tools.
2. **Decision records stay tracked.** ADRs, the plan and the reports they cite are how the project explains itself;
   removing them breaks links from ADRs and the plan's Decisions table (`DecisionsIndexTest`).
3. **Stale work logs are removed** (git history keeps them): `roadmap.md` (round 2 list from v0.3.0, superseded by
   the plan, nothing links it) and `campaign-matrix-baseline.md` / `campaign-matrix-run2.md` (raw run logs of the
   finished campaign, linked only from each other; `campaign-2026-11.md` keeps the outcome).
4. **Machine-specific details move out.** Paths in `CLAUDE.md` and docs become generic; personal paths and notes for
   one machine go in an untracked `CLAUDE.local.md`, which Claude Code loads beside `CLAUDE.md`. `.gitignore` now
   covers `CLAUDE.local.md`, `.claude/settings.local.json` and the stray root folders `.msc/` and `.claire/`. The LAN
   address in the `ci-cd` agent stays: it is a private (RFC 1918) address the agent needs for a manual release on
   omen, and it is unreachable from outside.

## Not done here

- **The personal email in history.** About 410 commits, including every Gitea web merge up to now, carry the owner's
  personal email as author. Only a history rewrite removes it from GitHub, and that needs the owner's explicit OK.
  New merges stop leaking it once the Gitea account uses its GitHub noreply address (or Gitea's "Keep email private").
