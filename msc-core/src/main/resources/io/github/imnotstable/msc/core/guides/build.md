BUILD GUIDE: what makes a build look hand-made, and the loop that gets it there.

WHERE TO BUILD
- Build in the world with build_batch (one building per call), not with plugin code that places blocks: code builds are never seen, reviewed or undone. Plugin code only swaps prepared states at runtime (save each state with structure_save, paste it from code or with build_batch structure file=).
- Shape the land first. Never put a city on the flat dev floor: raise hills with dome/sphere and mixes, cut rivers and paths, then fit builds into the terrain (foundations step down slopes).

THE LOOP (every build)
1. Plan in 3 lines: purpose and story (who lived here, what happened), silhouette (3+ masses of different heights), palette.
2. Block out the masses: walls 1-2 thick, real floor heights (4-5 per storey), doors 2-3 tall, roofs.
3. Detail pass (below). This is most of the work; a block-out is not a build.
4. world_render the build from 2 views (iso and one side) and run world_summary review=true on the same region.
5. Name the 3 weakest things concretely ("north wall is a flat 12x8 plane of one block"), fix them in one build_batch, re-render. Repeat until review passes and the render reads well. Never call a build done on its first render, and never praise a render the review flags.

PALETTE
- 60/30/10: a main material with 2-3 variants mixed in (stone_bricks:70,cracked_stone_bricks:15,mossy_stone_bricks:15), a secondary (wood, a second stone), one accent (copper, gold, a coloured block) used sparingly.
- Gradient: darker, heavier blocks at the base, lighter near the top. Frame with a contrasting trim (logs or a polished stone).
- Themes to start from (swap freely):
  medieval: stone_bricks, cobblestone, spruce_log, spruce_planks, dark_oak_stairs roof, lanterns
  industrial: deepslate_bricks, polished_blackstone, waxed copper (oxidised variants for age), iron_bars, chains, smooth_stone
  frost: packed_ice, blue_ice, snow_block, calcite, diorite, light_blue_stained_glass, spruce
  ember: blackstone, basalt, magma_block, red_nether_bricks, netherrack, crimson_planks, shroomlight
  drowned: prismarine, dark_prismarine, sea_lantern, mud_bricks, kelp, seagrass, coral
  verdant/elven: moss_block, mossy_cobblestone, birch and cherry wood, azalea leaves, vines, flowering_azalea
  astral: end_stone_bricks, purpur, amethyst_block, quartz, crying_obsidian, end_rod, glass panes

DETAIL PASS (use most of these on every build)
- Depth: no wall is one flat plane. Pillars or a timber frame 1 block out, windows set 1 block in, a plinth at the base, a stair/slab cornice at the top.
- Shaped blocks: stairs and slabs for roofs, sills, eaves and trim; walls and fences for railings and buttresses; trapdoors as shutters and panels; panes and bars in windows.
- Roofs: pitched with stairs, overhang 1, gable ends with a different material, dormers or a chimney to break long ridges.
- Props: lanterns on chains, barrels, crates, banners, signs, flower pots, hay, carts, market stalls; furnished interiors (beds, tables from slabs and fences, shelves of bookshelves).
- Nature: leaves and vines on walls, moss and coarse_dirt mixed into paths, trees and bushes around builds, water features.
- Age and story: cracked and mossy variants, a broken wall edge (seed= mixes with air), rubble at the base, a collapsed roof corner.
- Light: lanterns, torches or glowing blocks at doors, paths and windows; no dark interiors.

SCALE AND COMPOSITION
- A landmark is seen from far away: give it a strong, asymmetric silhouette (a tall tower beside a low hall), not a box with a spike.
- Towers taper or step in, have a base, a shaft and a cap; no 1-block-thick towers.
- Lead the eye: a path or road to each entrance, a plaza with a focal point, varied heights along a street.
- Repeat a building with variations (rotate=, a different roof or colour), never copy it unchanged.

EFFICIENCY
- One build_batch per building with block mixes, repeat= for rows of windows and copy/structure for repeated parts; dryRun=true checks names without placing.
- structure_save good parts (a window, a lamp post, a roof end) and stamp them; world_summary diff= shows what a change did.
