# ADR 0064: Renovate on Gitea replaces Dependabot

Status: accepted, 2026-11-22, decided by Claude for the owner, who asked for a Gitea-side replacement so GitHub
can carry only main, releases and packages (ADR 0063).

## Context

Dependabot runs on GitHub and opens its PRs there. Since Gitea is the working home and GitHub's `main` is a mirror,
a Dependabot PR can't be merged on GitHub without diverging `main`. Gitea has no built-in dependency updater.

## Decision

- `.github/workflows/renovate.yml` runs the Renovate container (`ghcr.io/renovatebot/renovate:44`, a major tag
  Renovate itself bumps) on the omen runner every Monday 06:00 UTC and by hand. It opens PRs on Gitea.
- `renovate.json` keeps Dependabot's behaviour: Gradle (catalog, settings plugins, wrapper) in a `minecraft
  platforms` group and a `libraries and plugins` group, workflow actions in an `actions` group, at most 5 open
  PRs, `deps:`/`ci:` commit prefixes, Fabric API skipped (ADR 0012). New: `actions/upload-artifact` is skipped,
  because v4+ refuses Gitea. Dependency dashboard issue off, onboarding PR off.
- Renovate authenticates with a `RENOVATE_TOKEN` Gitea secret. The Actions job token can't read `/user`, which
  Renovate needs, so it is a personal token with repository and issue read/write plus user and organization read
  (a separate bot account keeps its PRs apart from the owner's). `GH_MIRROR_TOKEN` doubles as Renovate's
  github.com token for release notes.
- `.github/dependabot.yml` is removed.

## Consequences

- Dependabot security alerts on GitHub still work (they need no config file) and stay the vulnerability signal
  (`security-triage.md`); they no longer turn into PRs.
- Without the secret the workflow warns and stays green.
