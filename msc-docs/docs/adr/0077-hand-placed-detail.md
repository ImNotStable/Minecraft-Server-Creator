# ADR 0077: Hand-placed detail

Status: accepted, 2026-11-22. Decided by Claude. Jeremiah: builds look "like a 3-year-old or an AI built it",
straight-lined or mathematically curved; make them look placed by a skilled builder, with comprehensive tooling, no
style-biasing templates (ADR 0073, 0074), deciding without decision cards. He added that straight lines are fine and
needed: good builds also have individually placed blocks wherever they make the whole look better.

## What builders do that the tools could not

Guides from build teams (WesterosCraft's gradients and exteriors, Conquest Reforged's gradients) and common build advice
agree on a short list: depth (no flat plane: plinths, belt courses, cornices, frames, recessed windows), shaped blocks for
trim (stairs, slabs, walls, fences, trapdoors), gradients by shade without skipping a step ("splattering"), roofs with
eaves and overhangs, weathering where water and sun act (moss low and at drips, wear on exposed edges, not random
discoloration), plants that tie a build to its ground, furnished interiors, light, and asymmetry.

Against that list MSC's `build_batch` had shapes and fills, and three gaps that showed in every agent's builds:

1. **Nothing joined.** Builds are written without block updates (`physics=false`, so sand and water stay put). So every
   fence, pane and wall stood alone as a post, stair corners stayed square, a door was its lower half, a bed its foot, and
   a stair without `facing=` faced north. A player's placement does all of this; a build without it reads as made by a
   script, whatever its design.
2. **Every detail block cost a line and a guess.** A shutter is an open trapdoor facing the right way; a sill an
   upside-down stair; a cornice a row of them around the outside only. An agent wrote each as `set` with exact
   properties, got the facings wrong or left them out.
3. **No pass worked on what was already built.** Weathering, vines, props along walls and trim around windows all depend
   on where the walls, ledges and holes are; the steps only knew their own boxes.

## Decision

1. **Joins on every build** (`Joins`, after the steps are drawn, looking at the world around the build too): fences,
   panes, bars and walls connect to their own kind and to full blocks (not leaves); walls rise to a post where they turn
   and go tall under a block; stairs get the game's corner shape (its own `getStairsShape` rule); doors, beds and tall
   plants get their other half where it is free; vines and lichen cling to the blocks beside them; lanterns hang under a
   ceiling; torches beside a wall become wall torches; and a stair, trapdoor, chest, furnace, barrel or ladder that
   `roof`, `trim` or `decorate` set without `facing=` turns to the wall beside it (stairs back to it and upside down
   under a ceiling, a trapdoor open flat on it). Only what was left unsaid is filled in, so saved structures and copies,
   which name every property, come out exactly as they were. The canvas is one block wider than the steps so a door's
   top or a bed's head fits.
2. **The agent decides placement** (`BlockRules`; Jeremiah: "force the AI to determine blockdata such as what
   direction, waterlogged"). A block written straight into a build (`set`, `fill`, the shapes, land steps, structure
   text and files) must say what a builder decides by how they place it: `facing`, `half` (stairs, trapdoors), `type`
   (slabs), `axis` (logs, wood, stems, pillars, basalt, deepslate, chains...), `open` and `hinge` (doors, gates,
   trapdoors), `face` (buttons, levers), `rotation` (standing signs, banners, heads), `hanging` (lanterns), `attachment`
   (bells), `vertical_direction` (dripstone). Otherwise `build_batch` places nothing and names each block with what to
   add, such as `oak_stairs needs facing=north|east|south|west half=bottom|top`. A waterloggable block in or beside water
   must say `waterlogged=`; elsewhere dry is the only sensible answer and is not asked. What the game itself derives
   from the neighbours (connections, stair corners, a door's top half) stays automatic, and `roof`, `trim` and
   `decorate` place blocks whose facing differs per spot, so they turn them themselves. Only `minecraft:` blocks are
   known; a mod's blocks pass. A `world_slice` without `states=true` drops properties, so pasting one back is refused
   with that hint.
3. **Gradients**: a block `a>b>c` (each stage a block or a mix) runs over the step's own box along `grade=` (y by
   default, `-y`, `x`, `z`...), each position moved up to most of a stage by a hash (or smooth noise with `clump=`), so
   neighbouring stages meet over a ragged seam and the first and last never touch.
4. **Detail steps**, each working on what is already there (world and earlier steps):
   - `roof`: gable, hip or shed over a wall box, rows from the box's bottom y with `overhang=`; stairs face up the slope
     and their corners join, slabs alternate halves for a low pitch, full blocks step; `pitch=2` is steep, `gable=`
     closes the ends in the walls' planes, `ridge=` tops an odd span (the stair's own slab by default), and a box taller
     than one layer caps the roof flat at its top y. A roof is geometry like `dome`, not a style: every block, pitch and
     overhang is the caller's.
   - `trim`: on the outside faces of the walls (open to air and facing away from the box's middle): `top` (cornice),
     `base` (plinth), `band` (belt course at `level=`), `out=` deep with the corner piece; `corners` replaces outer
     corner columns; at window and door holes (air through a thin wall), `sills`, `lintels`, `shutters` (trapdoors open
     flat on the wall beside the hole) and `frames`. Stairs, slabs and trapdoors are turned to fit.
   - `decorate`: things where they belong: `on=top` (any upward face), `ground` (open to the sky), `floor` (indoors:
     under a ceiling and walled on four sides), `edge` and `corner` (indoors, beside walls), `wall` (down a wall face from
     its top: vines) and `ceiling` (hanging); `chance=` with `clump=` patches, or one per `spacing=` cell; `length=A-B`
     up from floors and down from walls and ceilings, ending in `tip=` (a chain, then a lantern).
   - `weather`: moss low down and on ledges, cracks on edges and higher up, verdigris on exposed copper, `swap=from>to`
     pairs by exposure, all in patches; only real variants (mossy and cracked stone bricks, cobblestone, deepslate,
     nether and blackstone bricks; the copper families), so no name is guessed. `break=` knocks exposed top edges off,
     leaving `chip=` (slabs, stairs) or air: a ruin's broken wall.
   - `rough=N` on `sphere` and `dome`: the surface bulges in and out by up to N blocks of 3D noise, for boulders,
     canopies and hedges.
5. **Review asks for finish** (`BuildReview`): plain wall tops (rooflines and wall tops ending on a plain block, aim under
   60%), bare base (where the build meets the ground with nothing shaped, aim under 50%), floor under a roof against
   furnishings (one per 16 floor blocks; panes, bars and doors do not count), and plants. Straight lines are not judged.
   Each fix names the step that makes it.
6. **Guide**: the build guide's detail pass names the steps, says which block data to decide and what joins
   itself, and says straight lines are fine with detail layered on them.

## Consequences

- Measured on a real Paper 26.3 server (`D:\Projects\MSC Detail Test`), the same 13 x 9 cottage two ways. Written as
  agents write it today (and as the old core placed it: unjoined fences and panes, half a door, a floating lantern),
  `review=true` asks for variants, furnishing (180 floor blocks, 5 furnishings) and plants. The same massing with 38
  steps (gradient walls, `roof`, `trim` shutters, sills, plinth and lintels, `decorate` props, lights, carpets, plants
  and vines, `weather`) passes: plain tops 3%, bare base 7%, 44 furnishings, 242 plants, 11 lights. Renders are in the
  PR.
- Joins add one world read around the joined blocks per build that has any; a build with none reads nothing more. A
  dry run without a server joins against the canvas alone.
- Builds written before this that left placement to defaults (`set ... oak_stairs`, `fill ... oak_log`) are now
  refused until they say it. That costs tokens per block (`oak_log[axis=y]`), which a legend entry or a mix pays once
  per kind, not per block; the return is that no stair faces north by accident.
- Existing world blocks next to a new build are not rejoined (a new fence beside an old one connects only on its own
  side); `physics=true` still asks the game to do that, with sand and water.
- The renderer drew plants as solid cubes, so a flower bed read as coloured blocks and an agent judging a render saw
  clutter; it now draws them as two thin crossed panels (flowers, saplings and mushrooms smaller), as the game does.
- The tool list grows by about 430 characters (the four steps are one line each in `build_batch`'s help, with their
  options explained in `msc_status guide=build`); its budget in `McpBridgeTest` rises from 17,800 to 18,250.
- The thresholds (60%, 50%, 1 per 16) are rules of thumb like ADR 0068's; the next agent runs are the measure.
