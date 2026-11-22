# ADR 0070: Bench agents run on Flux, never as root on omen

Status: accepted, 2026-11-22, decided by Claude (coordinator) on Jeremiah's request to decide.

## Context

The 0.7.0 bench run (all tasks, toolset ×2) was started on omen from the Remote Control session, which runs as root.
Every attempt ended in 0 s with `--dangerously-skip-permissions cannot be used with root/sudo privileges for security
reasons`: Claude Code refuses that flag for root. The user `jere` on omen has no Claude Code install or login.

Options: run as `jere` (install Claude Code, Jeremiah logs in once), override the check with `IS_SANDBOX=1` (agents
get full root on omen, which also hosts Gitea and the CI runner), or run on Flux, where earlier reruns ran (ADR 0062).

## Decision

Bench runs go to Flux. Agents are never given root on omen, so `IS_SANDBOX=1` is not used. No code change: the bench
already says Linux runs need `--isolate` and a normal user's Claude Code login.

## Consequences

- Bench numbers wait for Flux to be free (the Antigravity rerun runs there first).
- Running on omen later means setting up Claude Code for a non-root user first.
