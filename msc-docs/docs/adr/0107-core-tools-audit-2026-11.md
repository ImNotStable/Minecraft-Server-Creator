# ADR 0107: Core-tools audit after the November features

Status: accepted, 2026-11-24. The core-tools audit deferred by [ADR 0056](0056-linux-ci-gates-merges-while-windows-runner-is-unproven.md),
run over every MCP tool after the features of ADRs 0057 to 0106 (plugins, networks, profiling, export, the build,
land and pack tools, reviews, the from-scratch rule).

## Method

Every one of the 32 tools was called over MCP stdio against a real Paper 26.3 dev server in a scratch workspace
(scaffold, setup, start, build, review, render, tour, play-test, profile, pack, export, detach, stop), with good input
and with the input an agent gets wrong (typos, missing placement data, unknown ids, regions too big, bad regexes,
removed steps and options). Each result was read for four things: is it right, what does it cost in tokens, does the
description say what the tool does, and does an error say what to do next. The guides were read against the
from-scratch rule ([ADR 0106](0106-from-scratch.md)).

## Decision: what changed

Correctness:

1. **`pack_asset` is idempotent.** A repeated `sound` call appended the same sound again (doubling its weight in the
   event), and a repeated `glyph` call added a second provider. Sounds already listed are not added again; a glyph
   provider already in the font returns the char it has.
2. **Unknown items are refused before anything is placed.** `container items=`, `frame item=` and `stand`'s equipment
   with an unknown `minecraft:` item built "successfully": the game drops the item with only a log warning. They are
   checked against the item lists `player_test` bundles (a modded namespace is not checked).
3. **The review judges ground cover as land.** A region whose only blocks above the ground are grass, flowers and
   saplings was reviewed as a building ("flat roofs", "no lights", "only 3 block types"). It now gets the land review
   alone. Leaves, mossy stone and `_block`s still count as built.
4. **`server_profile` tunes nothing from a handful of samples.** Five idle seconds gave three samples, and one of them
   in chunk loading advised lowering view-distance. Under 20 samples it says the shares are rough and gives no area
   advice.
5. **The build guide no longer describes the removed `roof` step** (ADR 0103), and its "themes to start from" (seven
   named stock palettes) are gone: the palette principles stay, the palette is the agent's.

Error guidance and results:

6. `build_batch`'s refusal for unsaid placement ends with the refused block's own example (`oak_sign[rotation=0]`),
   not a fixed stairs example.
7. A misspelt block gets close names by words with a plural or one typo forgiven (`stne_brick` suggests
   `stone_bricks`; it suggested `brick_slab`).
8. A console command that fails silently says it is an unknown command or wrong arguments, not `command failed` and
   `(no output)`.
9. An ambiguous `api_class name` says to pass the full name and how many more matched.
10. `world_summary path=` outside the region says to widen it; a build past the build height names the steps that dig
    or reach (river and path `depth=`, spheres); "nothing to place" covers `light`, `decorate`, `scatter` and `flora`
    finding no spot.
11. `model_create` names the textures it references that the pack lacks yet; `pack_asset` lists a file still to add
    as `still to add:`, not `error:` (that prefix is for a refused call), once each.
12. The review's "bare base" fix says `decorate on=ground` (bushes at the base), not `on=top` (ledges and roofs).

Quiet stderr: msc-core starts with `--enable-native-access=ALL-UNNAMED` and `-Dslf4j.internal.verbosity=ERROR`, so
MCP clients no longer show Netty's restricted-method warnings and SLF4J's no-provider warning as server errors.

From scratch: the dev proxy's MOTD (Velocity, Waterfall) is plain text, not an MSC colour.

Docs: `tools.md` states the tool-list guard as it is (21,500 characters).

Round 2 (lifecycle edge paths: failed builds, structures and copies, diffs, backups, world removal, Pterodactyl export
twice, waits, log modes, plugin ids, workspace switches, Minestom setup):

13. A failed `dev_cycle` build no longer repeats `BUILD FAILED in`, nor carries Gradle's "[Incubating] Problems report"
    line or the "(registered by plugin class ...)" suffix: the compiler errors and the failed task stay.
14. `build_undo`'s description says it also lists, saves and restores world backups; `player_test`'s says what a
    missed jump means ("hard for players too").

## Consequences

- Item ids are checked against bundled lists: a Minecraft newer than the newest list refuses its new items until that
  list is added (`ItemsTest` has the command); with no list nothing is checked.
- Not changed, found sound: argument checking, size budgets and caps, refusals of removed steps and options
  (`roof`, `tree`, `style=`, `kind=`), the server lifecycle, detach, export and backups.
- Not fixed here: an item frame with nothing behind it is summoned and dropped by the game while the result counts the
  command as run; telling that apart needs the agent to report the summon's outcome.
