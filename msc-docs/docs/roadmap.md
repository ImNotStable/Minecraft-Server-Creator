# Roadmap: what to optimize, add and cut next

Round 2, written 2026-11-12 against main `94d28ee` (v0.3.0). Sources: [plan.md](plan.md), ADRs 0001 to 0021,
[playtest-findings.md](playtest-findings.md), the v0.3.0 notes and the code. Items are ranked by value to an AI
building a server. The coordinator starts threads from this list.

**Where** says what the work needs: *cloud* (unit tests, Minestom, CI) or *PC* (a real Paper or Fabric server, a
Windows check, or a bench run). **Parallel** says whether it can start now beside everything else.

## Done in round 1 (v0.3.0)

One EULA acceptance per machine (ADR 0020), Claude Code plugin install (ADR 0021), Windows CI, `minestom-game` and
`fabric-game`, renderer entities and sign text, the smaller tool list (36 to 30 tools, ADR 0019), `player_test` on
Minestom and Fabric, two unused protocol ops removed.

Carried over, not started: a real server in CI (was N3) and the shared agent library (was N5). Both are unblocked
now and lead this round.

## What still costs the most

| Cost | Evidence | Answer |
|---|---|---|
| A break is found only when someone play-tests on the PC | every Fabric and Minestom fix this round came from a manual run | R1 |
| Each agent change is written three times | #37 and #41 each touched all three agents; 4,200 lines, about a third shared | R2 |
| "Balance and jumps untested" | every playtest report | R3, R4 |
| `dev_cycle` is nearly all the tool time | 101 s of 111 s in Lumen Siege | R5 |
| No number yet says MSC helps | baseline done once, full run cut off twice | R7 |
| 8 texture tools are a quarter of the tool list | untested since the shrink | R6 |

## Now: seven threads, none sharing a folder

| # | Item | Kind | Where | Parallel |
|---|---|---|---|---|
| R1 | **A real server in CI.** Minestom needs no EULA: CI scaffolds `minestom-game`, starts it through msc-core over MCP stdio, runs `build_batch`, `world_slice`, `world_entities`, `world_render`, `player_test` and `dev_cycle`, then stops. Also runs `verifyTemplates`. Linux and Windows. | add | cloud | safe (an `e2eTest` task in msc-core's test tree, `ci.yml`) |
| R2 | **One shared agent library, `msc-agent-common`.** Six classes are identical in the three agents and four nearly so. Each agent keeps only its platform calls; about 1,000 lines go. | shrink | cloud | safe if no other thread edits an agent folder; needs the foundation thread for `settings.gradle.kts` |
| R3 | **The test player sees what a player sees.** Add inventory and held item, boss bar, scoreboard, action bar, whether the resource pack loaded, and click on an entity. Reported only when asked for, to keep results short. | add | cloud on Minestom; Paper check on the PC | safe (msc-bot, `PlayerTools`) |
| R4 | **World checks without a player.** Three steps on existing tools, no new tool: `path from to` (walk and jump reachability, names the first gap or wall), `diff` against a snapshot of a region, and a `helix` shape plus `repeat` on `set` in `build_batch`. Also list constants inherited from interfaces in `api_class`. | add | cloud | safe (msc-core `world/`, `building/`, `api/`) |
| R5 | **Faster `dev_cycle`.** Log the time of each phase first (Gradle, stop, start, agent connect). Then skip the restart when only pack or config files changed, keep the Gradle daemon and configuration cache warm, and start the server while tests run. Split the 856-line `LifecycleTools.java` by platform on the way, and make `msc_status` show a detached server as running. Target: half the 14 s average. | optimize, shrink | cloud on Minestom; Paper numbers on the PC | safe (msc-core `server/`, `LifecycleTools`) |
| R6 | **Texture round.** Build one pack-heavy game on Minestom (custom items, blocks, an animated texture), then fold the 8 texture tools into about 4 by what it used: one `texture` tool with a `from` (text, generator, recolor, animate), `model_create`, `texture_view`, and `pack` (check, build). More item shapes (tool, food, armour) if the game wanted them. | shrink, add | cloud | safe (`TextureTools`, msc-textures) |
| R7 | **Bench that can run anywhere.** Add a Minestom mode (no EULA), one game-scale task scored by `player_test` and `@EnvTest`, read the machine's `eula.json` and drop `bench.local.json`, and stop a run at the first usage-limit error. Then finish the full run and write targets into `plan.md`. | add, shrink | cloud to write; runs on the PC, one at a time | safe to write (msc-bench only); the run waits for a quiet hour on the PC |

## Next: each waits for something

| # | Item | Kind | Where | Waits for |
|---|---|---|---|---|
| R8 | **`tools.md` and the wiki tool page generated from code**, with a build check for drift. `tools.md` still carries a "Not in 0.1" section and hand-copied budgets. (Done, ADR 0026: tables generated, `checkToolDocs` in `build`; the wiki page is checked for the same tool set.) | shrink | cloud | R4, R5, R6 (they all change tool text) |
| R9 | **Trim tool results by measurement.** Use the bench tool log to cut the three most token-heavy results. | optimize | cloud | R7's full run |
| R10 | **Fourth playtest as the release gate.** One game per platform from the same brief on the installed plugin; compare calls, minutes and tokens with the first three. Feeds v0.4.0 notes. | verify | Minestom in the cloud; Paper and Fabric on the PC | R3 to R6 merged |
| R11 | **Check the plugin on Windows** (install, remove the old `claude mcp add msc`, `installMsc` while a core runs, `/mcp` reconnect). Do it inside R10's PC session, not as its own prompt. | verify | PC | R10 |
| R12 | **One Minecraft version in the test player.** Drop the 26.2 class loader and `Compat` reflection. | shrink | cloud | a Minestom 26.3 release |
| R13 | **Remove the flat-install fallback** that #44 keeps for old installs. | shrink | cloud | v0.4.0 |

## Later

- Block entities (chests, signs) in `structure_save`; vanilla `.nbt` structures.
- Blockstate and model JSON in the renderer, for exact shapes and pack textures.
- Launcher downloads the newest release itself, once the repository is public.
- Public release: license, `v1.0` once the bench shows a gain on quality, speed and tokens.

## Cut or hold

| What | Why |
|---|---|
| Image-model textures | No playtest asked for them. Revisit only if R6 shows text pixel art is the limit. |
| More platforms (Folia, Sponge, proxies) | None before R2 and a bench result. |
| Undo that survives a core restart | Never missed; `structure_save` covers it. |
| Room and wall detection | Replaced by R4's `path` and `diff`. |
| Looking at a pack in a real Minecraft client | Needs the owner's client and screen. R3 reports whether the pack loaded; `world_render` uses the pack's textures. |
| New tools | The list is 30 tools under a 16,000-character guard. Everything in this round is a step or option on an existing tool, and R6 removes four (done, ADR 0023). |

## Order and collisions

- R1 to R7 can all start at once. R2 is the only one that touches the agent folders; R4, R5 and R6 are all in
  msc-core but in different packages and tool classes. Each raises or lowers the tool-list guard, so the second
  and third to merge must merge main first.
- R1 should merge first: every later PR then gets an end-to-end check for free.
- PC time this round: R7's bench run, Paper timings for R3 and R5, and one session for R10 and R11. Everything
  else runs in the cloud, and nothing needs an answer from the owner.
