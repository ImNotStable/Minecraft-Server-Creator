# ADR 0063: Gitea is the working home; GitHub carries main, releases, packages and the wiki

Status: accepted, 2026-11-21, decided by Claude for the owner, who asked that both be used: GitHub only for main,
releases and packages, the two wikis matching, and most Actions on Gitea.

## Context

The repo moved to Gitea on omen, where branches, PRs and CI live (ADR 0056). GitHub Actions has been blocked by
billing since 2026-11-12. `wiki.yml` was still written for GitHub: it cloned `github.com/<repo>.wiki.git`, which
failed on Gitea, warned "wiki not created yet" and exited green, so the Gitea wiki stayed empty. GitHub got
nothing but a tag push from `release.yml`, and only with a `GH_MIRROR_TOKEN` secret that was never set.

## Decision

- **Gitea is the source of truth.** Work happens there; GitHub is written only by Gitea workflows, with the
  `GH_MIRROR_TOKEN` Gitea secret (a fine-grained GitHub token for this repo: Contents and Workflows read/write).
- **main:** `mirror.yml` pushes each new `main` commit to GitHub's `main`. It never force-pushes (CLAUDE.md), so a
  diverged GitHub `main` makes it fail red instead of losing commits.
- **Releases:** `release.yml` publishes the Gitea release, then pushes the tag to GitHub and creates the same
  release there (same notes, same assets) through the GitHub API. `release-sync.yml` (run by hand) copies any
  Gitea release still missing on GitHub, such as those made before this ADR.
- **Packages:** nothing publishes packages yet. When something does, it publishes to GitHub Packages from a Gitea
  workflow with the same token (plus `write:packages`).
- **Wiki:** `docs/wiki/` is the only source. `wiki.yml` pushes it to the Gitea wiki (with `github.token`) and to
  the GitHub wiki (with `GH_MIRROR_TOKEN`). On Gitea, links to files on the private GitHub repo are rewritten to
  this server (`/blob/<ref>/` becomes `/src/branch/<ref>/`); the GitHub copy keeps them. The Gitea wiki was created
  once with a Home page through the API, since neither server accepts a push to a wiki that has no page.
- **Actions:** every job has `if: github.server_url != 'https://github.com'`, so the mirrored workflows are skipped
  on GitHub (no billed minutes, no red runs) and all CI, release and wiki work runs on Gitea.

## Consequences

- Edits in either Wiki tab are overwritten by the next sync; edit `docs/wiki/` instead.
- Without the secret, the mirror, GitHub release and GitHub wiki steps warn and stay green; the Gitea side works.
- Dependabot is replaced by Renovate on Gitea (ADR 0064).
