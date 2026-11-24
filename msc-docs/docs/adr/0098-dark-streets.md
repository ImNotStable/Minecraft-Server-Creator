# ADR 0098: Dark streets

Status: accepted, 2026-11-24. Decided by Claude, in the Shattered Meridian loop.

## What run 11 did

Run 11 (the first attempt stopped after 25 minutes at Antigravity's Gemini quota; the second ran 61 minutes, 356 MSC
calls, 99 `build_batch`, 106 reviews, 6 `world_tour`) reviewed Havenfall building by building, met the town check of
ADR 0097 43 times and fixed what it raised; all 13 of its regions pass on a fresh review. In the world afterwards:
three skeletons and a spider in Havenfall, and 22 mob drops (bones, string, rotten flesh, gunpowder, mutton). The
rooms are lit; the streets are not, so the town spawns monsters every night, as in Jeremiah's run 6 screenshots.

## Decision

A town-sized review (ADR 0097) also measures its streets: the open ground (the top solid block of each column, seen
through air and plants) within 4 blocks of a building's box and outside every box. When more than half of it, over
100 cells or more, has no light within 6 blocks, the review fails with "dark streets" and asks for lamp posts along
paths and lanterns at doors. Run 11's town: 64% dark.

## Not done

- Light is counted by light blocks within reach, not computed light levels; sky light and moonlight are ignored.
- Water columns count their sea floor as ground.
