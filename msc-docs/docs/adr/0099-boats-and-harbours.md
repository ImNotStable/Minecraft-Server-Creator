# ADR 0099: Boats and harbours

Status: accepted, 2026-11-24. Decided by Claude, from Jeremiah's open list after the Shattered Meridian screenshots.

## Context

ADR 0094 left boats and harbours out ("no number yet; the tour puts them in front of the agent"). Run 6's harbour, as
Jeremiah's in-game screenshots show it: the boat was a box of planks laid on the water with a three-block stump of a
mast and no sail, and the harbour a square pit dug into the land with sheer dirt sides. The other shapes in those
screenshots (trees through walls, solid wool awnings, leaf pillars, trapdoor clumps) are ADR 0094's checks, already on
main. `build_batch` had no step that makes a hull, rig or basin, so an agent had to place every block of a ship by
hand, and the review said nothing about what it made.

## Decision

1. **`boat X,Y,Z`** (`kind=rowboat|sloop|ship facing= len= wood= hull= deck= sail=`). X,Y,Z is midship at the water's
   surface. The hull's half-width is a function of position (a rounded stern into a transom, a pointed bow on a
   quarter-ellipse) and depth (narrow at the keel, full at the gunwale); the keel is shorter than the deck (raked bow,
   overhanging stern). Each level's outer cells, and every cell outside the level below or the slices fore and aft, are
   skin, so the hold is closed to the water; where the hull steps out it is rounded with upside-down stairs. The hull
   sits 1 (rowboat), 2 (sloop) or 3 (ship) blocks under the waterline with air inside. Sloops and ships get rails,
   bow and stern lanterns, a bowsprit, a rudder on the raked sternpost, a lit hold with barrels (so the room checks of
   ADR 0093 pass), and masts as tall above the deck as 0.55 to 0.85 of the length. A sloop's sails are fore-and-aft
   (a mainsail over a boom, a jib to the bowsprit), a ship's are square on yards in two or three tiers with a fighting
   top on the main mast. Every sail bellies one block in its middle, with both offsets filled where the belly steps,
   so it reads as a curved sheet with no gaps.
2. **`harbor X,Y,Z X,Y,Z`** (`water= depth= bank= quay= piers= wood= quay_block= shore= bed=`). Digs the box to a bed
   `depth` below `water`, each column's top dropping one block per `bank` blocks in from the nearest non-quay edge,
   with fractal noise on that distance so the shoreline is ragged; sand and gravel at the waterline, gravel, sand and
   clay below, the land's own top block on the cut slopes. `quay=DIR` makes that side a stone quay (a retaining wall
   from the bed to the land) with ladders every 10 blocks and lamp posts every 8, and `piers=` (default 2 with a quay;
   refused without one) run out from it over 55% of the basin.
3. **`pier X,Y,Z X,Y,Z`**: a plank deck at the points' height on log posts every 4 blocks down to the first solid
   block (40 at most), fenced along both sides, lanterns along it and at the far end (the end with the deeper water).
4. **Review checks** (`WaterReview`, run from `BuildReview` beside ADR 0094's): a boat is a group of built blocks
   resting on water and joined to no ground and not to the region's sides (a pier on posts or a moored house is not).
   - **box hull**: 85% or more of its slices along its length as wide as the widest (3+ wide, 5+ long).
   - **floating hull**: no block of it has water beside it: it sits on the surface instead of in it.
   - **bare mast**: a vertical run of 4+ logs, fences or walls with no wool or banner aboard (10+ long boats).
   - **stump mast**: the tallest such run under half the boat's length (10+ long boats).
   - **sheer banks**: a body of water with a free surface of 40+ blocks whose natural shore (ground, as
     `BuildReview.ground` judges it; built quays do not count) rises 2+ blocks straight out of the water on 60% or
     more of 20+ shore faces.
   - **square basin**: such a body that fills 90% of its box, 6+ blocks on each side, not cut by the region's edge,
     with natural ground on at least half its perimeter.
   The basin checks also run on regions that are mostly land, where a harbour usually is, and when nothing is built
   above the ground.

## Consequences

- One step makes a boat that passes its own review and the room checks; the review fails run 6's boat on all four
  boat checks and its harbour on both basin checks (`VesselStepsTest`).
- The steps are starts: the guide tells the agent to add a name board, cargo, flags and crew.
- Not done: sails that follow the wind (all sails face the bow), boats heeled or under way, docks with cranes or
  warehouses, river ports (the harbour basin is one level of still water), and boats of mixed blocks (glass, metal)
  beyond `hull=`/`deck=`/`sail=` patterns. A boat joined to a pier by a gangplank is judged as part of the pier.
