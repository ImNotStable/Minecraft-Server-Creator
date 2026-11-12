# Playtest findings: what two AI-built servers taught us

Two threads each built a full game with MSC on 2026-11-12: Lumen Siege (wave defense, Paper) and Gearfall
(seven different trials and a boss, Paper). Both worked, and Gearfall was playable 20 minutes after it started.
This is the merged list of what was hard, what took needless steps and what to build next, in priority order.
Each item names its owner or says it is open. Both threads agree on the order of the first three priorities.

Lumen Siege in numbers: 148 MSC calls, 6 errors, 111 s of tool time (101 s in 7 `dev_cycle`s), about 7.7k result
tokens including images, 26 minutes wall clock. About 15 of those minutes were the EULA wait and 5 the stale
install and workspace. `server_command` was called 81 times, mostly to poll game state.

## Where the time went

| Task | How it went | Tool support today |
|---|---|---|
| Writing the plugin | Fast. 750 lines of Paper code compiled first time; `api_search` was barely needed | good |
| Build, deploy, restart | Fast. `dev_cycle` is one call and its report is short | good |
| Building the world | Slow. Both threads wrote a Python script that emits `build_fill` and `build_set` calls | weak |
| Checking the game works | Mostly impossible. Only admin commands and logs; nobody could walk, jump, aim or click | missing |
| Pointing MSC at the project | Slow, twice. The session's MSC was pinned to the wrong folder and was an old install | weak |
| Debugging game logic | Console only: each game added its own `/x status` and `/x wave N` commands and polled them | weak |
| Leaving the server up | Painful in the first test, one call (`server_detach`) in the second | fixed (#19) |

## Priority 1: let the AI verify what it built

1. **A test player.** Owner: usable-state thread (in progress). Both games shipped with "balance and jumps
   untested". The bot is only useful for games if it can: join an offline-mode server, teleport and walk to a
   position, look at and click a block or entity, use the held item, attack, and report what the player sees
   (chat, title, action bar, boss bar, inventory, health, position, resource pack accepted or not). Jump
   courses and aiming need real movement physics; if that is out of reach, say so in the tool so the AI
   reports it as untested.
2. **See game state, not only blocks.** Done (ADR 0018). `world_summary` counts entities but nothing lists them. Add a
   `world_entities` tool: type, name, tags, health and position for a region. Wave spawns, NPCs and holograms
   could then be checked without a player.
3. **Wait instead of poll.** Done (ADR 0018). `server_wait until=<regex> timeout=<s>` returns when a log or chat line
   matches. It replaces the poll loops behind most of Lumen Siege's 81 `server_command` calls.
4. **Dev server defaults that fit games.** Done (ADR 0018). The dev server is creative and peaceful, so a combat game
   silently does nothing until the plugin overrides it. Add `server_setup mode=game` (survival, normal
   difficulty) and say the mode in the `server_start` report. Also document that chunks without a player do
   not tick: spawners far from spawn need chunk tickets (both games hit this).
5. **Fast-forward.** Done, docs only (ADR 0018). `server_command "tick sprint 600"` already speeds up timers and waves.
   Put it in the server instructions and the project `CLAUDE.md` so AIs use it when testing.

## Priority 2: remove the build scripts

6. **Hollow box and walls.** Owner: usable-state thread (in progress). Every room was four fills.
7. **A batch build call.** Open. One call that takes a list of fills, sets and structures, applies them as one
   edit and returns one undo id. Both scripts existed mainly to send 50 to 400 small calls; a batch removes
   the script, the bridge traffic and the per-call tool overhead.
8. **More shapes.** Open, after hollow boxes and the batch call. Cylinder or ring (hollow, radius, height), disc, sphere or dome, and
   a line from A to B with a width. Every shape and fill should accept a weighted block pattern such as
   `grass_block:78,moss_block:10`, which is what makes terrain look natural.
9. **Stamp, repeat and rotate.** Open. Place one structure at several points, rotated (for example four rifts
   facing the centre) or repeated with an offset (a jump course, pillars, lamp rows).
10. **Terrain by radius bands.** Open, lowest of the build items: floor, then beach, then water.
11. **Check block names before building.** Open, small. 3 of Lumen Siege's 11 generated structures failed on a
   renamed block. A dry-run flag, or validation of the whole legend before the first block is placed, turns
   three failed calls into one message.
12. **Render gaps.** Partly fixed (#20 adds colours for plates, levers and lightning rods). Entities and text
   displays are not drawn, so an NPC or hologram cannot be checked in `world_render`. Draw them as markers.

## Priority 3: starting and pointing MSC

13. **Stale installs.** Owner: usable-state thread (in progress). The build stamp (#18) made it visible; a
   warning when the install is older than the newest release would make it unmissable.
14. **Switch workspace without restarting.** Open. The workspace is the folder Claude Code started in. Both
   threads worked around it with a private stdio-to-HTTP bridge. A `msc_workspace` tool (or a `workspace`
   argument on `project_scaffold` that the core then adopts) removes the workaround.
15. **A stale tool list hides new options.** Follows from the stale-install item. The second thread missed `project_scaffold`'s
    `package` option because the session's old install did not list it.

## Priority 4: fewer steps in every game

16. **A game template.** Done: `project_scaffold template=paper-game`. Both plugins rewrote the same base: join kit, adventure mode, keep inventory,
    respawn point, no natural spawns, fixed time and weather, admin commands to jump to any stage, progress
    saved to config. A `paper-game` template with those and a stage/zone skeleton saves about 150 lines and
    gives every game the test hooks the bot will need. At the least, document the admin debug-command pattern.
17. **Resource pack after a restart.** Fixed in #20 for restarts inside a session. Still open: on
    `server_detach`, also write the pack URL and hash to `server.properties`, so a restart outside MSC keeps it.
    That needs a fixed or configurable `pack_build` port instead of a random one.
18. **Item models.** Open, small. `texture_create model=handheld`, so swords and tools are held correctly
    without a separate `model_create` call.
19. **`server_command` output.** Open, small. Commands that reply with a broadcast return nothing, and
    non-ASCII replies broke a Windows console client. Capture broadcasts made during the command.
20. **EULA step.** Open, docs only. Both threads lost minutes waiting for the owner's answer to arrive in a
    form their permission checks accepted. `server_setup` should print the exact sentence to ask the owner, and
    the docs should say Minestom has no EULA step, for cases where the owner is away.

## Already fixed by the playtests

Wrong `item_model` id in the wiki (#16), `texture_recolor` wording (#16), build stamp in `msc_status` (#16, #18),
block name suggestions (#17), `server_detach`, ground level that ignores builds, command syntax errors and the
scaffold `package` option (#19), pack sent again after a restart and render colours (#20).

## What worked and should not change

`dev_cycle` as one call, `api_search` and `api_class` for names that moved in 26.x, condensed logs ("no errors or warnings"), the ground-level line, text structures for
irregular builds such as the maze, `world_render` for a quick look, `texture_create` from text, and
`server_detach`.

## Third playtest: Tidewright on Minestom (2026-11-12)

A tide puzzle game (five different trials and a boss) built on Minestom with current main (3bc7a82), using
`msc_workspace`, `build_batch`, `server_setup mode=game`, `server_wait` and `player_test`.

| | Lumen Siege (Paper) | Gearfall (Paper) | Tidewright (Minestom) |
|---|---|---|---|
| Start to playable | 26 min (15 of them EULA wait) | about 20 min | 19 min |
| MSC calls | 148 | not counted | 76 |
| `server_command` calls | 81 (polling) | many | 4 |
| Build calls | 14 `build_structure` from a 230-line script | dozens of fills from a script | 3 `build_batch` (35k blocks) |
| Game code | 900 lines, compiled first time | 750 lines | 1,000 lines, one compile error |

**What the new tools removed.** `build_batch` replaced the build scripts: the whole archipelago (6 islands, piers,
sandbar, lighthouse, reef, a flooded tank, palms stamped six times) was one call, with a dry run that checked every
block name first. A short generator was still needed for the lighthouse spiral (no helix shape) and for repeated
posts. `msc_workspace` replaced the bridge's pinned folder and stopped Gearfall over RCON with a world save. No
EULA wait on Minestom. The game's own test environment replaced polling: no `server_wait` was needed.

**Friction, worst first.**
1. **`player_test` cannot join Minestom.** The test player speaks 26.3 and Minestom is 26.2, so it is kicked at login,
   yet the tool said "joined as MscTester (survival, op)" first. Fixed in the PR with this doc: the tool now says the
   versions differ before trying, and a kick at login is an error. Accepting any protocol on the server does not help
   (the configuration packets differ). The trials were verified instead with Minestom's `@EnvTest` players, ticking
   the real server: lighthouse flood, four raid waves, breath, boss shield. That worked well and is worth
   recommending for Minestom.
2. **Minestom worlds were not saved.** The template kept the world in memory, so every `dev_cycle` would have
   erased what `build_batch` built. Fixed: the template now uses an `AnvilLoader` in `run/world` and saves on stop.
3. **`project_scaffold` refused the workspace** after `msc_workspace` or `server_setup` had created `.msc`. Fixed: `.msc`
   no longer counts as content.
4. **The bridge is still needed** for a fresh install: the session's own MSC is pinned to the old install, so
   `msc_workspace` cannot help until Claude Code restarts. Fixed by the `msc` plugin (ADR 0021): it starts the
   newest install, so `/mcp` reconnect picks up a fresh install and no bridge is needed.
5. **`msc_status` says "server: stopped"** for a server detached by an earlier core, while `server_stop` finds and
   stops it. Open, small.
6. **`api_class` hides inherited interface constants**: `MinecraftServer.PROTOCOL_VERSION`, `Attribute.SCALE` and
   `DimensionType.OVERWORLD` all come from interfaces and were missing. Open.
7. **No spiral or helix shape** in `build_batch`, and no `repeat` for single `set` steps. Open, small.
8. **Shadow warnings in `dev_cycle` failures** (ten lines of DuplicatesStrategy notes). Fixed in the template.
9. **The tool log stays in the start folder** after `msc_workspace`. Open, small.
