# msc-core

The MCP server the AI talks to. It runs servers, condenses their output, answers API questions, and drives building, world views and textures through the agents.

**Phase:** MVP  
**Status:** MVP implemented (0.1): 24 MCP tools, tested against a fake agent. Not yet run against a real Paper server.

## Scope

- MCP server exposing the tool catalogue.
- Server lifecycle: scaffold from `msc-templates`, download jars, start, stop, build, deploy and hot-restart in one call.
- Condensed logs: errors, stack traces trimmed to project frames, duplicates collapsed.
- Local API index per platform: signatures, events and registries in a few lines.
- World building: set, fill, replace, copy/paste, undo, schematics, and a compact text structure format.
- World views: layer slices as character grids, heightmaps and region summaries, each with a size budget.
- Basic textures: palette-grid to PNG, palette checks, resource pack build and serve.
- Token and timing logging for every tool call.

## Run

`./gradlew :msc-core:run` starts the MCP server on stdin/stdout. Configuration is by environment variables (`MSC_WORKSPACE` and others); see the tool catalogue in `msc-docs/docs/tools.md`.

## Layout

- `tool/`: tool registry, argument parsing, result budgets. `mcp/McpBridge` is the only class that uses the MCP SDK.
- `agent/AgentHub`: the core's end of `msc-protocol`.
- `server/`: server process, Paper download, build runner, log condensing.
- `api/`: API index read from class files.
- `world/`: text structure format, views, edits with undo, schematics.
- `texture/`: palette-grid textures and the resource pack.
- `metrics/ToolLog`: per-call log for `msc-bench`.
- Tests use `FakeAgent` (an in-memory world) and fake server and build processes.

## Depends on

- `msc-protocol`
- `msc-templates`
- An agent such as `msc-agent-paper` for world tools.

## Stack

Java 25, Gradle Kotlin DSL.

## More

See `PLAN.md` for this module's plan, and the project plan in `msc-docs` or [the shared plan doc](https://claude.ai/artifact/DLkdACibUcy9G29V9cjmUe).
