# ADR 0081: Signs, heads, entities, biomes, features and light in build_batch

Status: accepted, 2026-11-23. Decided by Claude, filling Jeremiah's gap list ("no tools to place mobs, villagers, armor
stands or item frames", "biomes can't be painted after a world is made", "no easy signs, banners, books or player
heads", "no automatic lighting") and the plugin survey's `feature`, `biome` and `entity` steps.

## Decision

**Block entity helpers** (`sign`, `banner`, `head`, `book`, `container`) write the SNBT a block already could carry
(ADR 0042) from plain options: sign lines (waxed by default), banner patterns, a head's player, texture value or
textures.minecraft.net skin (how decorative object heads are made, no plugin needed), a written book on a lectern,
container items or a loot table. They are `set` with data, so undo, dry runs and the placement rule are unchanged.

**Commands after the blocks.** A canvas now also queues server commands; `build_batch` runs them once its blocks are
written, inside the same edit, and reports how many ran and the first failures:

- `entity`, `frame` and `stand` summon at a block's centre, every entity tagged `msc_build` plus the build's own tag.
  The edit id remembers `kill @e[tag=<that tag>]`, so `build_undo` removes them with the blocks. `still=true` adds no AI,
  silent, persistent, invulnerable.
- `biome` paints with `/fillbiome`, cut into 32x32x32 pieces (the command's volume limit).
- `feature` runs `/place feature` with any configured feature id, on the ground when spread.

Commands run in the step's world through `execute in <dimension>`. Biome and feature changes are not undone by
`build_undo`; the result says so, and the world backup taken before builds (0.7.0) covers them. They need vanilla's
commands, so not Minestom. A build of only commands is an edit with no blocks.

**Light.** `light` reads the light sources around a box (by block, lit state and level), walks the spots of a kind
(floor, ground, wall, ceiling...) in a spread-out order and puts a light wherever the light would fall below `level=`,
counting light as fading one per block of walking distance. Walls are not traced, which only errs on lighting a little
less in a maze; the review and renders show it.

Option values may be quoted to keep spaces: `text="Welcome to|Ashford"`.

## Consequences

- An agent no longer writes sign or head SNBT by hand, and populated places (villagers, armor stands with gear, maps in
  frames) come undoably from the same batch as the buildings.
- MineSkin uploads for custom head textures, and a FAWE bridge, stay the survey's later items.
