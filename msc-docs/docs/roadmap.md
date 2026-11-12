# Roadmap: what to optimize, add and cut next

Written 2026-11-12 against main `d47e55a` (v0.2.0). Sources: [plan.md](plan.md), ADRs 0001 to 0018,
[playtest-findings.md](playtest-findings.md), the v0.2.0 notes and the code. Items are ranked by value to an AI
building a server. The coordinator starts threads from this list and ticks items off here.

Not repeated here because threads already own them: Fabric end-to-end test, bench baseline and targets,
tool-surface shrink, CRLF fixes, `player_test` on Minestom (#30).

**Where** says what the work needs: *cloud* (unit tests, Minestom, CI) or *PC* (a real Paper or Fabric server,
which needs the owner's EULA acceptance, or a Windows check).
**Parallel** says whether it can start now beside everything else: *safe*, or *after X*.

## Where the time still goes

| Cost seen in the playtests | Size | Answer |
|---|---|---|
| Waiting for the owner's EULA answer | 15 of 26 min (Lumen Siege) | N1 |
| Stale install, MSC pinned to the wrong folder | 5 min, in all three playtests | N2 |
| `dev_cycle` | 101 s of 111 s tool time | O1 |
| "Balance and jumps untested" | every game | A1, A2 |
| Regressions found only on the PC (#29 failed on Windows only) | one integrator round each | N3, N4 |

## Now: start all of these together

| # | Item | Kind | Where | Parallel |
|---|---|---|---|---|
| N1 | **One EULA acceptance per machine.** The owner runs one command once (`msc eula accept`, which shows the EULA link and writes `~/.msc/eula.json`); `server_setup` then reuses it for every new server on that machine. Nothing accepts by default, and a tool argument alone still cannot create the file. Removes the largest wait in every Paper and Fabric session. New ADR. | add | cloud to build, one owner action on the PC | safe (msc-core `server/` only) |
| N2 | **Install that cannot go stale.** Ship MSC as a Claude Code plugin (MCP config plus a launcher that runs the newest version under `~/.msc/core/`), so a new release is picked up without re-adding the server, and the workspace is the session's folder. Retires the private stdio-to-HTTP bridge every playtest needed. | add, cut | cloud to build, PC to check on Windows | safe (new `msc-plugin/` folder, release workflow) |
| N3 | **A real server in CI.** Minestom needs no EULA, so CI can scaffold `minestom-server`, start it through msc-core over MCP stdio, run `build_batch`, `world_slice`, `world_entities`, `player_test` and `dev_cycle`, and stop. Also run `verifyTemplates` there. Every PR is then checked end to end without the PC. | add | cloud | after #30 (needs the 26.2 test player) |
| N4 | **Windows job in CI.** The owner's machine is Windows and CI is Ubuntu only; #29 was a Windows-only failure. Add `windows-latest` to the matrix. | add | cloud | safe (CI/CD thread) |
| N5 | **One shared agent library.** `AgentRoutes`, `CoreAddress`, `EditHistory`, `MainThread`, `PackedPos` and `SlicedJob` are identical in the three agents, and `EditJob`, `ReadJob`, `UndoRecord`, `ChangeSource` nearly so. Move them, with their tests, to `msc-agent-common`; each agent keeps only its platform calls. About 1,000 lines go, and a protocol change is made once, not three times. | shrink | cloud | after the Fabric end-to-end test; needs the foundation thread for `settings.gradle.kts`; no other agent work while it runs |
| N6 | **Game templates for Minestom and Fabric.** `paper-game` saved about 150 lines per game. Minestom was the fastest playtest and has no EULA step, so `minestom-game` (stages, kit, respawn, admin command, an `@EnvTest` example) makes it the default for quick prototypes. `fabric-game` follows the Fabric end-to-end test. | add | cloud | safe (msc-templates only) |
| N7 | **Renderer: draw what is missing.** Entity and text-display markers, sign text, coordinate labels on iso views. Library work first; the `world_render` wiring is a few lines in msc-core afterwards. | add | cloud | safe (msc-renderer only) |

## Next: each waits for something

| # | Item | Kind | Where | Parallel |
|---|---|---|---|---|
| O1 | **Faster `dev_cycle`.** Measure first (Gradle, stop, start, agent connect), from the tool log. Then: keep the Gradle daemon and configuration cache warm, skip the restart when only pack or config files changed, and start the server while Gradle is still testing. Target: half the 14 s average. | optimize | cloud on Minestom, PC for Paper numbers | after the bench baseline, so the gain is measured |
| A1 | **The test player sees what a player sees.** It reports chat, titles, health and deaths. Still missing from finding 1: inventory and held item, boss bar, scoreboard, whether the resource pack loaded, and click on an entity. | add | cloud on Minestom, PC for Paper | after #30 |
| A2 | **Can a player get there?** `world_path from to`: walk and jump reachability from the block data in the core, with the first gap or wall that blocks it. Checks a jump course in one call with no server tick. Returned as an option on an existing tool if the shrink thread prefers. | add | cloud | after the tool-surface shrink |
| A3 | **Small friction, one PR.** From the Minestom playtest: `msc_status` shows a detached server as running; `api_class` lists constants inherited from interfaces; `helix` shape and `repeat` on `set` in `build_batch`; the tool log follows `msc_workspace`. | fix | cloud | after the tool-surface shrink (same files) |
| S1 | **`tools.md` and the wiki tool page from code.** `tools.md` is hand-written and already wrong (34 tools, a 17,500 guard, a "Not in 0.1" list of things that exist). Generate both from the tool definitions and fail the build when they drift. | shrink | cloud | after the tool-surface shrink |
| B1 | **Game-scale bench task, and a Minestom mode.** The 8 tasks are small; the playtests showed the real cost is in a whole game. Add one "build this minigame" task scored by `player_test` and `@EnvTest` checks. A Minestom mode lets the bench run with no EULA, on CI if headless Claude Code is available there. | add | cloud to write, PC to run until a CI run is proven | after the baseline run |
| A4 | **What changed?** `world_diff`: snapshot a region, later list the changed blocks as a text structure. Shows what a game or a redstone tick did, and what a build call really placed. | add | cloud | after the tool-surface shrink |
| S2 | **One Minecraft version in the test player.** When Minestom ships 26.3, drop the 26.2 class loader and `Compat` reflection that #30 adds. | shrink | cloud | after a Minestom 26.3 release (the weekly Dependabot PR is the signal) |

## Later

- Block entities (chests, signs) in `structure_save` and `structure_load`; vanilla `.nbt` structures.
- Blockstate and model JSON in the renderer, for exact shapes and pack textures.
- More item shapes in `texture_generate` (tool, food, armour).
- Public release: license, `v1.0` once the bench shows a gain on all three metrics.

## Cut or hold

| What | Why |
|---|---|
| Image-model textures | No playtest asked for them; text pixel art was enough in all three games. |
| More platforms (Folia, Sponge, proxies) | Three agents is already three times the upkeep; add none before N5 and a bench result. |
| Undo that survives a core restart | Never missed in a playtest; `structure_save` covers the need. |
| Room and wall detection (old post-MVP item 4) | Replaced by A2 and A4, which answer the questions the playtests actually had. |
| New tools as the default way to add features | Each one costs every conversation about 130 tokens. Prefer an option on an existing tool; the shrink thread owns the budget. |
| `LifecycleTools.java` (802 lines) as one file | Split by platform when N1 or O1 touches it, not as its own task. |

## Order and collisions

- msc-core `tools/` is the crowded place: the shrink thread goes first, then A2, A3, A4 and S1 one after another or as one thread.
- N5 needs a quiet window in all three agent folders: after the Fabric end-to-end test and #30, before A1.
- Everything in "Now" except N3 and N5 can start at once: five threads, none sharing a folder.
- PC time is only needed for: N1's single owner action, the N2 Windows check, Paper numbers for O1 and A1, and bench runs.
