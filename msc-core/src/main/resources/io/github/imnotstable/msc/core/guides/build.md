BUILD GUIDE: what makes a build look hand-made, and the loop that gets it there.

DONE MEANS (check before you report a build or a map finished)
- Every build and every land region has a world_summary review=true that ends "no number below the bar". A review
  that says "verdict: NOT DONE" is not done, however good the render looks; msc_status and dev_cycle list the reviews
  still failing until one of the same region passes.
- Land was shaped, not only generated: terrain, smooth or paint where the build meets it, path to each door, scatter
  or flora for ground cover, trees you built around it. Generated land is the start, not the finish.
- Every build met the ground: trim at=base (a plinth of stairs or slabs; full blocks are still a clean line),
  decorate on=ground (bushes, flowers against the wall) and worn ground at the doors; rooms furnished (decorate on=edge, corner, ceiling); weather for age.
- A failing review names what to change and where: add exactly that, in that place (a trim, a decorate, a light), and
  review again. Rebuilding the whole build from its first script repeats the same numbers.
- Review once per round of edits, not after each: a review of a region no block changed in answers "UNCHANGED" with
  the last verdict, an identical call answers "same result as your identical call", and a batch that changed nothing
  answers "nothing changed". Each is a call spent on nothing: edit first, then review.
- If the run must end with a review still failing, say which and why in the report; never call it done.

PLAN FIRST (strongly suggested: the builds of a planned site read as one place made by players)
- Before the first block of a site with more than one build, write the plan and save it with msc_status plan=. It is
  short: a line for the site (what the place is, who lives there, where the land rises, where the water and the ways
  run, the focal point a visitor walks toward), then one line per build: "name: place and footprint, storeys and
  silhouette, palette as block ids (main, secondary, one accent), roof form, the one thing that sets it apart".
- Make the builds differ on purpose: each line changes at least two of footprint, height, roof form, palette and
  what the build is for. Two lines that read alike will look alike: rewrite one before building.
- Fit the plan to the land you have (world_heightmap first): bigger and older builds on the best ground, small ones
  squeezed in, ways following the slope, not a grid.
- Review each build under its plan name: world_summary review=true name=smithy why="..." adds it to the build ledger
  (.msc/builds.json) and shows its plan line beside the review, with any block id the line names that the build lacks.
  Build to the plan, or change the plan and save it again; the ledger keeps the newest plan.
- The ledger is the world's memory across sessions: world_summary on any region lists the builds there (id, name,
  review status, why), and msc_status builds=true lists them all with the planned ones not yet built. Read it before
  building in a world you did not build this session.
- Protecting builds from players is the server's job: on Paper with WorldGuard installed, set up its regions yourself
  from the ledger's boxes; MSC does not depend on it.

WHERE TO BUILD
- Build in the world with build_batch (one building per call), not with plugin code that places blocks: code builds are never seen, reviewed or undone. Plugin code only swaps prepared states at runtime (save each state with structure_save, paste it from code or with build_batch structure file=).
- Land first (next section), then builds fitted into it.

LAND FIRST
- The flat dev world is a test floor, not a landscape. Never paint a region onto it: no blobs of one block on flat ground, no 1-block rings or outlines as "hills".
- An open world, a region or a dimension: make it a generated world with world_create (normal, amplified, large_biomes, floating_islands, caves, nether, end; one biome everywhere or several sharing the land; flat or void for a blank slate), restart, and build into its real terrain with world=msc:<name>. Several regions can be several worlds, or several biomes in one.
- Land by hand: build_batch terrain is a noise primitive (noise=fbm, ridged, terraced or waves, scale=, octaves=, steps=); you decide the landform by combining boxes, noises and brushes (water= for lakes, bed= for shores, cliff= for rock on steep sides). The box's height is the land's: hills rise 10-40 blocks, mountains more. Big areas: tile boxes with edge=0 and one seed.
- Ways: path through 3+ points with wander= (curves, ragged sides, on the ground); a stream is path depth=2 with water and bed=.
- Ground cover: give ground mixes clump= (patches with wavy edges); a plain mix reads as static.
- Nature: MSC has no tree or plant presets (ADR 0103); build each tree yourself. A trunk of logs that tapers or forks,
  branches of logs or wood going out and up (axis= along them), and leaves in loose, uneven clusters around the
  branch tips (sphere rough= or set by hand), never a box or a ball on a stick; vary height, lean and size from tree to
  tree, roots and a few fallen leaves at the base. Ground cover: flora with your own plant mix for that biome
  (short_grass:40,fern:10,poppy:2...), in patches with clump=, soil= where it grows. Mix kinds and sizes, leave
  clearings, thicken toward water and slopes. Nothing floats: rails and props rest on something.
- Fit builds to the land: foundations reach down to the ground on slopes, a plaza is cut into the hill with retaining walls, and the ground at a build is worn (paths to doors, coarse dirt, gravel), not a clean edge.
- Underwater builds: find the sea floor with world_slice of a thin column (world_heightmap stops at the water's
  surface), stand the build on
  it and let the water stay around it: walls, columns and arches rise from the floor, and only the top (spires, a
  roof) may break the surface. Waterlogged=true for stairs, slabs, fences, lanterns and chains in the water; a dry
  room inside is a sealed hull filled with air in a later step. A build resting on the water surface is a floating
  barge, not a sunken one.
- Review land as well as builds: world_summary review=true on a land region reports ground at one level, speckled ground (a random mix of clashing blocks) and floating blocks, and it fails land reviewed on the flat dev world: give a map its own world_create worlds. Review the whole map once too, not only each plot.

THE LOOP (every build)
1. Plan in 3 lines (its line in the site plan, PLAN FIRST): purpose and story (who lived here, what happened),
   silhouette (3+ masses of different heights), palette.
2. Block out the masses: walls 1-2 thick, real floor heights (4-5 per storey), doors 2-3 tall, roofs you shape (DETAIL STEPS).
3. Detail pass (below). This is most of the work; a block-out is not a build. Straight walls and rows are fine:
   good builds have plenty. What makes them look hand-made is the single blocks layered on that structure.
4. world_render the build from 2 views (iso and one side), world_tour it (eye-height views at its doors, inside and
   at what the review flags: what a visitor sees) and run world_summary review=true name=<its plan name> on the same
   region (a wider box that passes does not settle a failing one).
5. Name the 3 weakest things concretely ("north wall is a flat 12x8 plane of one block"), fix them in one build_batch, re-render. Repeat until review passes and the render reads well. Never call a build done on its first render, and never praise a render the review flags.

PALETTE
- 60/30/10: a main material with 2-3 variants mixed in (no one block over half the build: the review fails it), a secondary (wood, a second stone), one accent (copper, gold, a coloured block) used sparingly.
- Mix only near tones (stone_bricks with its cracked and mossy variants, deepslate_bricks with deepslate_tiles). A random mix of clashing colours (deepslate with prismarine, purpur with gold) reads as static; put contrast in lines instead: trims, bands, frames.
- Gradient: darker, heavier blocks at the base, lighter near the top. Frame with a contrasting trim (logs or a polished stone).
- Pick the palette for this build's story and place yourself; no two regions of a map share one.

DETAIL PASS (use most of these on every build; the steps work on what is already built, so block out first)
- Decide every block's placement as a builder would: which way a stair, trapdoor, chest or wall torch faces and its half,
  a log's axis, a slab's type, a door's hinge, open or closed, a sign's or banner's rotation, a lantern hanging or
  standing, and in or beside water whether it is waterlogged. build_batch refuses a block that leaves these to the game's
  default (every stair facing north, every log upright), naming what to add. Write them in each block or legend entry:
  s=oak_stairs[facing=east,half=bottom]. What follows from the neighbours is added for you: fences, panes, bars and walls
  connect, stair corners turn, doors, beds and tall plants get their other half, vines cling. trim and decorate turn
  their own blocks to where each lands.
- Roofs: stairs face up the slope with corners joined, overhang 1-2, slabs for a low pitch, gable ends closed in the
  wall block. Break long ridges: a second, lower roof, a dormer, a chimney.
- Depth: no wall is one flat plane. trim at=base (plinth), at=band level=Y (belt course), at=top (cornice) and at=corners
  (pillars or quoins of logs or polished stone); windows set 1 in, pillars or a timber frame 1 out.
- Windows and doors: trim at=sills, lintels, shutters (trapdoors), frames; panes or bars in the hole.
- Doors are two blocks and people walk through them: the door's bottom stands on the floor's top level inside and out
  (the floor block itself is one below), the block above it is the door's top, the lintel goes above that. Lay the
  interior floor before you cut the doorway, and never at the door's top half. build_batch refuses half a door;
  review=true counts half doors, blocked doors and wall holes left open.
- Gradient: a>b>c on the walls, darker and heavier at the base (cobblestone>stone_bricks>andesite), seams ragged, no
  shade skipped; grade=x or z for a run along a wall.
- Interiors: every room furnished and lit (review=true counts dark floor: a light within 6 blocks); ladders run from
  the floor up without gaps; signs, NPC names and holograms as text components (CustomName:{text:"Vela",color:"gold"}).
- Props: decorate on=edge (barrels, shelves, crates along walls), on=corner (beds, chests), on=ceiling with tip=
  (chain then lantern), on=floor (carpets in patches with clump=), on=top (props on ledges); furnished interiors, tables
  from slabs and fences, beds, shelves; lanterns on chains, banners, signs, flower pots, hay, carts, market stalls.
- Nature: decorate on=wall (vines, glow lichen) from the wall tops down, on=ground (grass, flowers, leaf bushes) around
  the base with clump=, on=ceiling (hanging roots, weeping vines) under bridges and arches; trees and water nearby.
- Age and story: weather (moss low and on ledges, cracks on edges, verdigris on copper, swap= for other blocks), break=
  for a ruin's broken tops with chip= slabs and stairs; rubble at the base, a collapsed roof corner.
- Organic shapes: sphere/dome rough=N for boulders, leaf canopies and hedges, never a perfect ball.
- Light: lanterns, torches or glowing blocks at doors, paths and windows; no dark interiors.
- Atmosphere (what a place sounds like, what moves in it, its light and sky): msc_status guide=atmosphere.

DETAIL STEPS (build_batch; each works on what the world and earlier steps already hold)
- Roofs are yours to shape (no roof generator, ADR 0103): stairs rising row by row from the eave (half=bottom facing
  up the slope, upside-down stairs under the overhang), a ridge of slabs or stairs, gable ends closed in the wall
  block, overhang 1; vary pitch and form between buildings (gable, hip, shed, mansard, dormers).
- trim X,Y,Z X,Y,Z B at=...: only outside faces (facing away from the box's middle). top (cornice on wall tops), base
  (plinth), band level=Y (belt course), out=N deep; corners (outer corner columns, replaced); at holes through a thin
  wall: sills (under), lintels (over), shutters (trapdoors beside, opened flat on the wall), frames (around, replaced).
- decorate X,Y,Z X,Y,Z B on=...: top (any upward face), ground (open to the sky), floor (indoors), edge and corner
  (indoors, beside walls), wall (down wall faces from their tops), ceiling (hanging). chance=25 percent of the spots,
  in patches with clump=N, or one per spacing=N cell (lights); length=A-B up from floors, down from walls and ceilings;
  tip=B ends each (chain then lantern).
- weather X,Y,Z X,Y,Z [amount=30]: moss low and on ledges, cracks on edges, verdigris on copper, in patches;
  swap=from>to,... for other blocks; break=N knocks exposed top edges off, leaving chip=B or air.

- flora X,Y,Z X,Y,Z B [chance=35 clump=5 soil=B]: your plant mix on open ground, in patches, only on soil= blocks
  when given (decorate takes soil= too).


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
- river X,Y,Z X,Y,Z ... bed=B [w=5 depth=3 bank=4 slope=1 wander=w water=B shore=B]: a channel (bed= is yours, no stock mix) whose water holds one
  level that only falls downstream (no water staircases), banks sloping down to it, shore= on the waterline. Points on
  the ground, upstream first.
- tunnel X,Y,Z X,Y,Z ... [r=3 rough=1 B]: a winding tunnel through 3D points (air, or B such as water or lava).
- cave X,Y,Z X,Y,Z [density=30 scale=16 roof=4 B]: noise caves through the box's solid ground, keeping roof= blocks
  under the surface.

BOATS AND HARBOURS (build them yourself from the steps above; the review checks what makes them read as real)
- A hull is not a box. Plan it slice by slice along its length: widest a little behind the middle, narrowing to a
  pointed bow and a rounded or square-cut stern, and narrower again toward the keel, so each layer down is smaller than
  the one above. Round the steps between layers with upside-down stairs facing inward, and let the bow rake forward
  past the keel. Raise the deck line toward bow and stern (sheer), not one flat level.
- A boat sits in the water: sink the hull 1-3 blocks below the surface and keep the inside dry (air, not water, inside
  the planks), with a deck above it; light the hold and put cargo in it if it is a room.
- Rig it: a mast stands roughly as tall above the deck as the hull is long; give it sails of wool (or banners) on yards
  across the mast or along a boom, bellied: the middle of each sail one block forward of its edges, with both blocks
  filled where it steps so there are no gaps. Add a bowsprit, rails, a rudder, lanterns at bow and stern.
- A harbour is the shore meeting the water, not a pit. Banks slope down to a beach or rocks (drop a block every 1-3
  blocks, along a ragged line with coves and points), sand and gravel at the waterline; build a stone quay only where
  boats tie up, with ladders down its face, bollards and lamps, and piers on posts that reach the bed.
The review flags box hulls (one width bow to stern), hulls sitting on the water instead of in it, masts with no sail
or under half the hull's length, flat sails, banks rising straight out of the water, and basins that fill their box.

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
- biome X,Y,Z X,Y,Z ID [only=ID]: paints the biome (grass, foliage and water colours, weather, mob spawns) with /fillbiome. Not undone by build_undo (the world backup is).
- light X,Y,Z X,Y,Z [B level=8 on=floor|ground|top|wall|ceiling]: lights wherever existing light would fall below
  level=, spread out, as few as needed: lanterns by default, hanging ones for on=ceiling, torches on walls, or B
  (light[level=15] for invisible light).

ROOMS, PICTURES AND FILES
- Furnish rooms yourself by their use (no furnish preset, ADR 0103): props in corners and along walls (chests and
  shelves facing out, beds head to the wall), a centre piece (a table of slabs, fences or trapdoors with chairs of
  stairs), a rug, a light; keep the spots beside doors clear. Two rooms of one use should not look alike.
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
