# ADR 0075: Land tools and generated worlds

Status: accepted, 2026-11-22. Decided by Claude. Jeremiah asked for an analysis of Antigravity's third build of "The
Shattered Meridian" (ADR 0068, 0071) and for MSC changes so future agents do better, deciding without decision cards.

## What the agent did

The run (`D:\Minecraft Servers\The Shattered Meridian`, 266 MSC calls: 60 `build_batch`, 45 `world_render`, 45
`world_summary`, 7 failures) followed the build guide's loop: it rendered, reviewed and fixed its buildings. The land
is where it went wrong. Everything stands on the superflat dev floor at y -61: the regions (Havenfall, Ember Wastes,
Frontier Valley) are blobs painted on it, Ember Wastes a flat splat of two terracottas mixed block by block; "hills"
are concentric 1-block rings; paths are straight 1-block lines of dirt and gravel; sand and grass are ovals; a harbour
is 1 block of water on grass; trees and leaves float, a ship and a tower stand off their bases; buildings sit on the
floor with no footing. The design asked for a 12,000 x 12,000 world of six regions; the whole build fits in 114 x 152.

The causes are in MSC, not only in the model:

1. **No real land to build on.** Every dev world is superflat, and nothing made another. The only way to "shape the
   land first", as the build guide said, was by hand, with tools for buildings.
2. **The tools for land were shapes.** `dome` and `sphere` make domes, `line` makes straight lines, and `bands`, whose
   help said "rings to each radius: land, beach, water", is exactly the concentric-oval island the agent made, again
   and again. A block mix picks block by block, so a ground mix is static, not patches.
3. **Nothing put things on the ground.** A tree stamped by hand at a guessed y floats or sinks; there was no "find the
   ground here" in any step.
4. **The review only judged buildings.** It left out the ground layer, so flat land, straight paths and floating
   blocks never showed in a number, and the agent's own reading of the renders passed them.

## Decision

1. **`world_create` makes generated worlds** beside the flat dev world: a data pack (`<world>/datapacks/msc-worlds`)
   with one dimension per world, `msc:<name>`, using the game's own generators: `normal`, `amplified`,
   `large_biomes`, `caves`, `floating_islands`, `nether`, `end`, `flat` (with `layers=`) or `void`. `biomes=` sets one
   biome everywhere (`fixed`) or shares the land among several, each at its own point of the climate so the regions
   take the game's organic shapes. It loads on the next start on every vanilla-based platform (Paper and forks,
   Fabric, Quilt, NeoForge, Forge); Minestom is refused with what to do instead, and proxies are gated like the other
   world tools. Checked on Paper 26.3: all nine terrains load, into a new world and into an existing one, as worlds
   named `msc:<name>`, which `world=` already resolves; a world of four biomes put the second-nearest 520 blocks away.
   The pack declares `min_format` 82 to `max_format` 1000 (26.3's data format is 121).
2. **Three land steps in `build_batch`**, all deterministic by seed like block mixes:
   - `terrain`: noise land in a box, styles `hills` (fractal noise), `ridges` (ridged noise), `mesa` (terraces) and
     `dunes`; `top=`, `under=` (`depth=`), `cliff=` on columns 3+ blocks steeper than a neighbour, `bed=` under water
     and on its shore; the outer `edge=` blocks ease down to `ground=` along a wavy outline, so it meets the land
     around it, and `edge=0` fills the box for tiling one seed over a large area; below `ground=` it digs, and
     `water=` (at most `ground=`) fills the valleys.
   - `path`: a Catmull-Rom curve through the points, pushed up to `wander=` aside by noise, `w=` wide with a ragged
     rim, laid on each column's top ground block (looking through plants, leaves and trunks) with plants cleared;
     `depth=` digs a bowl filled with the block over `bed=`, a stream.
   - `scatter`: one thing per `spacing=` cell at a random spot, kept by `chance=`, on the ground (only on `on=`
     blocks): a block from a mix, or one of several structure files, centred, turned a random quarter, `sink=` deep.
3. **`clump=N` on any step with a block**: the mix in patches about N across with wavy edges (a warped cell grid, one
   roll per cell, so the weights hold) instead of block by block.
4. **`review=true` judges land too** (`LandReview`): on a region of 1024+ columns, mostly ground, the share of ground at
   one level (over 80% is flat land) and of edges between different ground blocks that run straight for 6+ blocks
   (over 25%); on any region, blocks not joined face to face to the region's bottom or sides (water joins), with the
   three largest clumps' positions. Soil (grass, dirt, sand, gravel...) no longer counts in a build's numbers, and a
   region that is 60%+ land by blocks is judged as land only, so a hillside is never told to pitch its roof.
5. **Guidance**: the build guide's "LAND FIRST" section (a test floor is not a landscape; generated worlds for open
   worlds and regions; terrain, path, scatter, clump; foundations reach the ground; review land too), the MCP
   instructions, and `bands`' help text, now neutral ("flat rings out to each radius").

## Consequences

- Measured on two 128 x 128 scenes drawn with the steps: the Meridian way (flat floor, bands, straight lines, a
  terracotta splat) gets "flat land 90%" and "straight ground edges 35%"; the same area with `terrain`, a winding
  `path` and scattered trees gets 35% and 3%, and passes.
- The tool list every conversation pays for grows by about 1,150 characters (16,600 to 17,750; the budget in
  `McpBridgeTest` is now 17,800): one tool and three steps, with help text cut to one line each. Land had no tool.
- Nothing here sets a style: the terrains are the game's own, the steps take any block, and the guide names tools, not
  looks (ADR 0073, 0074).
- A world made with `world_create` uses the main world's seed; per-world seeds and custom noise settings (sea level,
  terrain shape) are not offered: the game's settings are long JSON, and its presets plus `terrain` cover the cases
  seen so far.
- A bad biome or block name makes the server report a data pack error on start; the tool says to fix it or
  `remove=true`.
- Not done: a speckled-ground measure. Tried (share of mixed columns with 3+ different neighbours), it scored the
  painted scene 16% and the natural one 15%, so it would only add noise; `clump=` and the guide carry that point.
- The thresholds (80%, 25%, 6 blocks) are rules of thumb like ADR 0068's; the next Antigravity run is the measure.
