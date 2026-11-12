# Playtest findings: what two AI-built servers taught us

Two threads each built a full game with MSC on 2026-11-12: Lumen Siege (wave defense, Paper) and Gearfall
(seven different trials and a boss, Paper). Both worked, and Gearfall was playable 20 minutes after it started.
This is the merged list of what was hard, what took needless steps and what to build next, in priority order.
Each item names its owner or says it is open.

## Where the time went

| Task | How it went | Tool support today |
|---|---|---|
| Writing the plugin | Fast. 750 lines of Paper code compiled first time; `api_search` was barely needed | good |
| Build, deploy, restart | Fast. `dev_cycle` is one call and its report is short | good |
| Building the world | Slow. Both threads wrote a Python script that emits `build_fill` and `build_set` calls | weak |
| Checking the game works | Mostly impossible. Only admin commands and logs; nobody could walk, jump, aim or click | missing |
| Pointing MSC at the project | Slow, twice. The session's MSC was pinned to the wrong folder and was an old install | weak |
| Leaving the server up | Painful in the first test, one call (`server_detach`) in the second | fixed (#19) |

## Priority 1: let the AI verify what it built

1. **A test player.** Owner: usable-state thread (in progress). Both games shipped with "balance and jumps
   untested". The bot is only useful for games if it can: join an offline-mode server, teleport and walk to a
   position, look at and click a block or entity, use the held item, attack, and report what the player sees
   (chat, title, action bar, boss bar, inventory, health, position, resource pack accepted or not). Jump
   courses and aiming need real movement physics; if that is out of reach, say so in the tool so the AI
   reports it as untested.
2. **See game state, not only blocks.** Open. `world_summary` counts entities but nothing lists them. Add a
   `world_entities` tool: type, name, tags, health and position for a region. Wave spawns, NPCs and holograms
   could then be checked without a player.
3. **Fast-forward.** Open, docs only. `server_command "tick sprint 600"` already speeds up timers and waves.
   Put it in the server instructions and the project `CLAUDE.md` so AIs use it when testing.

## Priority 2: remove the build scripts

4. **Hollow box and walls.** Owner: usable-state thread (in progress). Every room was four fills.
5. **A batch build call.** Open. One call that takes a list of fills, sets and structures, applies them as one
   edit and returns one undo id. Both scripts existed mainly to send 50 to 400 small calls; a batch removes
   the script, the bridge traffic and the per-call tool overhead.
6. **More shapes.** Open, after 4 and 5. Line, cylinder or disc, and "repeat this structure N times with an
   offset" cover the jump course, towers, pillars and lamp rows both games needed.
7. **Render gaps.** Partly fixed (#20 adds colours for plates, levers and lightning rods). Entities and text
   displays are not drawn, so an NPC or hologram cannot be checked in `world_render`. Draw them as markers.

## Priority 3: starting and pointing MSC

8. **Stale installs.** Owner: usable-state thread (in progress). The build stamp (#18) made it visible; a
   warning when the install is older than the newest release would make it unmissable.
9. **Switch workspace without restarting.** Open. The workspace is the folder Claude Code started in. Both
   threads worked around it with a private stdio-to-HTTP bridge. A `msc_workspace` tool (or a `workspace`
   argument on `project_scaffold` that the core then adopts) removes the workaround.
10. **A stale tool list hides new options.** Follows from 8. The second thread missed `project_scaffold`'s
    `package` option because the session's old install did not list it.

## Priority 4: fewer steps in every game

11. **A game template.** Open. Both plugins rewrote the same base: join kit, adventure mode, keep inventory,
    respawn point, no natural spawns, fixed time and weather, admin commands to jump to any stage, progress
    saved to config. A `paper-game` template with those and a stage/zone skeleton saves about 150 lines and
    gives every game the test hooks the bot will need.
12. **Resource pack after a restart.** Fixed in #20 for restarts inside a session. Still open: on
    `server_detach`, also write the pack URL and hash to `server.properties`, so a restart outside MSC keeps it.
13. **`server_command` output.** Open, small. Commands that reply with a broadcast return nothing, and
    non-ASCII replies broke a Windows console client. Capture broadcasts made during the command.
14. **EULA step.** Open, docs only. Both threads lost minutes waiting for the owner's answer to arrive in a
    form their permission checks accepted. `server_setup` should print the exact sentence to ask the owner, and
    the docs should say Minestom has no EULA step, for cases where the owner is away.

## Already fixed by the playtests

Wrong `item_model` id in the wiki (#16), `texture_recolor` wording (#16), build stamp in `msc_status` (#16, #18),
block name suggestions (#17), `server_detach`, ground level that ignores builds, command syntax errors and the
scaffold `package` option (#19), pack sent again after a restart and render colours (#20).

## What worked and should not change

`dev_cycle` as one call, condensed logs ("no errors or warnings"), the ground-level line, text structures for
irregular builds such as the maze, `world_render` for a quick look, `texture_create` from text, and
`server_detach`.
