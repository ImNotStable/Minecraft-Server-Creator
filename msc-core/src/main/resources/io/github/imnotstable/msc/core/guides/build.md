BUILD GUIDE: what makes a build look hand-made, and the loop that gets it there.

DONE MEANS (check before you report a build or a map finished)
- Every build and every land region has a world_summary review=true that ends "no number below the bar". A review
  that says "verdict: NOT DONE" is not done, however good the render looks; msc_status and dev_cycle list the reviews
  still failing until one of the same region passes.
- Land was shaped, not only generated: terrain, smooth or paint where the build meets it, path to each door, scatter
  or flora for ground cover, forest or tree around it. Generated land is the start, not the finish.
- Every build met the ground: trim at=base (a plinth), decorate on=ground (bushes, flowers) and worn ground at the
  doors; rooms furnished (decorate on=edge, corner, ceiling); weather for age.
- If the run must end with a review still failing, say which and why in the report; never call it done.

WHERE TO BUILD
- Build in the world with build_batch (one building per call), not with plugin code that places blocks: code builds are never seen, reviewed or undone. Plugin code only swaps prepared states at runtime (save each state with structure_save, paste it from code or with build_batch structure file=).
- Land first (next section), then builds fitted into it.

LAND FIRST
- The flat dev world is a test floor, not a landscape. Never paint a region onto it: no blobs of one block on flat ground, no 1-block rings or outlines as "hills".
- An open world, a region or a dimension: make it a generated world with world_create (normal, amplified, large_biomes, floating_islands, caves, nether, end; one biome everywhere or several sharing the land; flat or void for a blank slate), restart, and build into its real terrain with world=msc:<name>. Several regions can be several worlds, or several biomes in one.
- Land by hand: build_batch terrain (styles hills, ridges, mesa, dunes; water= for lakes, bed= for shores, cliff= for rock on steep sides). The box's height is the land's: hills rise 10-40 blocks, mountains more. Big areas: tile boxes with edge=0 and one seed.
- Ways: path through 3+ points with wander= (curves, ragged sides, on the ground); a stream is path depth=2 with water and bed=.
- Ground cover: give ground mixes clump= (patches with wavy edges); a plain mix reads as static.
- Nature: forest (kind=oak:3,birch:1 size=mixed spacing= chance=) grows real trees on the ground, each different; tree
  places one (19 kinds: oak, spruce, pine, jungle, acacia, dark_oak, cherry, mangrove, pale_oak, azalea, willow, dead,
  palm, bush, huge mushrooms, crimson and warped fungi; size small to huge). flora style=meadow|forest|taiga|swamp|
  desert|cherry|lush|nether... covers open ground with that biome's plants in patches, only on soil they grow on.
  Mix kinds and sizes, leave clearings, thicken toward water and slopes. Nothing floats: rails and props rest on
  something.
- Fit builds to the land: foundations reach down to the ground on slopes, a plaza is cut into the hill with retaining walls, and the ground at a build is worn (paths to doors, coarse dirt, gravel), not a clean edge.
- Underwater builds: find the sea floor with world_slice of a thin column (world_heightmap stops at the water's
  surface), stand the build on
  it and let the water stay around it: walls, columns and arches rise from the floor, and only the top (spires, a
  roof) may break the surface. Waterlogged=true for stairs, slabs, fences, lanterns and chains in the water; a dry
  room inside is a sealed hull filled with air in a later step. A build resting on the water surface is a floating
  barge, not a sunken one.
- Review land as well as builds: world_summary review=true on a land region reports ground at one level, speckled ground (a random mix of clashing blocks) and floating blocks, and it fails land reviewed on the flat dev world: give a map its own world_create worlds. Review the whole map once too, not only each plot.

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

- tree X,Y,Z: X,Y,Z is the lowest trunk block (one above the ground). kind= oak, birch, spruce, pine, jungle, acacia,
  dark_oak, cherry, mangrove, pale_oak, azalea, willow, dead, palm, bush, red_mushroom, brown_mushroom, crimson, warped;
  size= small, medium, large (big oaks branch, jungles and spruces go 2 wide), huge; h= the trunk height; log= and
  leaves= any block or mix (stripped_oak_log, azalea_leaves:3,flowering_azalea_leaves:1). seed= makes another tree.
- forest X,Y,Z X,Y,Z: one tree per spacing= cell (default 7) kept with chance= percent, on the top ground in the box
  (on= limits the ground blocks); kind= weights (oak:3,birch:1,bush:2), size=mixed by default.
- flora X,Y,Z X,Y,Z style=: meadow, plains, flowers, forest, taiga, swamp, jungle, savanna, desert, cherry, lush, snowy,
  mushroom, nether, crimson, warped; or your own plant mix. chance=35 percent of open ground, clump=5 patches, only on
  the style's soil (soil= to change; decorate takes soil= too).


SHAPING STEPS (build_batch; they reshape what the world and earlier steps hold, terrain and builds alike)
- smooth X,Y,Z X,Y,Z [radius=3 strength=2]: averages the ground's column heights, keeping each column's top block on
  top; the box's rim eases back to the land around it. The box must reach from below the lowest to above the highest
  ground. Use after terrain, or to soften a hand-built hill.
- erode X,Y,Z X,Y,Z [mode=melt|fill|smooth faces=4 times=1]: melt removes blocks with faces= or more open sides (spikes,
  lone blocks, sharp edges); fill fills open blocks with faces= solid sides with their neighbours' block (pits,
  notches); smooth does both. Raise times= for stronger, lower faces= for harsher.
- blend X,Y,Z X,Y,Z [shape=true times=1]: each block becomes the most common in its 3x3x3; shape=false blends only
  materials (softens a seam between stone and dirt without changing the shape).
- paint X,Y,Z X,Y,Z B [faces=top|side|all depth=1 slope=A-B chance=100]: paints the exposed skin. slope= in degrees picks
  by the ground's steepness: stone on cliffs (slope=50-90), grass on flats (slope=0-30), gravel on screes; chance= and
  a clump= mix splatter it.
- river X,Y,Z X,Y,Z ... [w=5 depth=3 bank=4 slope=1 wander=w water=B bed=B shore=B]: a channel whose water holds one
  level that only falls downstream (no water staircases), banks sloping down to it, shore= on the waterline. Points on
  the ground, upstream first.
- tunnel X,Y,Z X,Y,Z ... [r=3 rough=1 B]: a winding tunnel through 3D points (air, or B such as water or lava).
- cave X,Y,Z X,Y,Z [density=30 scale=16 roof=4 B]: noise caves through the box's solid ground, keeping roof= blocks
  under the surface.

THINGS IN A BUILD (build_batch; values with spaces in quotes: text="Two words")
- sign X,Y,Z B text="line 1|line 2" [back= color=dye glow=true wax=false]: B oak_sign[rotation=N], oak_wall_sign[facing=]
  or a hanging sign; waxed by default so players cannot edit it.
- banner X,Y,Z B patterns=stripe_top:red,cross:black (bottom layer first, up to 16; pattern ids as /give takes them).
- head X,Y,Z B player=NAME | skin=HASH|URL | texture=BASE64: decorative object heads (a globe, a crate, food) come from
  head sites as a textures.minecraft.net hash or base64 value; B is the player head block with rotation=N, or its wall
  form with facing=.
- book X,Y,Z lectern[facing=] title="..." author=... pages="page one|page two" (\n inside a page breaks the line).
- container X,Y,Z B items=bread:16,iron_ingot:3 | loot=chests/village/village_weaponsmith: chests, barrels, shulkers.
- entity X,Y,Z TYPE [data={SNBT} still=true yaw=DEG count=N]: mobs, villagers (data={VillagerData:{profession:
  "minecraft:farmer"}}), display entities; still=true keeps it standing there (no AI, silent, never despawns).
- frame X,Y,Z item=ID facing=DIR [glow=true invisible=true turn=0-7]: an item frame in the air block in front of a wall,
  facing out. stand X,Y,Z [head= chest= legs= feet= mainhand= offhand= yaw= small=true base=false arms=false
  data={Pose:{...}}]: a dressed armor stand.
  Entities are summoned after the blocks, tagged with the build, and build_undo removes them.
- biome X,Y,Z X,Y,Z ID [only=ID]: paints the biome (grass, foliage and water colours, weather, mob spawns) with /fillbiome.
- feature X,Y,Z ID [count=N spread=R]: the game's own feature (fancy_oak, mega_spruce, birch_bees_005, huge_red_mushroom,
  forest_rock, amethyst_geode, flower_meadow...) with /place feature, on the ground when spread= is given. Not on
  Minestom. Biomes and features are not undone by build_undo (the world backup is).
- light X,Y,Z X,Y,Z [B level=8 on=floor|ground|top|wall|ceiling]: lights wherever existing light would fall below
  level=, spread out, as few as needed: lanterns by default, hanging ones for on=ceiling, torches on walls, or B
  (light[level=15] for invisible light).

ROOMS, PICTURES AND FILES
- furnish X,Y,Z X,Y,Z room= [wood=spruce color=red density=50]: furnishes a built room by its use: bedroom, kitchen,
  dining, library, study, tavern, storage, smithy, alchemy, chapel, throne, shop, barracks, bathroom. Give the room's
  inside (floor to ceiling) after walls and roof: props go in corners and along walls (chests and shelves facing out,
  beds head to the wall), a centre piece (tables with chairs, a long dining table, pews and an altar, a throne, a
  counter), a rug around it and a lantern on a chain; spots beside doors and openings stay clear. Then add your own
  touches: it is a start, not the finished room.
- image X,Y,Z file=PNG w=N [h=N plane=x|z|floor palette=all|wool|concrete|terracotta|glass dither=true]: a picture as
  blocks, X,Y,Z its bottom-left (walls) or north-west corner (floor, for map art); transparent pixels are left alone.
- structure file= and scatter file= read Sponge .schem, the game's structure .nbt files and Litematica .litematic,
  told apart by content; MCEdit .schematic from before 1.13 must be re-saved as .schem.

SCALE AND COMPOSITION
- A landmark is seen from far away: give it a strong, asymmetric silhouette (a tall tower beside a low hall), not a box with a spike.
- Towers taper or step in, have a base, a shaft and a cap; no 1-block-thick towers.
- Lead the eye: a path or road to each entrance, a plaza with a focal point, varied heights along a street.
- Repeat a building with variations (rotate=, a different roof or colour), never copy it unchanged.

EFFICIENCY
- One build_batch per building with block mixes, repeat= for rows of windows and copy/structure for repeated parts; dryRun=true checks names without placing.
- structure_save good parts (a window, a lamp post, a roof end) and stamp them; world_summary diff= shows what a change did.
