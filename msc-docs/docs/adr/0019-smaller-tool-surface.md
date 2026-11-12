# ADR 0019: A smaller tool surface

Status: accepted, 2026-11-12. Decided by Claude under Jeremiah's hand-over of build decisions.

## Context

Every conversation pays for the whole MCP tool list and the server instructions before the first call. By v0.2.0 the list had grown to 36 tools and 18,500 characters (about 4,600 tokens), plus 1,500 characters of instructions. Most growth was additive: each playtest added a tool and none removed one.

An audit against the three playtests ([findings](../playtest-findings.md); Lumen Siege 148 calls, Tidewright 76) found:

- **Six build tools did one job.** Since ADR 0015, `build_batch` has `fill` (with the same `hollow`, `outline` and `walls` shapes and `replace=`), `set`, and `structure` with inline text or `file=` (`.schem` or text). That made `build_fill`, `build_set`, `build_structure` and `structure_load` duplicates with their own schemas and their own, older code path in `WorldService`. Tidewright used only `build_batch` for 35k blocks. `build_copy` was the one thing `build_batch` could not do.
- **`msc_stats` was a second status tool** with no arguments.
- **The server instructions named two tools that never existed** (`build_shape`, `build_stamp`, from an early ADR 0015 draft), and the `build_batch` description pointed at `build_fill` and `build_set`.
- `build_batch` results always repeated a `blocks:` line and a `(N placed)` count, even for a one-block fill.

## Decision

1. **`build_batch` is the one build tool.** `build_fill`, `build_set`, `build_structure`, `build_copy` and `structure_load` are removed. The text structure format moves into the `build_batch` help, where the `structure` step needs it. The name stays `build_batch`: playtest transcripts, the wiki, ADR 0015 and the scaffolded projects' habits already use it, and a rename would cost more than it saves.
2. **A `copy` step replaces `build_copy`**: `copy X,Y,Z X,Y,Z to=X,Y,Z`, taking every `structure` option, so a copy can also be turned or repeated. It reads the world when the call is parsed, before anything is written, so source and target may overlap.
3. **`msc_stats` becomes `msc_status stats=true`.**
4. **Shorter shared text**: the `world` argument reads "World. Default: the main world"; the server instructions are rewritten to name only real tools and drop what the tool descriptions already say (1,500 to 1,060 characters).
5. **Leaner build results**: `edit eN: C blocks changed, region R`. `(N placed)` only when it differs from the changed count, `blocks:` only when more than one kind was placed.
6. **Dead code goes with the tools**: `WorldService.fill/set/write/copy`, its shape splitting and `FillShapeTest`. Their behaviour stays covered through `build_batch` in `WorldToolsTest` (rooms, slice round trip, structure errors, undo, split large fills, copy, schematics).

## Consequences

- 30 tools, 15,750 characters of tool list (down 15%), about 700 fewer tokens per conversation before instructions. The tool-list guard in `McpBridgeTest` drops from 19,000 to 16,000 characters.
- A big solid fill now goes through `world.write` grids instead of the agent's `world.fill`. Same 8,388,608-block limit; more bytes on the local socket, but one code path and the name check before any block is placed.
- The protocol ops `world.fill` and `world.setBlocks` are no longer called by the core. Removed in a follow-up (see the addendum).
- Old prompts or notes that call `build_fill` get "unknown tool". The tool list the client fetches shows `build_batch`, so an AI recovers in one call.

## Next candidates, not done here

- `texture_create`, `texture_generate`, `texture_recolor` and `model_create` overlap in their `model` argument; a single `texture` tool with a `from` mode could save about 1,000 characters, but texture work has had no playtest data yet.
- `server_start`, `server_stop` and `server_detach` are short; merging them saves little.
- Response sizes: measure `dev_cycle`, `server_start` and `player_test` results from the bench tool log before trimming.

## Addendum 2026-11-12: `world.fill` and `world.setBlocks` removed

The follow-up PR removes both ops from `msc-protocol`, the Paper, Fabric and Minestom agents and the test fakes, together with what only they used: the agents' region and list cursors, the `onlyReplace` check in each `EditJob`, and the Fabric and Minestom `BlockStates.Filter`. `world.write` is the one block-writing op; `replace=` filtering happens in the core (`Canvas.mask`), before anything is sent. Agents now list 8 operations.

The protocol version stays 1. Core and agents ship in one install (`installMsc`, release zips), so a new agent never meets an old core in practice; if it did, the old core's `build_fill` would get `unknown_op` from the agent, which names the problem.
