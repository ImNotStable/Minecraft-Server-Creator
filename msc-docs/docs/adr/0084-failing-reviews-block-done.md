# ADR 0084: Failing reviews block "done", natural rock is ground, builds clear their drops

Status: accepted, 2026-11-23. Decided by Claude. After the fifth Antigravity run of "The Shattered Meridian"
Jeremiah asked (through the project coordinator) for failing reviews to block "done" more firmly, a guide that pushes
decorate, trim at=base, terrain and scatter, and cover for dropped items and underwater builds.

## What the fifth run did

Antigravity CLI, Gemini 4 Neon XHigh, core 20261123-030006 (ADR 0078), the same prompt as ADR 0071: 43 minutes, 180
MSC calls, 12 refused by the block-data and waterlogged checks and then fixed. The tools reached the agent (no repeat
of ADR 0078's empty tool list). It was the first run to call `world_create`: one world per realm and a void world for
dungeons, so every realm stands on generated land. But:

- It used `fill`, `cylinder`, `ring` and `set` almost only: no `terrain`, `path`, `scatter` or `decorate`, three
  `trim`s and one `weather`.
- Every review it ran kept failing, most on "bare base" at 95-100% and "empty rooms"; it reviewed a second time and
  then reported the map finished. Its final report never mentioned a failing review.
- The Drowned Kingdom cathedral rests on the water's surface instead of the sea floor.
- Dropped item entities lay around Havenfall in the renders (gone after their five minutes).

## Decisions

1. **A failing review says NOT DONE.** The review adds `verdict: NOT DONE, N checks below the bar` before its fix
   list. "fix first" read as advice; a verdict does not.
2. **Failing reviews stay listed until they pass.** The core keeps this session's failed reviews by world and region
   (`OpenReviews`); a passing review of the same or a larger region clears them. `msc_status` and `dev_cycle` end with
   the list, so an agent that ends a run on either (this one did both) sees what is not done. The MCP instructions say
   a build is done only when its review passes, and that `msc_status` lists the ones that do not.
3. **Natural rock is ground.** Rock, terracotta and ore in an unbroken column up from the region's bottom (a generated
   world's hills, cliffs, mesas and sea floor) is left out of the build numbers like soil. Before, Havenfall's stone
   coast (36% of its blocks) counted as walls with a bare base, so "bare base" could not pass on generated land
   however well the builds met the ground. Rock standing on soil (a cobblestone wall on grass) stays part of the
   build. Shortcut: rock above an underground dirt or gravel pocket counts as built.
4. **`build_batch` clears the items its edit knocked loose.** After the write it runs `kill @e[type=item]` over the
   edit's box grown by one, in its dimension, and says how many it cleared (best effort: Minestom keeps them).
5. **The build guide starts with DONE MEANS**: every build and land region reviewed to a pass, land shaped and not
   only generated (terrain, smooth, paint, path, scatter, flora, forest), every build meeting the ground (trim at=base,
   decorate on=ground, worn ground at doors), rooms furnished, and an honest report when a review is still failing.
   A new paragraph covers underwater builds: find the sea floor with `world_slice` (`world_heightmap` stops at the
   water), build up from it, waterlog what stands in the water, seal dry rooms.

## Not done

- A review cannot make an agent keep working; it can only make "done" visibly untrue. The next run measures whether
  the list in `msc_status` and `dev_cycle` changes the ending.
- Failed reviews are kept for the session only, not across restarts of the core.
