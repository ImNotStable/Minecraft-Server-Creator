# ADR 0023: Four texture tools instead of eight

Status: accepted, 2026-11-12. Decided by Claude under Jeremiah's hand-over of build decisions. Roadmap item R6.

## Context

After ADR 0019 the eight texture tools (`texture_create`, `texture_generate`, `texture_recolor`, `texture_animate`,
`model_create`, `texture_view`, `pack_check`, `pack_build`) were about 4,650 of the 16,660 characters of the tool
list, and no playtest had used them. ADR 0019 named the merge as the next candidate but waited for data.

A texture-heavy playtest on Minestom ("Emberwright", [findings](../playtest-findings.md#fourth-playtest-texture-round-emberwright-on-minestom-2026-11-12))
made two gems, an ingot, an ore block, a hammer, a food item, a recolored gem, a pulsing item and a scrolling
retexture of vanilla magma, then built the pack and had `player_test` download it. It showed:

- Draw, generate and recolor are the same act with a different source, and all three end in the same write and
  `model` choice. Animate always followed one of them on the same name.
- `pack_check` was not needed as its own call: `pack_build` already printed the summary line, but not the findings,
  so an error still took a second call.
- The game wanted a tool and a food item. Neither shape existed, so both were drawn by hand (16 rows each).
- `texture_view` of an animation printed every frame (2,255 characters for 8 frames of 16 x 16).

## Decision

1. **One `texture` tool** with three ways in, chosen by which arguments are given: draw (`palette`, `rows`),
   generate (`kind`, `color`) or recolor (`copy`, `color`). Exactly one way per call; two is an error that names
   both. `animate` (`scroll`, `scroll dx,dy`, `pulse`) applies to the result in the same call, or with only `name`
   to the texture already in the pack. `frames` and `frametime` get defaults (one full loop for scroll, 8 for pulse;
   2 ticks), so a plain `animate=pulse` is enough. Re-animating or copying an animated texture starts from frame 1.
2. **`pack` replaces `pack_build` and `pack_check`.** A build ends with the lint summary and the first 5 findings;
   `check=true` only lints.
3. **`model_create` and `texture_view` stay.** Models take a different shape of input (slots), and view is the only
   read. `texture_view` shows frame 1 of an animation and says how many frames there are.
4. **Three new shapes: `sword`, `pickaxe`, `food`.** Each has a second part, a handle or a bone, coloured from
   `accent` (default wood `#6b4a28` for tools, bone `#e8dcc0` for food). Swords and pickaxes default to the
   `handheld` model.
5. Generate leaves the pixel text out of its result when it also animates (the frames differ from what it would show).

Armour was not added: it needs equipment model assets as well as item textures, and the playtest did not ask for it.

## Consequences

- 26 tools, 15,695 characters of tool list (texture tools 4,650 to 3,685). The `McpBridgeTest` guard stays at
  16,000 so R4 and R5 have room.
- Old prompts that call `texture_create` or `pack_build` get "unknown tool"; the list the client fetches shows the
  new names. Tool results now say `next: pack`.
- Pack hashes are unchanged: the files written are the same.
