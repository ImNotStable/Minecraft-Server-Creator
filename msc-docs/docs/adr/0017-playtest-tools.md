# ADR 0017: Tools for an AI to check the game it built

Status: accepted, 2026-11-12. Decided by Claude under Jeremiah's hand-over of build decisions.

## Context

Two AI playtests (Lumen Siege and Gearfall, see [playtest findings](../playtest-findings.md)) built working games but could barely check them. Items 2 to 5 of the findings: nothing lists entities, so wave spawns, NPCs and holograms could not be checked; game state was polled through 81 `server_command` calls; the dev server is creative and peaceful, so a combat game silently does nothing; and chunks without a player do not tick. The test player bot is separate work (ADR 0016).

## Decision

1. **`world_entities` uses a new protocol op, `world.entities`, not `world.read`.** `world.read` also reads every block, is limited to 1,048,576 blocks and returns only type, position and name. The new op reads no blocks, so one call covers a whole arena (the core caps the width at 2,048 x 2,048, because the entity lookup walks every chunk section in the box). It returns type, UUID, position, name, scoreboard tags, health and max health. The name is the player name, the custom name, or (on Paper and Minestom) a text display's text, so holograms read as text; Fabric keeps the text only in client-side render state, so its text displays show no name. Sorting and the limit live in `Ops.EntitiesResult.of`, so the three agents answer the same way. `world.read` and `EntitySummary` are unchanged, so old agents keep working; the core says "the paper agent does not support world.entities" when talking to one built before this change. Minestom has no scoreboard tags, so its tags are always empty.
2. **The core formats one line per entity**, with totals per type first, coordinates to one decimal, and UUIDs only on request (`ids=true`), since a UUID costs about 10 tokens and is needed only to target a command.
3. **`server_wait` reads the core's log buffer, not an agent event.** Paper logs chat to the console, so log lines cover both, and the buffer already exists for `server_logs`. No protocol change. It looks at lines since the last `server_command`, `server_wait` match or start. That way the common pattern, a command and then a wait for its effect, cannot miss a line printed between the two calls, and two waits for the same pattern see two different lines. A timeout is a normal result with the last 5 lines, not an error, because "it did not happen" is an answer. It also returns when the server stops. It works only for a server this core started, since an attached server's log is not captured.
4. **`server_setup mode=game|build` edits `server.properties` in place**: `game` is survival and normal difficulty, `build` is creative and peaceful, and both set `force-gamemode=true` so players who joined before switch too. Without `mode`, a new server gets build defaults and an existing one is left alone. Minestom ignores it (its code sets the mode). The start report gets a `mode:` line read from `server.properties` that names the other mode, so an AI testing a combat game sees it is in peaceful.
5. **Chunk tickets and `tick sprint` are documented, not wrapped.** Both are one existing command or API call. They go in the MCP server instructions, `tools.md` and the scaffolded project `CLAUDE.md` (Paper and Fabric).

## Consequences

- Two more tools (about 900 characters of schema after trimming their descriptions) and a longer server instructions string; the tool-list guard goes up to 15,500 characters.
- Every agent must implement `world.entities`. Agents built before this change still connect.
- `server_wait` cannot see what is not logged (titles, action bars, boss bars). The project `CLAUDE.md` asks the game to log its stage changes; the test player bot reports what a player sees.
