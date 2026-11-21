# ADR 0061: Toolset token overruns in the 2026-11-21 bench run

Status: accepted, 2026-11-21, decided by Claude on Jeremiah's request.

## Context

The full bench run 20261121-023026 (at c848018, Sonnet) had the toolset above the baseline's median tokens on three
tasks: texture-resource-pack +29%, code-config-motd +11%, game-sky-sprint +42%. Another AI's analysis of that run was
taken as suggestions. The coordinator read the attempt traces (GitHub branch claude/bench-r9-evidence,
full-1121/20261121-023026); the workspaces (ZIPs) are only on Flux. What the traces showed:

- Pack: every baseline pack.mcmeta said `pack_format` 46, every toolset one 97 (26.3). The check only looked for a
  `pack_format` or `min_format` key, so packs made for another version passed. The toolset runs found `texture` and
  `pack` in their first ToolSearch, then detoured: `msc_status`/`msc_workspace` (3 of 3), loading `api_search` without
  calling it (2), `ls` of the empty workspace (2); one called `texture` twice.
- MOTD: no toolset run touched msc. They followed the baseline's flow (Glob, Read, Write/Edit, gradlew, cp by hand),
  and one spawned a subagent and made 27 tool calls. The gap reads as run-to-run spread plus the preamble, not tool cost.
- Sky Sprint: no msc calls in any toolset run. Shell calls before the first gradlew: baseline 6, 13, 9; toolset 4, 38,
  25, the last two with jar/unzip/javap digging (7 and 3 calls) for Minestom classes.

## Decision

Kept:

1. The pack check asks for the version's format. A `zip` check takes `packFormat`: pack.mcmeta's `pack_format` must
   equal it, or its `min_format` to `max_format` range (a number or `[major, minor]`, majors compared) must hold it.
   texture-resource-pack sets it to the new `{resourcePackFormat}` placeholder. Its one source is msc-textures
   `PackSource.MINECRAFT` / `PackSource.FORMAT` (26.3, 97), which msc-core's `DEFAULT_MINECRAFT` and
   `DEFAULT_PACK_FORMAT` now read too; the bench already may not depend on msc-core (ADR 0004), msc-textures it may.
   When the bench's `minecraftVersion` is another version the placeholder stays unfilled and the check fails saying no
   format is known, rather than guessing.
   Regrade of 20261121-023026, from the pack.mcmeta contents in the traces (the ZIPs were not reachable): baseline 0 of
   3 (format 46 is not 97), toolset 3 of 3 (msc writes `pack_format` 97 with `min_format` 97 to `max_format` 9999). The
   baseline's earlier passes were the checker's error, so its lower token count bought a pack 26.3 does not load.
2. `texture` and `pack` descriptions say the pack is the workspace's and needs no server, and `pack` names the
   Minecraft version and format its pack.mcmeta gets, aimed at the `msc_status`/`msc_workspace` detour. The tool list
   stays under the 16,400-character guard without raising it (`pixel-art` dropped from `texture`'s first line).
3. The minestom-game CLAUDE.md names `api_search`/`api_class` for Minestom classes and its test environment instead of
   unzipping or `javap`-ing jars, and `api_search` on Minestom now also indexes `net.minestom:testing` (Env, EnvTest),
   which it did not, so the cue is true. Shortcut: the index still takes the newest Minestom jar in the Gradle cache,
   not the version the project's catalog pins; with one version cached (the bench) they are the same.

Dropped:

4. MOTD cue: not added. No toolset run looked for msc, so a `dev_cycle` description change cannot reach it, and a cue
   in the Paper preamble or plugin CLAUDE.md needs a paired test to show it helps; that test needs a machine with
   `~/.msc/eula.json` and bench runs (see 6).
5. Bench MCP connect check: already covered. `ClaudeResultParser` reads the session's `mcp_servers` status and
   `BenchRunner` retries, then fails, an attempt whose `requireMcp` server was not connected and called no tool.

Not done:

6. Paired reruns. On omen the agent launch with `--dangerously-skip-permissions` was refused by the session's
   permission guard, so no before/after numbers here: run on Flux
   `run --tasks texture-resource-pack --modes baseline,toolset --repeat 2` and
   `run --tasks game-sky-sprint --modes toolset --repeat 2`.

## Consequences

- Old results.jsonl texture passes are not comparable with new ones; the baseline's pass rate on the task drops.
- Bumping the Minecraft version means changing `PackSource.MINECRAFT` and `FORMAT` together (`checkUpstreamVersions`
  says so).
