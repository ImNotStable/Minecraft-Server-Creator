# ADR 0078: What the fourth Shattered Meridian run got past the review

Status: accepted, 2026-11-23. Decided by Claude. Jeremiah asked for a review of the fourth Antigravity run (Gemini 4
Argon High, IDE, 2026-11-22, on the core with ADR 0075 and 0077) with MSC's own tools, fixes for what comes from MSC
rather than the agent, and decisions without decision cards.

## What the run built

142 MSC calls: 26 `build_batch`, 34 `world_summary` (most with `review=true`), 23 `world_render`, 7 `structure_save`,
13 textures. The IDE showed the msc server as ready but gave the agent none of its tools, so the agent wrote its own
stdio-to-HTTP bridge (`MscDaemon.java`) and called MSC through PowerShell. It never called `world_create`.

All six realms of the design (Havenfall, Verdant Hollow, Ember Wastes, Frostveil Peaks, Drowned Kingdom, Astral Reach)
sit on one 150x150 patch of the superflat dev world, each a 20 to 50 block square with its own ground: a speckled snow
square, a lava-and-blackstone checkerboard, a moss square, a sand border dotted with gravel. Astral Reach, "floating",
is a three-block tower on Frostveil's roof. The buildings themselves pass the build numbers (detail 27-60%, flat walls
under 6%, shaped roofs, lights, furnished rooms, block data set), but four still fail "bare base" and the whole map was
reviewed once, with floating blocks left in it.

## Gaps in MSC

1. **Speckled ground was never measured.** ADR 0071 judged speckle on walls only. The snow square (17% of its ground a
   lone block among clashing ones), the Ember checkerboard (15%) and the map's sand border (11-12%) passed every review.
2. **Land on the dev world passed.** The guide says land belongs in `world_create` worlds, and the land review's only
   number for it, ground at one level, was 63-74%: under the 80% bar, because the agent raised a few hills. Nothing told
   the agent that a whole map painted on the test floor is the problem.
3. **Roofs were not judged for speckle either** (the same rule, from above). The Leviathan's copper roof is spotted with
   near tones and passes, which is right; a roof of clashing blocks would not have been caught.

The rest are agent mistakes: no `world_create`, buildings left on a bare base, a square plank ship, a pier on a perfect
4-block grid, slimes from the superflat world wandering the hub, a "floating" realm that does not float.

## Decision

1. `review=true` measures **speckled ground** on land of 256+ columns: the share of ground columns whose top block clashes
   in colour (the ADR 0071 rule, RGB distance over 64, by the top face) with at least three of its four neighbours. Aim
   under 10%: from above a lone clashing block shows more than on a wall broken by windows, and clumped patches
   (`scatter clump=`, `terrain`) score near 0. `magma_block` counts as rock.
2. `review=true` measures **speckled roof** the same way on each column's top block of a build, aim under 10%.
3. **Land reviewed on the dev world fails.** When the land is judged (1024+ columns, half of them ground) and the region
   is in the default world, which MSC always makes superflat, the fix list says to make the land in a `world_create`
   world, one per region or biomes sharing the land, and allows an arena meant to be flat.
4. The build guide names these and says to review the whole map once, not only each plot.

Not done: the IDE not showing MSC's tools (Antigravity's side; the CLI run of ADR 0071 had them), and emoji in text
displays drawn as `?` in renders.
