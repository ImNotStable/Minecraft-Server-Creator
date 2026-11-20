# ADR 0058: No Code Mode front end for the msc MCP server

Status: accepted, 2026-11-20. Decided by Claude under Jeremiah's hand-over of build decisions.

## Context

Jeremiah pointed at Cloudflare's "better MCP thing". That is Code Mode
([2025-09 post](https://blog.cloudflare.com/code-mode/),
[2026-02 post](https://blog.cloudflare.com/code-mode-mcp/),
[docs](https://developers.cloudflare.com/agents/model-context-protocol/guides/build-codemode-mcp-server/)).
A Code Mode server lists two tools, `search()` and `execute()`. Each takes a JavaScript function that runs in a
sandbox: `search` runs against the API's spec, `execute` calls the API. The model writes one script instead of
many tool calls, so the client never loads the full tool list and intermediate results stay out of the context.
Cloudflare measures 99.9% fewer input tokens for its own API: about 2,500 endpoints, 1.17 million tokens as
native tools, about 1,000 as Code Mode. The sandbox is a V8 isolate on Workers, the SDK is TypeScript, and the
docs still call it experimental.

## What it would save here

- **Tool list.** msc lists 35 tools in about 16,400 characters (about 4,100 tokens; `McpBridgeTest` caps it).
  Code Mode's win scales with API size, and ours is 600 times smaller than Cloudflare's. Claude Code already
  defers MCP schemas until a tool is used (`ENABLE_TOOL_SEARCH`, [agent efficiency](../agent-efficiency.md)),
  so most of those 4,100 tokens are already not paid per turn.
- **Chained calls.** The heavy sequences are already one call: `build_batch` (ADR 0015, 0019) for many edits,
  `dev_cycle` for build, deploy, restart and check, `server_setup` for jars, and output caps (`Budget`,
  `Output`) for large results. The playtest logs show long chains of builds, not long chains of small reads
  whose results the model throws away, which is where Code Mode helps most.

## What it would cost

- A JavaScript engine in the Java server (GraalJS or similar, tens of megabytes) or a separate Node process,
  plus a sandbox that keeps model-written code away from the file system, the agent link and the pack host.
  That is a new trust boundary in a server that already runs builds and starts servers.
- A typed API description generated from 35 tool schemas and kept in step with them, and a second way to
  call every tool that tests would have to cover.
- Worse errors: one failed script hides which step failed, where a tool call now says exactly which.

## Decision

Don't build Code Mode into msc. Keep reducing the tool list and merging chains into single tools, as ADR 0019
and 0057 do.

Revisit if the tool list passes about 50,000 characters, if Claude Code gains client-side code execution over
MCP tools (then msc gets it for free and needs no change), or if playtests show sessions spending most tokens
on chains of small reads.
