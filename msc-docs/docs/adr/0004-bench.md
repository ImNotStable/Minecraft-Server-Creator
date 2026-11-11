# 0004: Benchmark design and the core's tool log

Date: 2026-11-10. Status: accepted. Module: [msc-bench](../../../msc-bench/README.md).

## Context

The plan measures quality, speed and tokens with and without the toolset, and sets targets only after a baseline run. The bench must work before msc-core exists (baseline), must judge both modes with the same checks, and must not depend on core's code.

## Decision

- **Agent:** Claude Code run headless (`claude -p --output-format json`). Its result gives turns, token usage (input, cache write, cache read, output) and cost for both modes. The command is configurable in `msc-bench/bench.json`.
- **Modes:** `baseline` runs with `--strict-mcp-config` and no MCP servers; `toolset` adds an MCP config that starts msc-core. Everything else (fixtures, prompt, checks, time limit) is identical.
- **Checks are toolset-independent:** files, build commands, PNG and zip inspection, and the live server over vanilla RCON (`execute if block` for blocks). No check uses msc-core or the agent plugin.
- **World tasks:** the runner starts the Paper server and runs setup commands before the agent, so both modes start from the same world. In toolset mode the server gets `MSC_CORE_ADDRESS` and `MSC_TOKEN`, and core listens there and accepts the agent that connects (agreed with msc-core).
- **Paper jar:** found in the jar caches (msc-bench `.cache/`, then msc-core's `.msc/cache`) or downloaded from PaperMC's Fill API, so a run needs no setup. Toolset workspaces get the jar pre-seeded in `.msc/cache` and core runs with `MSC_SERVER_DIR=<workspace>/server`, so both modes use the same server folder and neither pays for a download.
- **Metrics:** wall time of the agent process, pass (all checks), turns, total tokens, output tokens, cost, tool calls. Reports use medians per task.
- **Tool log (format v1):** msc-core appends one JSON object per finished MCP tool call to the file named by `MSC_TOOL_LOG` (default `<workspace>/.msc/tool-calls.jsonl`), flushed per line. Fields: `v` (1), `ts` (ISO-8601 finish time), `tool`, `ok`, `durationMs`, `argsChars`, `resultChars`, `resultTokens` (ceil(resultChars/4) in v1), optional `error`. Readers ignore unknown fields and skip lines with another `v`, so core can add fields freely; incompatible changes bump `v`.
- **Safety:** the agent runs with `--dangerously-skip-permissions` because runs are unattended; each attempt has its own folder, and the README says to run the bench only where that is acceptable. The Minecraft EULA is never accepted by default (`server.acceptEula`).

## Consequences

- The baseline can run as soon as the EULA setting allows a server; toolset runs need core and the agent jar built.
- Token counts include the model's whole context, so they capture both smaller tool results and fewer turns.
- Targets are set from the first baseline and recorded in the plan's Decisions table.
