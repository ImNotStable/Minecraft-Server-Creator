BUILD GUIDE: what makes a build look hand-made, and the loop that gets it there.

WHERE TO BUILD
- Build in the world with build_batch (one building per call), not with plugin code that places blocks: code builds are never seen, reviewed or undone. Plugin code only swaps prepared states at runtime (save each state with structure_save, paste it from code or with build_batch structure file=).
- Land first (next section), then builds fitted into it.

LAND FIRST
- The flat dev world is a test floor, not a landscape. Never paint a region onto it: no blobs of one block on flat ground, no 1-block rings or outlines as "hills".
- An open world, a region or a dimension: make it a generated world with world_create (normal, amplified, large_biomes, floating_islands, caves, nether, end; one biome everywhere or several sharing the land; flat or void for a blank slate), restart, and build into its real terrain with world=msc:<name>. Several regions can be several worlds, or several biomes in one.
- Land by hand: build_batch terrain (styles hills, ridges, mesa, dunes; water= for lakes, bed= for shores, cliff= for rock on steep sides). The box's height is the land's: hills rise 10-40 blocks, mountains more. Big areas: tile boxes with edge=0 and one seed.
- Ways: path through 3+ points with wander= (curves, ragged sides, on the ground); a stream is path depth=2 with water and bed=. Straight lines are for built things (walls, rails, bridges), not trails.
- Ground cover: give ground mixes clump= (patches with wavy edges); a plain mix reads as static.
- Nature: structure_save a few trees, rocks and bushes and scatter them (file=a,b,c spacing= chance=) so each stands on the ground; scatter flowers and grass the same way. Nothing floats: leaves join a trunk, rails and props rest on something.
- Fit builds to the land: foundations reach down to the ground on slopes, a plaza is cut into the hill with retaining walls, and the ground at a build is worn (paths to doors, coarse dirt, gravel), not a clean edge.
- Review land as well as builds: world_summary review=true on a land region reports ground at one level, straight ground edges and floating blocks.

THE LOOP (every build)
1. Plan in 3 lines: purpose and story (who lived here, what happened), silhouette (3+ masses of different heights), palette.
2. Block out the masses: walls 1-2 thick, real floor heights (4-5 per storey), doors 2-3 tall, roofs (roof step).
3. Detail pass (below). This is most of the work; a block-out is not a build. Straight walls and rows are fine:
   good builds have plenty. What makes them look hand-made is the single blocks layered on that structure.
4. world_render the build from 2 views (iso and one side) and run world_summary review=true on the same region.
5. Name the 3 weakest things concretely ("north wall is a flat 12x8 plane of one block"), fix them in one build_batch, re-render. Repeat until review passes and the render reads well. Never call a build done on its first render, and never praise a render the review flags.

PALETTE
- 60/30/10: a main material with 2-3 variants mixed in (stone_bricks:70,cracked_stone_bricks:15,mossy_stone_bricks:15), a secondary (wood, a second stone), one accent (copper, gold, a coloured block) used sparingly.
- Mix only near tones (stone_bricks with its cracked and mossy variants, deepslate_bricks with deepslate_tiles). A random mix of clashing colours (deepslate with prismarine, purpur with gold) reads as static; put contrast in lines instead: trims, bands, frames.
- Gradient: darker, heavier blocks at the base, lighter near the top. Frame with a contrasting trim (logs or a polished stone).
- Themes to start from (swap freely):
  medieval: stone_bricks, cobblestone, spruce_log, spruce_planks, dark_oak_stairs roof, lanterns
  industrial: deepslate_bricks, polished_blackstone, waxed copper (oxidised variants for age), iron_bars, chains, smooth_stone
  frost: packed_ice, blue_ice, snow_block, calcite, diorite, light_blue_stained_glass, spruce
  ember: blackstone, basalt, magma_block, red_nether_bricks, netherrack, crimson_planks, shroomlight
  drowned: prismarine, dark_prismarine, sea_lantern, mud_bricks, kelp, seagrass, coral
  verdant/elven: moss_block, mossy_cobblestone, birch and cherry wood, azalea leaves, vines, flowering_azalea
  astral: end_stone_bricks, purpur, amethyst_block, quartz, crying_obsidian, end_rod, glass panes

DETAIL PASS (use most of these on every build; the steps work on what is already built, so block out first)
- Decide every block's placement as a builder would: which way a stair, trapdoor, chest or wall torch faces and its half,
  a log's axis, a slab's type, a door's hinge, open or closed, a sign's or banner's rotation, a lantern hanging or
  standing, and in or beside water whether it is waterlogged. build_batch refuses a block that leaves these to the game's
  default (every stair facing north, every log upright), naming what to add. Write them in each block or legend entry:
  s=oak_stairs[facing=east,half=bottom]. What follows from the neighbours is added for you: fences, panes, bars and walls
  connect, stair corners turn, doors, beds and tall plants get their other half, vines cling. roof, trim and decorate turn
  their own blocks to where each lands.
- Roofs: roof (gable, hip, shed) over the wall box: stairs face up the slope with corners joined, overhang=1-2, slabs for a
  low pitch, gable= closes the ends, a taller box caps it flat. Break long ridges: a second, lower roof, a dormer, a chimney.
- Depth: no wall is one flat plane. trim at=base (plinth), at=band level=Y (belt course), at=top (cornice) and at=corners
  (pillars or quoins of logs or polished stone); windows set 1 in, pillars or a timber frame 1 out.
- Windows and doors: trim at=sills, lintels, shutters (trapdoors), frames; panes or bars in the hole.
- Gradient: a>b>c on the walls, darker and heavier at the base (cobblestone>stone_bricks>andesite), seams ragged, no
  shade skipped; grade=x or z for a run along a wall.
- Props: decorate on=edge (barrels, shelves, crates along walls), on=corner (beds, chests), on=ceiling with tip=
  (chain then lantern), on=floor (carpets in patches with clump=), on=top (props on ledges); furnished interiors, tables
  from slabs and fences, beds, shelves; lanterns on chains, banners, signs, flower pots, hay, carts, market stalls.
- Nature: decorate on=wall (vines, glow lichen) from the wall tops down, on=ground (grass, flowers, leaf bushes) around
  the base with clump=, on=ceiling (hanging roots, weeping vines) under bridges and arches; trees and water nearby.
- Age and story: weather (moss low and on ledges, cracks on edges, verdigris on copper, swap= for other blocks), break=
  for a ruin's broken tops with chip= slabs and stairs; rubble at the base, a collapsed roof corner.
- Organic shapes: sphere/dome rough=N for boulders, leaf canopies and hedges, never a perfect ball.
- Light: lanterns, torches or glowing blocks at doors, paths and windows; no dark interiors.

DETAIL STEPS (build_batch; each works on what the world and earlier steps already hold)
- roof X,Y,Z X,Y,Z B: the box is the walls' footprint; rows start at its bottom y (the eave) and reach overhang= past the
  walls. style=gable (ridge along axis=, the longer side by default), hip, or shed (rises toward high=north|east|...).
  B stairs: pitch 1, or pitch=2 steep; B slabs: a low pitch; B full blocks: steps. gable=B closes the ends, ridge=B tops
  an odd span (default the stair's own slab); a box taller than one layer caps the roof flat at its top y.
- trim X,Y,Z X,Y,Z B at=...: only outside faces (facing away from the box's middle). top (cornice on wall tops), base
  (plinth), band level=Y (belt course), out=N deep; corners (outer corner columns, replaced); at holes through a thin
  wall: sills (under), lintels (over), shutters (trapdoors beside, opened flat on the wall), frames (around, replaced).
- decorate X,Y,Z X,Y,Z B on=...: top (any upward face), ground (open to the sky), floor (indoors), edge and corner
  (indoors, beside walls), wall (down wall faces from their tops), ceiling (hanging). chance=25 percent of the spots,
  in patches with clump=N, or one per spacing=N cell (lights); length=A-B up from floors, down from walls and ceilings;
  tip=B ends each (chain then lantern).
- weather X,Y,Z X,Y,Z [amount=30]: moss low and on ledges, cracks on edges, verdigris on copper, in patches;
  swap=from>to,... for other blocks; break=N knocks exposed top edges off, leaving chip=B or air.

SCALE AND COMPOSITION
- A landmark is seen from far away: give it a strong, asymmetric silhouette (a tall tower beside a low hall), not a box with a spike.
- Towers taper or step in, have a base, a shaft and a cap; no 1-block-thick towers.
- Lead the eye: a path or road to each entrance, a plaza with a focal point, varied heights along a street.
- Repeat a building with variations (rotate=, a different roof or colour), never copy it unchanged.

EFFICIENCY
- One build_batch per building with block mixes, repeat= for rows of windows and copy/structure for repeated parts; dryRun=true checks names without placing.
- structure_save good parts (a window, a lamp post, a roof end) and stamp them; world_summary diff= shows what a change did.
