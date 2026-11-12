# ADR 0017: Switch workspace at runtime, keep the pack offered past the session, small playtest fixes

Status: accepted, 2026-11-12. Decided by Claude under Jeremiah's hand-over of build decisions. Follows the playtest findings (`playtest-findings.md`, items 12, 14, 17, 18, 19 and 20).

## Context

Both playtests started Claude Code in the wrong folder and wrote a private stdio-to-HTTP bridge to point MSC elsewhere, because the workspace was fixed when the MCP server started. After `server_detach` (ADR 0014) the pack host stayed up, but the pack URL used a random port and lived only in the agent's memory: a server restarted outside MSC no longer sent it. Smaller gaps: no handheld item model from `texture_create`, `/say`-style broadcasts lost by `server_command`, entities invisible in `world_render`, and minutes lost waiting for an EULA answer the AI's permission checks would accept.

## Decision

1. **`msc_workspace path=<dir>` swaps the workspace in place.** Everything bound to one workspace (config, agent hub, server manager, resource pack, the tools on top) is one unit inside `Core`. The MCP tool list is fixed at startup, so `Core` exposes forwarding tools that call the current unit; a switch takes a write lock, closes the old unit exactly as a session end would (a detached server keeps running, any other stops) and opens the new one. The old unit is closed first so a fixed agent or pack port is free again; if the new one cannot open, the old one is reopened. The server folder and tool log follow the workspace only when they were the defaults inside it. The tool log object stays the same, so `msc_stats` covers the whole session. A missing folder is an error unless `create=true`, so a typo does not make a stray folder. A `workspace` argument on `project_scaffold` was rejected: one tool covers existing projects too.
2. **The pack port is stable per workspace.** `MSC_PACK_PORT` sets it; otherwise the port used last is kept in `.msc/pack-port` and reused, falling back to a free port when it is taken. The pack URL is then the same from session to session.
3. **`server_detach` writes the pack into `server.properties`:** `resource-pack`, `resource-pack-sha1`, `require-resource-pack`, and `resource-pack-id` set to the same UUID the agent uses, so a client never stacks the pack twice. Other lines are kept; values are escaped as vanilla writes them. Minestom has no `server.properties`, so nothing is written there.
4. **The next start keeps that promise.** When `server.properties` points at an MSC pack URL on the pack host's address, the start (after stopping the leftover pack host) rebuilds the pack, serves it on the same port and updates the hash before the server reads the file. If the pack can no longer be built, the lines are cleared so players do not see a failed download. URLs that are not MSC's are never touched. `pack_build` keeps the lines pointing at the newest build.
5. **Small items.** `texture_create`/`texture_generate`/`texture_recolor` take `model=handheld` (parent `item/handheld`). `server_command` appends what the console printed while the command ran (waits until the log is quiet for 60 ms, at most 400 ms), minus lines already returned; this is platform-neutral and needs no protocol change, but only works for a server this core started. `world_render` reads entities with the blocks and draws them as numbered magenta dots one block above their feet, listed in the caption (`entities=false` turns this off); the Paper agent reports a text display's text as its name. `server_setup` and `project_scaffold` print the exact EULA question to put to the user, and that a Minestom project needs no EULA.

## Consequences

- One more tool and one more argument: the tool-list guard goes up to 15,000 characters.
- Markers are drawn on top and are not hidden by walls; the caption lists which fall outside the image.
- `server_command` is up to about 0.4 s slower when the console keeps printing; usually about 60 ms.
- Players on another machine still cannot fetch a pack served on `127.0.0.1`; `MSC_PACK_HOST` remains the fix.
- Not done: non-ASCII replies breaking a Windows console client were in the playtest's own bridge, not in MSC, which writes UTF-8 JSON.
