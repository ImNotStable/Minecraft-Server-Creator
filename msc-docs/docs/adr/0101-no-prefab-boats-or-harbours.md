# ADR 0101: No prefab boats or harbours

Status: accepted, 2026-11-24. Decided on Jeremiah's word. Supersedes decisions 1 to 3 of
[ADR 0099](0099-boats-and-harbours.md) (the `boat`, `harbor` and `pier` steps); its review checks (decision 4) stay.

## Context

ADR 0099 added `build_batch` steps that generated a whole boat, harbour or pier. Jeremiah: "We're creating
models/defaults for the AIs now?? It should be able to create EVERYTHING from scratch." It is the rule ADR 0073 (bare
templates) and ADR 0074 (texture shapes are examples, not starts) already follow: a preset makes every server's ship the
same ship, and an agent that calls it learns nothing about building one.

## Decision

1. **The `boat`, `harbor` and `pier` steps are removed** (`Vessels`, the `Step` records, the parser cases, the help
   line). `build_batch` keeps only general steps: shapes, fills, stairs and detail placed by hand, terrain and the
   shaping brushes.
2. **The guide says what makes a good boat and harbour** instead (`guide=build`, "Boats and harbours"): a hull planned
   slice by slice, narrowing to bow, stern and keel with stairs at the steps, sitting 1-3 blocks deep with a dry hold;
   a mast about as tall as the hull is long with bellied sails on yards or a boom; banks that slope to a beach along a
   ragged line, a quay only where boats tie up, piers on posts to the bed.
3. **The review checks stay and get one more**: box hull, floating hull, bare mast, stump mast, sheer banks and square
   basin (ADR 0099), and **flat sails**: a boat with 12+ sail blocks (wool or banners) none of which has sail beside it
   along both x and z, so no sail bellies out. Fix texts name what to build, not a step to call.
4. **The rule for the rest of this list** (Folia, Pterodactyl eggs, the BungeeCord stdlib adapter): tools and
   libraries that run what the agent makes are fine; nothing generates content for it.

## Other generators on main, flagged, not removed here

`furnish` (ADR 0083) fills a room with a preset set of props by its use; `tree` and `forest` grow procedural trees by
kind; `flora` plants preset mixes by style; `roof` draws gable, hip and shed roofs. `furnish` is the closest to a
prefab (a whole room from one line) and is the first candidate if the same rule should apply to it; trees, plants and
roof shapes are closer to brushes. They stay until Jeremiah says otherwise.

## Consequences

- An agent builds every boat and harbour itself; the review tells it when one reads as a crate or a pit.
- Tests: `WaterReviewTest` builds a good boat and a sloped shore block by block and passes them, and fails run 6's
  plank box, a flat sail and a square pit.
