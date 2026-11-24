# ADR 0097: A town-sized review judges each building on its own

Status: accepted, 2026-11-24. Decided by Claude, in the Shattered Meridian loop.

## What run 10 did

Run 10 (Antigravity CLI, Gemini 4 Neon XHigh, core with ADR 0096; 52 minutes, 301 MSC calls, 92 `build_batch`, 54
reviews, 6 `world_tour`) ended with all 10 review regions passing, and they still passed when toured afterwards. But
Havenfall was reviewed only as one 110x90 box, `246,54,248 356,144,316`: the town's numbers (lights 360, 3,798
furnishings) averaged its houses, and a house with 52% dark floor and a speckled roof passed inside it.

## Decision

When a review covers more than 48x48 columns, it also finds the buildings in it: groups of touching columns
(diagonals too) with 3 or more built blocks that are not ground, land blocks or plants, 16 columns or more, each boxed
one block wider. Each is reviewed on its own; failing ones lead the fix list as "buildings failing: K of N" with each
box and its fixes, so the town fails until each building passes in its own region. On run 10's town box this finds
5 buildings and 3 failing (few shaped blocks, no lights, dark rooms, speckled roof).

## Not done

- Buildings joined by a wall or a 3-tall bridge count as one.
- Buildings are boxed over the whole height of the region.
