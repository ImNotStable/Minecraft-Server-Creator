# ADR 0015: One build language for batches, shapes, mixes and stamps

Status: accepted, 2026-11-12. Decided by Claude under Jeremiah's hand-over of build decisions.

## Context

Both playtests (Lumen Siege and Gearfall, see `playtest-findings.md`) wrote a Python script that generated 50 to 400 `build_fill` and `build_set` calls, mainly for circles, block mixes and repeated pieces. Three of Lumen Siege's eleven structures failed on a renamed block after earlier calls had already placed blocks. Items 7 to 11 of the findings ask for a batch call, round shapes, weighted block mixes, stamping with repeat and rotate, terrain bands, and checking block names before building.

## Decision

1. **One tool, `build_batch`, with a line language.** One step per line: `fill`, `set`, `cylinder`, `ring`, `disc`, `sphere`, `dome`, `line`, `bands` and `structure` (text in place, ending with `end`, or `file=`). Separate `build_shape` and `build_stamp` tools were written first and dropped: they added about 3.5k characters to the tool list that every conversation pays for, and a one-line batch does the same job. The tool list guard goes to 16,500 characters.
2. **Steps draw onto a canvas in the core.** The core computes every block, later steps overwriting earlier ones, in one dense array over the batch's bounds (at most 8,388,608 blocks, like `build_fill`). It then writes the used box in protocol-sized `world.write` grids, skipping empty parts. No protocol or agent change was needed, so the tools work on Paper, Fabric and Minestom alike.
3. **One undo id per call.** The agent edit ids of every part are kept under one core edit id. If a part fails, the parts already written are undone, so a batch is placed whole or not at all.
4. **Block names are checked before anything is placed, using the existing protocol.** Every agent parses a write's whole palette before changing a block. The core first sends an all-unchanged write of one column spanning the batch's height, carrying the full palette; that checks every name and the build height and places nothing. On a bad name, the core probes each block alone, so the error lists every bad name with the agent's suggestions. Each grid written afterwards also carries the full palette. `dryRun=true` stops after the check and reports counts and bounds; without a server it still counts, and says names were not checked.
5. **Weighted mixes are deterministic.** `grass_block:78,moss_block:10` picks per position from a hash of x, y, z and `seed=N`, so a dry run, a redo after undo and a repeat give the same layout. Weights are relative; commas inside `[...]` belong to block properties.
6. **Shapes are cell-centre tests.** A cell is inside a circle or ball when its centre is closer than radius + 0.5. Hollow shapes peel cells that have a face neighbour outside, `thick` times, so walls have no gap a player could walk through. `inner=R` cuts a hole; `bands` assigns each column to the first radius it is inside, with an optional smooth `jitter` per edge, so land, beach and water meet without gaps.
7. **Stamps turn block states with the structure.** Quarter turns clockwise from above; `facing`, `axis`, `rotation`, rail and jigsaw directions and fence or wall side connections turn too. `face=X,Z` turns each copy so its north side (first text row) points at the target; `anchor=center` places footprints by their centre. Mirroring is not supported yet.
8. **Room shapes match `build_fill`.** `fill ... hollow|outline|walls` uses the same names as the `shape` argument added to `build_fill` in #22.

## Consequences

- An AI can build an island with a keep, towers facing the centre, a ring wall, a bridge with lamps and a tree in one call (checked on Paper 26.3: 5,496 blocks, one undo).
- Steps with `replace=` read the world over their bounds once.
- The core holds 4 bytes per block of the batch's bounding box while it builds: 32 MB at the limit.
