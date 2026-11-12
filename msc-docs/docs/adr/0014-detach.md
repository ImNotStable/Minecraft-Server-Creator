# ADR 0014: Detach the dev server so the user can play

Status: accepted, 2026-11-12. Decided by Claude under Jeremiah's hand-over of build decisions.

## Context

The Lumen Siege playtest built a full game through MSC, then had to leave it running for Jeremiah to play. The dev server stopped when the MCP process exited, because the core's shutdown hook stops it. The resource pack host is an HTTP server inside the core, so it stopped too, and players who had been sent the pack could no longer download it. The playtest had to run both by hand as standalone processes.

## Decision

1. **`server_detach` is an explicit tool.** The default stays the same: closing Claude Code stops the server. A forgotten server holds the world lock and the ports, so surviving the session has to be something the AI asks for, normally because the user wants to play.
2. **The detached server is the server already running.** It is not restarted. `close()` skips it, and `.msc/server.pid` stays, so the next core's `server_start`, `dev_cycle` or `server_stop` finds it and stops it over RCON (the world is saved), exactly as it does for a server orphaned when Claude Code was killed. Checked on Paper 26.3: the server keeps running after the core exits, with its console pipe closed. The agent tries to reconnect 8 times and then goes quiet.
3. **The pack host moves to a standalone process on the same port.** The pack URL players already have stays valid. The process (`PackHost`, run with the core's own class path) serves whatever `.msc/build/pack.zip` holds, so a `pack_build` later in the same session needs no restart. Its pid goes in `.msc/pack-host.pid`, and the next session stops it together with the server. If it cannot start, the core keeps serving the pack and says so.
4. **No reattach.** A later session cannot take over the detached server's console or agent, because the agent's address is random per core. It stops the old server and starts a new one, which takes about 10 s.

## Consequences

- One more tool, so the tool-list guard goes up to 14,600 characters.
- World tools and `server_command` on a detached server work only until the session ends.
