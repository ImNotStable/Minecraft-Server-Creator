# ADR 0039: Upstream versions are checked by a Gradle task run by hand

Status: accepted, 2026-11-14, decided by Claude.

## Context

The repository pins what it builds against: Minecraft (`minecraft` in `gradle/libs.versions.toml` and
`CoreConfig.DEFAULT_MINECRAFT`), the Paper line (`paper = "26.3.build.+"`), Fabric Loader and Fabric API, NeoForge,
Forge, Quilt Loader and Minestom. Nothing told anyone when Mojang, Paper, Fabric, NeoForge, Forge, Quilt or Minestom
shipped something newer, so pins went stale until someone happened to look.

What each audience can do with the news differs:

- A maintainer can bump a pin, update the agents and release.
- The AI using MSC cannot: a newer Minecraft or loader is not usable until MSC's agents and templates support it.
  Paper builds already follow upstream: `server_setup` downloads the newest stable build of the pinned version every
  time, so a newer Paper *build* needs nothing from anyone.

## Decision

1. **`./gradlew checkUpstreamVersions`** (msc-core, `io.github.imnotstable.msc.core.upstream`) reads the catalog and
   the core's default Minecraft version and prints one line per component: name, pinned, newest stable upstream, and
   `NEWER`, `up to date` or `could not check (<reason>)`, then a one-line summary. Sources: Mojang's version manifest
   (`latest.release`), Paper's Fill v3 API (the newest version newer than the pin whose latest build is `STABLE`),
   Fabric meta (loader with `stable: true`), Fabric's Maven (Fabric API for the pinned Minecraft), NeoForge's Maven,
   Forge's `promotions_slim.json` (the recommended build of the newest Minecraft that has one), Quilt meta and Maven
   Central (Minestom). Alphas, betas, pre-releases, release candidates and snapshots never count as newest.
2. **It fails soft and is never part of `build`.** Lookups run in parallel, each limited to 20 s; any failure
   (offline, a non-200 answer, an unexpected body) becomes that component's `could not check` line and the task still
   succeeds. It is not wired into `check`, so a build never touches the network for it.
3. **No tool change.** `msc_status` and `server_setup` say nothing about upstream versions: the AI cannot act on a
   newer Minecraft or loader, Paper builds are already newest, and a network call in `msc_status` would add latency
   and offline noise to the tool the AI calls most. The tool docs budget stays untouched.
4. **No schedule.** No GitHub Actions workflow runs it (Actions are billing-blocked); a maintainer or a maintaining
   session runs it when starting a version bump or periodically by hand.

## Consequences

- One command answers "are our pins behind?"; the output is short enough to paste into an issue.
- Nobody is told automatically: the check is only as fresh as the last time someone ran it.
- Tests use canned answers and a loopback HTTP server, never the network.
- A new pinned component is a new line in `UpstreamCheck.components` with its lookup; an upstream that changes its API
  shows up as `could not check (unexpected answer)` rather than a wrong version.
