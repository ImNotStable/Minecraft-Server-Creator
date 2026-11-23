# ADR 0079: Trees, forests and flora in build_batch

Status: accepted, 2026-11-23. Decided by Claude. Jeremiah asked to fill the gaps in the build tools and the standard
library, comprehensively, starting with what makes builds look made by a person. The gap list put trees first: "no tree,
bush or flower generation, which is the main reason builds look artificial". The plugin survey
(`msc-docs/docs/plugin-survey.md`) agrees that trees are the biggest tell.

## Context

Until now an agent had two ways to get a tree: draw one by hand as a text structure, or `structure_save` one and
`scatter` copies. Both give the same tree over and over, turned a quarter at most, and agents mostly drew a log column
under a leaf ball. Ground plants were a `decorate on=ground` mix the agent had to spell out, with every petal's `facing`
and `flower_amount`, and nothing kept grass off sand.

The survey's first recommendation is the vanilla `/place feature` command. That gives the game's own trees, but it runs
outside `build_batch`'s canvas: no dry run, no undo id, no review of what it placed, nothing on Minestom, and only what
the game ships. A `feature` step for it comes later as an extra, not instead.

## Decision

Three new `build_batch` steps, drawn on the canvas like every other step (one undo id, `dryRun`, `replace=`, joins):

- `tree X,Y,Z [kind= size= h= log= leaves= seed=]` grows one plant from code (`Nature`): 19 kinds with their own
  silhouettes (oak and birch crowns, big oaks with branches, spruce and pine cones, mega spruce and jungle trunks 2 wide,
  bent acacias, dark and pale oaks with hanging moss, arcing cherries, mangroves on roots, crooked azaleas on rooted
  dirt, weeping willows, dead trees, leaning palms, bushes, huge red and brown mushrooms, crimson and warped fungi with
  shroomlight and weeping vines), four sizes, and the seed varying every branch, so no two trees match. Logs carry their
  axis along each branch, leaves are `persistent=true` so nothing decays, waterloggable parts say whether they stand in
  water. Leaves, vines and moss fill only air and plants; trunks also pass through leaves and water; roots also into
  soft ground. A tree beside a wall does not eat it. `log=` and `leaves=` take any block or mix.
- `forest X,Y,Z X,Y,Z [kind=oak:3,birch:1 size=mixed spacing= chance= on=]` grows trees on the ground across a box,
  one per spacing cell (the same spot rule as `scatter`), each of a kind picked by weight and seeded from its spot.
- `flora X,Y,Z X,Y,Z style=... | B [chance= clump= soil=]` is `decorate on=ground` with a biome's plant mix (16 styles:
  meadow, plains, flowers, forest, taiga, swamp, jungle, savanna, desert, cherry, lush, snowy, mushroom, nether, crimson,
  warped), in patches by default, and only on the soil those plants grow on. `decorate` gained the same `soil=` filter.

Trees and forests turn their own logs, so they are exempt from the "say how each block is placed" rule like `roof` and
`trim`. `BlockRules` no longer asks `mushroom_stem` for an axis it does not have.

## Consequences

- An agent writes `forest` and `flora` lines instead of hand-drawing trees; the build guide says so.
- The trees are MSC's own shapes, not the game's features. Datapack trees and `/place feature` remain the survey's
  follow-up for vanilla-based servers.
