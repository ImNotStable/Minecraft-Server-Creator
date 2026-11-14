# ADR 0006: Paper agent

Status: accepted, 2026-11-10. Decided by Claude under Jeremiah's hand-over of build decisions. Amended 2026-11-14 (see the end).

## Context

`msc-agent-paper` carries out the core's operations (`Ops` in msc-protocol) inside a Paper 26.3 server. Edits can touch up to 1,048,576 blocks per call, and the AI's builds must not stall the dev server or leave it in a state that cannot be rolled back.

## Decisions

1. **The agent implements exactly the `Ops` catalogue.** Copy/paste, schematics and text structures are composed in the core from `world.read` and `world.write`, as ADR 0002 says. The agent stays a thin, fast executor.
2. **Main-thread work runs in sliced jobs.** Request handlers run on virtual threads; world access is a `SlicedJob` that the Bukkit scheduler steps once per tick with a 20 ms budget, so a full-size edit spreads over a few ticks and the server stays at 20 TPS. A call times out after 2 minutes (`timeout`).
3. **Edits skip blocks that are already in the target state.** `changed` counts real changes only, and the undo record holds only those blocks.
4. **Undo is in memory, bounded by blocks.** Each edit stores its replaced blocks (packed positions plus a state palette). The history keeps about 8 full-size edits (8,388,608 blocks) and drops the oldest first. Undoing an edit consumes it and returns a new edit id, so an undo can itself be undone (redo). Edit ids carry a random per-run prefix (`k3f9-12`) so an id from an earlier server run never matches.
5. **Physics off by default.** `world.fill` never applies physics; `world.write` and `world.setBlocks` follow `updatePhysics`. Builds should land exactly as described, without sand falling or torches popping off.
6. **Reads outside the build height return `minecraft:void_air`; writes there fail with `invalid_params`.** Unknown worlds give `not_found`, oversized calls `too_large`, unknown block states `invalid_params`.
7. **Commands run as Paper's feedback sender** (`Server#createCommandSender`), which captures the chat feedback a command sends. Output a plugin only logs to the console is not captured.
8. **Connection settings** come from `MSC_CORE_ADDRESS` / `MSC_TOKEN`, or the system properties `msc.core.address` / `msc.token` for hand-started servers and tests. The agent retries the connection with backoff and reconnects if the core drops it.
9. **One shaded jar** bundles msc-protocol; Gson comes from Paper.

## Consequences

- The core must split jobs larger than one call and should expect `changed` to be lower than the region volume.
- Undo history is lost when the server restarts. Persistent history is a post-MVP option.

## Amendment, 2026-11-14: statements later changes made untrue (#383)

Found by reading every ADR against the code during the campaign. The original text above is kept as decided; what is true now:

- Decisions 1 and 2: the operations, sliced jobs and undo live in msc-agent-common since [ADR 0024](0024-agent-common.md); the Paper agent supplies adapters. The Bukkit scheduler steps every job through the shared `JobQueue` from one task per tick, so all jobs in flight share one 20 ms budget (#247), not 20 ms each.
- Decision 5: `world.fill` and `world.setBlocks` were removed ([ADR 0019](0019-smaller-tool-surface.md), addendum). `world.write` is the only block-writing op and follows `updatePhysics`, which `build_batch` sends only with `physics=true` (off by default).
- Decision 9: the shaded jar bundles msc-agent-common as well as msc-protocol.
