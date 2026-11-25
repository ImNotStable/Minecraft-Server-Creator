# ADR 0113: The build ledger is the world's memory, and plans come first

Status: accepted, 2026-11-25. Decided by Claude; Jeremiah chose the direction in the 2026-11-25 brainstorm ("a memory
of the world ... every build having an ID ... what builds are actually here", "design before building", "better
instructions for AIs", "protect usage as much as possible") and asked for the details to be decided autonomously.

## Context

- MSC forgot every build when a session ended. `OpenReviews` (ADR 0084) knows this session's reviews only, by box,
  without names. A later session, or the same agent after its context was summarised, re-read the world with
  `world_summary` and `world_slice` to learn what it had built, and could not learn why.
- Benchmark and loop runs built towns house by house with no plan: look-alike buildings (ADR 0111) and a layout that
  grew one plot at a time. The build guide asked for a 3-line plan per build but nothing kept it or checked it.
- Jeremiah asked whether WorldGuard should hold this. WorldGuard runs only on Paper (and Folia forks), is GPLv3, and its
  regions hold flags and owners, not a description, a review status or a plan. MSC serves Fabric, NeoForge, Forge,
  Minestom and Vanilla too.

## Decision

- **Ledger.** `.msc/builds.json` in the workspace (beside the tool log, outliving sessions) holds the site plan and one
  entry per reviewed build: id (`b1`, `b2`...), name, world, box, first and last review date, status (`passed`,
  `failing: <fix names>`, or `edited since review` after a build_batch touches a passed build), and one optional line
  on why. Pretty-printed JSON, written whole (`AtomicFile`), so agents and people can read or fix it by hand.
- **Written by reviews the agent already runs.** `world_summary review=true` adds or updates the entry: under the
  same `name=` if given, else the entry with the same box or one sharing half the smaller box. It takes optional
  `name=` and `why=`; the review gains one line, `ledger: b3 smithy`. No call exists only to feed the ledger.
- **Lookup in an existing tool.** Plain `world_summary` ends with `builds here:` and up to 5 builds in the region, so
  "what stands at x,y,z" is `world_summary region="x,y,z x,y,z"`. `msc_status builds=true` lists the whole ledger with
  the planned builds no review has named yet. No new tool, and nothing is added to results when the ledger is empty.
- **Plan first, strongly suggested, not enforced.** `msc_status plan=` saves the site plan: a line for the site, then
  one per build, `name: place, size, palette as block ids, roof, what sets it apart`. A review under a planned name
  adds `plan: <its line>` and the block ids that line names that the build holds none of, so the agent compares the
  build with its own plan for two lines of output. Nothing refuses a build without a plan: a gate would cost calls on
  one-build tasks and push agents to write filler plans.
- **Guide.** build.md gains PLAN FIRST: what the plan holds, make builds differ on purpose (each line changes at least
  two of footprint, height, roof, palette and use), fit the plan to the land, review under the plan name, read the
  ledger before building in a world from another session, and that WorldGuard protection, if wanted, is set up by the
  agent from the ledger's boxes. Guidance only; nothing is generated (ADR 0106).
- **Limits** keep it cheap: names 40 characters, why lines 160, plans 4,000. An unreadable ledger is never
  overwritten; the review stands and says the ledger was not updated.

## Consequences

- A new session learns what was built, where, why and whether it passed from one `msc_status builds=true`, instead of
  slicing the world.
- `world_tour` records its review for the session's open reviews but not in the ledger (it takes no name); a
  `world_summary review=true` of the build does.
- Entries are never deleted by MSC; an agent removes a demolished build by editing the file.
