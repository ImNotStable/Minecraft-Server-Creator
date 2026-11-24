# ADR 0103: No content presets in build_batch

Status: accepted, 2026-11-24. Decided by the coordinator on Jeremiah's rule ("It should be able to create EVERYTHING
from scratch"). Extends [ADR 0101](0101-no-prefab-boats-or-harbours.md) to the generators it flagged; supersedes the
`roof` step (ADR 0077), `tree`, `forest` and flora styles (ADR 0079), `feature` (ADR 0082) and `furnish` (ADR 0083).

## Decision

1. **Removed**: `roof` (gable, hip and shed roofs from a footprint), `tree` and `forest` (19 procedural tree kinds),
   `feature` (the game's own configured features: trees, geodes, rocks), `furnish` (a room of props by its use), and
   `flora style=` (16 biome plant mixes). Each of them put a design the agent did not make into the world.
2. **Kept, as primitives the agent drives with its own blocks**: shapes and fills, `set`, `line`, `helix`, `bands`,
   `terrain` (noise land in the agent's blocks), `path`, `scatter` (the agent's own blocks or structure files), `trim`
   and `decorate` (the agent's block along the lines or spots it names), `weather`, the shaping brushes, `river`,
   `tunnel`, `cave`, `light`, `biome`, things with data, `image`, `structure` and `copy`. `flora` stays for the agent's
   own plant mix (`style=` is refused with a pointer to it).
3. **Guidance instead**: the build guide says how to build trees (tapering, forking log trunks, leaf clusters at branch
   tips, varied from tree to tree), roofs (stairs from the eave, ridge, gables, overhang, varied forms) and furnished
   rooms (by use, centre piece, clear doors, no two alike). The review keeps judging them: roofs (shaped roof share),
   stacked leaves, leaves in walls, empty and dark rooms.

## Consequences

- Every tree, roof and room is the agent's own; builds take more steps and more tokens, the price of originality
  (as ADR 0074 measured for textures).
- `Nature`, `Furnish` and `Detail.roof` are deleted; `Spots` keeps scatter's cell spreading.
- Tests: `NatureStepsTest` now checks flora with an own mix and that every removed step is refused.
