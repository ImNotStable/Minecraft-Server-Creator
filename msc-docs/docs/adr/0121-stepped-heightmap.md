# ADR 0121: A stepped heightmap for terrain overviews

Status: accepted, 2026-11-26, decided by Claude at Jeremiah's request (token sweep of MSC tool output).

## Context

The tool-call logs of the last week's bench and loop runs (71 runs, 12 models, 5,316 calls, 4.06M result characters)
show where MSC's own results spend tokens: `world_summary` 32% (mostly identical re-reviews, which ADR 0119 now
answers in a few lines), `world_heightmap` 15%, `msc_status` 15%, `build_batch` 10%, `world_render` 10%. Results
are plain text already, without decoration, and capped by `Budget`.

`world_heightmap` is the heaviest single result: 3,488 characters on average and up to 22,954, because models raise
`maxChars` to see a whole site (one character per column, 161 x 141 columns). Planning a site needs the shape of the
land, not every column.

## Decision

`world_heightmap` takes `step` (1 to 16, default 1): one cell per step x step columns, holding the highest of them.
The header still gives the true min, max and mean of every column and says the step. A cut result now suggests
`step=4` as well as a smaller area, and the build guide says to look at a wide area with `step=4` first, then at
step 1 where you build.

## Consequences

- The 161 x 141 overview that cost 22,954 characters costs about 1,500 at `step=4`.
- Nothing changes for a call without `step`. The agent side of the protocol is unchanged; core samples the grid.

## Addendum, 2026-11-27: shorter block counts, setup warnings kept

- `build_batch`'s block counts leave out `waterlogged=false`, every block's default and the bulk of a long state in
  the logs (`spruce_slab[type=bottom,waterlogged=false]` is now `spruce_slab[type=bottom]`). `waterlogged=true` and
  every other property stay.
- `msc_status` keeps listing setup warnings (wrong workspace, stale install) on every call, as #637 decided: they cost
  about 2% of result characters in the logs, and a warning that stops showing is easy to miss while it still applies.
