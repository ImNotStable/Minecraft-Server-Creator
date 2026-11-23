# ADR 0083: Furnished rooms, pictures as blocks, more structure files

Status: accepted, 2026-11-23. Decided by Claude, finishing the build half of Jeremiah's gap list: "no interior
furnishing by room type", "no image-to-build or map art", "only .schem files can be imported". The plugin survey ranks
.litematic and .nbt import as later work; it is small, so it lands here.

## Decision

- **`furnish`** reads a built room (floor under a ceiling, the wall beside each spot, corners, doors and wall openings)
  and furnishes it by its use, 14 room types in one wood and one colour: corner and wall props facing out of their
  walls, beds with their head on a wall, a centre piece (tables with chairs, a long dining table, pews and an altar, a
  throne on a carpet runner, a counter, an enchanting table), a rug around it and a lantern on a chain from the ceiling.
  Spots beside doors and openings, also diagonally, stay clear. It only fills air. It is a starting point the agent then
  details: the build guide says so, since a furnisher's sameness is what ADR 0073/0074 warn about in templates.
- **`image`** scales a PNG/JPEG to a width, matches each pixel to the nearest block colour of a palette (wool, concrete,
  terracotta, stained glass, or a broad mixed one) by the "redmean" distance, with Floyd-Steinberg dithering by default,
  and stamps it on a wall (plane x or z) or on the ground (map art). Colours come from the renderer's block colour table,
  so a picture looks in `world_render` as it will in game. Staircased map art (shading by height) is not done.
- **Structure files**: `StructureFile` now tells the game's structure `.nbt` (structure blocks, `/place template`) and
  Litematica's `.litematic` from Sponge schematics by content, everywhere a structure file is read. Litematic regions
  (negative sizes included) unpack states packed across longs; structure voids and cells no region covers leave the
  world alone; block entity data is kept. MCEdit `.schematic` files (numeric ids) get a one-line answer to re-save them.

## Consequences

- Interiors stop being empty boxes in a first pass; images, logos and map art no longer need a script.
- Builds downloaded from Litematica or vanilla structure collections load directly.
