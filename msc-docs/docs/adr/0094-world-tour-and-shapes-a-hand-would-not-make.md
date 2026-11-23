# ADR 0094: world_tour, and shapes a hand would not make

Status: accepted, 2026-11-23. Decided by Claude. Jeremiah wants the Shattered Meridian loop (an agent builds, MSC
reviews, MSC is fixed, the agent builds again) to keep running on its own, with a bot that walks every build and
takes player's-eye snapshots outside and inside, the way his in-game screenshots of run 6 found what the reviews
missed (ADR 0093).

## world_tour

A new tool, `world_tour region= [world=] [stops=1-9]`. It reviews the region (as `world_summary review=true`, and
records the result for `msc_status` like a review), then renders eye-height views (1.62 above the block stood in,
75 degree field of view) with the existing ray tracer (`eye x,y,z look x,y,z`), and returns them as one contact
sheet, numbered in its text, with the review:

1. one view of each review fix that names a spot ("first at x,y,z"), from 3 blocks away where there is room to stand;
2. each door from 4 blocks outside (the side under the open sky), then from a step inside looking into the room;
3. the build from the ground on each side of the region.

Decided against a headless game client: MSC already reads every block and renders any camera; a client would add a
second Minecraft install, login and frame capture for the same picture. The renderer shades by sun and shadow, not
block light, so darkness is measured by the review's light check, not seen in the views.

## New review checks

From the run 6 screenshots, measured on the blocks:

- **leaves in walls**: leaf blocks touching non-wood built blocks on two faces (a tree grown through a house), 8+.
- **stacked leaves**: leaf blocks in a vertical run of three or more with no log, wood or stem within 4 blocks (a
  hedge or canopy built as a box of leaf blocks), 12+. Trees from `tree` and `forest` have wood near every leaf.
- **hanging wool**: full wool with air above and below (an awning as a slab of cloth), 6+.
- **trapdoor clumps**: trapdoors touching two or more other trapdoors (a facade of trapdoors), 6+.

## Not done

- Boats, sheer harbour cuts and mixed-block vehicles have no number yet; the tour puts them in front of the agent.
- Stops are found from doors and review spots; a build with neither gets only the outside views.
