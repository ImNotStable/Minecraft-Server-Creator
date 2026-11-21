# ADR 0063: The wiki publishes to Gitea

Status: accepted, 2026-11-21, decided by Claude for the owner (who reported the Gitea wiki was not set up).

## Context

`docs/wiki/` holds the user wiki and `wiki.yml` copies it to the wiki on every push to `main` that touches it.
The workflow was written for GitHub: it cloned `github.com/<repo>.wiki.git` with `secrets.GITHUB_TOKEN`. Since the
repo moved to Gitea that clone failed, the step warned "wiki not created yet" and exited green, so the Gitea wiki
stayed empty. The pages also link to files on the GitHub repo, which is private.

## Decision

- The workflow clones `${{ github.server_url }}/<repo>.wiki.git` with `github.token`, so it publishes to whichever
  server runs it (Gitea now, GitHub unchanged).
- Off GitHub, it rewrites `github.com/ImNotStable/Minecraft-Server-Creator` links to this server, and
  `/blob/<ref>/` to Gitea's `/src/branch/<ref>/`. The source keeps GitHub links so the GitHub wiki still works.
- The Gitea wiki was created once with a Home page through the API (Gitea, like GitHub, cannot push to a wiki
  that has no page yet).
- `docs/wiki/` stays the only source. The plan, ADRs and `tools.md` are not copied in: they already render in the
  repo's file view, and the wiki links there.

## Consequences

- Editing in the Wiki tab is overwritten by the next sync; edit `docs/wiki/` instead.
- If the job token loses write access to the wiki, the push step fails red rather than silently.
