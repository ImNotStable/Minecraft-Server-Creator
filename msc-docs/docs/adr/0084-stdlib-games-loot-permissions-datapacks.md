# ADR 0084: Minigames, loot, permissions, data packs and dialogue in msc-stdlib

Status: accepted, 2026-11-23. Decided by Claude, filling the standard library half of Jeremiah's gap list: "no NPCs",
"no minigame framework for arenas, teams, queues and countdowns", "no permissions, loot tables or recipes", and his
standing preference for comprehensive libraries (memory: feedback-comprehensive-libraries).

## Context

Every minigame the agents built (Lumen Siege, Gearfall, Tidewright, Hollowroot, The Shattered Meridian) rewrote a
lobby, a countdown that should stop when players leave, team balancing, lives, a score to win, a time limit and a reset,
and most got one of them wrong. Permissions, loot and recipes were written per platform or skipped.

## Decision

Platform-neutral packages in `msc-stdlib` (2.4.0), so the same rules run on Paper, the mod loaders and Minestom; the
platform adapters (next PR) connect them to each server's own players, worlds and registries.

- `game`: `Arena` (bounds, lobby, spawns shared or per team, spectator point, min and max players), `Team`, `Game`
  (phases WAITING, STARTING, RUNNING, ENDING; a countdown that starts at the minimum and stops below it; even team
  fill; lives with respawn; kills and objectives as score; last team or player standing, score to win or time limit;
  late joiners spectate; a pause, then a reset that keeps players in the lobby and queues spectators) and `Matchmaker`
  (many games of one kind, fills the fullest open lobby first, makes games up to a limit). What players see and where
  they go is a `Game.Listener`, so no rule depends on a platform.
- `loot.LootTable<T>`: pools rolled between a minimum and a maximum (bonus rolls with luck), weighted entries with
  count ranges, a chance of nothing, conditions on pools and entries; `vanillaJson` writes the same table as a data
  pack loot table.
- `perm.Permissions`: groups with parents and weights, dotted nodes with `*` wildcards and `-` denials, user nodes for
  good or for a time, prefixes and suffixes, kept in any `Store` so servers can share them; a user's own nodes beat
  their groups', a heavier group beats a lighter one, a specific node beats a wildcard; `default` applies to everyone.
- `datapack.Datapack`: recipes (shaped, shapeless, smelting, blasting, smoking, campfire, stonecutting, smithing),
  loot tables, item and block tags, functions, advancements and raw files written as a data pack folder, the game's own
  way to add content on every vanilla-based server.
- `npc.Dialogue`: pages of lines with choices that open pages, run actions or close, hidden by conditions; it keeps
  where each player is, the platform shows it.

`scripts/stdlib-graph.sh` now unpacks every file and keeps the `.java` ones: Git Bash's unzip on Windows matched no
pattern, so the graphs could only be rebuilt on Linux.

## Consequences

- A minigame is a `Game` plus its listener, not a page of state handling; the same for loot and ranks.
- NPC entities, permission hooks, recipe loading, a Vault bridge, and commands and sidebars outside Paper are the
  adapters' part, in the following PR.
