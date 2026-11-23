# ADR 0087: The test player clicks menus, NPCs and chat buttons, and checks the served pack

Status: accepted, 2026-11-23. Decided by Claude, item 1 of Jeremiah's gap list: "nothing joins a generated server as a
player, so minigames, NPCs, commands, the resource pack and builds have never been verified in game".

## Context

Checked against main first: the premise was half wrong. `player_test` (ADR 0016, msc-bot on MCProtocolLib) already
joins the dev server as a real client, accepts the pack, runs commands, walks routes, jumps, fights, right-clicks the
nearest entity and reads chat, titles, the sidebar, boss bars and menus. What it could not do was what a generated game
built with msc-stdlib 2.5 (ADR 0086) asks of a player:

- click a slot of a menu (ADR 0016: "Clicking slots inside a menu is not supported yet"), so no GUI was ever tested;
- tell NPCs apart: entities were reported by type only, so "the shop NPC" was any villager;
- click a chat button: `Npcs` dialogue and many plugins offer choices as `run_command` chat parts;
- check the pack beyond "it downloads and the hash matches": a pack whose font texture or `.ogg` is missing still
  loads, and the player sees boxes and hears nothing.

## Decision

Extend `player_test`; no new tool, no new library (MCProtocolLib stays; Mineflayer would add Node for nothing).

1. `click <slot>` left-clicks a slot of the open menu, numbered as `inventory` reports them. It sends no carried item
   or changed slots: a menu plugin cancels the click anyway, and a real move is resynced by the server.
2. `click <text>` runs the command of the newest chat part with that text and a `run_command` click. Chat events list
   the clickable parts (`(click: [Yes] | [No])`).
3. Entities carry their custom name (entity data index 2) or, for player-shaped NPCs, their tab-list name.
   `entities` shows it, and `attack`/`interact` take a type or a part of a name (`interact old miner`).
4. The bot keeps the pack it downloaded (`PackContents`): `PackLint` runs on the served zip and the pack line says
   `lint clean` or the first errors. `hud` lists the sounds heard, the custom sounds the pack does not add, and the
   Private Use Area glyphs seen on screen that no pack font draws. msc-bot now depends on msc-textures for `PackLint`.

Verified on a real vanilla 26.3 server: the pack line named both missing files, `interact miner` picked the named
villager, `click yes` ran the button's command, `click 0` took the diamond out of a chest, and `hud` flagged a sound
missing from the pack.

## Consequences

- A game's GUIs, NPC dialogues and resource pack can be checked in play by the AI, not only by Jeremiah.
- Shortcut: a ttf, unihex or reference font provider counts as drawing every glyph; glyphs in a non-default font are
  checked against all the pack's fonts together.
- Still not covered: rendering (models, shaders, how text looks), which needs a real client and a GPU.
