# ADR 0114: Atmosphere guide and review checks

Status: accepted, 2026-11-25. Decided by Claude; Jeremiah agreed in the 2026-11-25 brainstorm that MSC should help
places feel alive (sound, ambient particles, weather and lighting mood) and asked for autonomous decisions.

## Context

Players judge a server first by what they see when they arrive. A build can pass every looks check and still feel
dead: silent, nothing moving, one lantern model everywhere, a noon sky over a place meant for dusk. MSC had nothing on
this beyond "no dark interiors" and the UX guide's feedback sounds. Agent usage must stay low, and the from-scratch
rule (ADR 0106) forbids ambience presets, sound lists or particle recipes.

## Decision

- A new guide, `msc_status guide=atmosphere`, read only on demand: a mood per region, light by use and colour (pools of
  light, a brighter focal point), blocks that sound or move by themselves, an ambient loop the agent writes (sounds
  played at a spot, random gaps, low volume, varied pitch; few particles, only near players), sky, weather and time
  (world, per player, and biome effects, including the agent's own datapack biome after reading the game version's
  own generated biome files). It names kinds of things, never a set to copy. The build guide points to it in one line.
- The build review gains one line, `atmosphere: N sounding and M moving blocks, K light kinds`, from one extra pass
  over the grid's block counts (no neighbour checks), and two fixes:
  - **still**: a build of 300+ blocks (the "no plants" bar) where no block sounds or moves by itself: lit fire and
    campfires, lava, flowing water (water with a level), lit furnaces, torches, lit candles, end rods, spore blossoms,
    and so on. Unlit and still versions do not count.
  - **one light**: 8+ lights that are all one kind (a wall torch is a torch).
- Both fail the review like every other fix, so they gate new builds (ADR 0108). Each is cheap to fix and says what
  to change, in a sentence.

## Not done

- Sky, weather, time, biome and plugin loops are not visible in blocks, so they are not checked; the guide asks for a
  one-line atmosphere note per region in the report instead. Entities (animals, villagers) are not in the block grid.
- No WorldGuard or other plugin dependency: weather and time per region are a few lines of the agent's own code.
- No light-colour or light-spacing check yet; add one if runs show warm/cold mismatches or uniform grids of light.
