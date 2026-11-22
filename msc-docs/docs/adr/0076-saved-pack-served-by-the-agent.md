# ADR 0076: The agent serves the saved pack when the server starts without MSC

Status: accepted, 2026-11-22. Decided by Claude, on Jeremiah's ask. Amends [ADR 0017](0017-workspace-and-pack-lifetime.md)
and [ADR 0051](0051-publish-the-pack-from-one-state.md) for servers started by hand.

## Context

After Antigravity's Shattered Meridian run (ADR 0075) the agent ended with `server_stop`, and Jeremiah started the
server himself to look around. Every custom item was the purple-and-black missing texture. The logs show why: at 06:32,
with MSC running, the pack came with the join; at 06:35 the server started with no MSC session (`MSC_CORE_ADDRESS is
not set; agent is idle`) and `resource-pack=` empty, because only an MSC session or a detached pack host serves the
pack (ADR 0017, 0051, 0053). `server_detach` covers a session still open, not a server started later by hand.
Jeremiah: a finished server must send its pack without MSC or the MCP server running.

## Decision

1. **The core keeps the latest built pack beside the server**: every publish of a build copies the zip to
   `<server>/msc-pack/pack.zip` and writes the address players reach it on (the host of the pack's URL) to
   `msc-pack/host`. Best effort: a failure only costs a hand-started server its pack.
2. **The agent serves it when idle.** On start, an agent with no MSC session (no core address) and a
   `msc-pack/pack.zip` serves that zip over HTTP on a free port of that host, and sends it to each player who joins,
   exactly as it sends a pack MSC applies (all agents already send `currentPack()` on join). A session that connects
   later applies its own pack, which replaces this one. `SavedPack` in msc-agent-common, called by the Paper, vanilla
   (Fabric, Quilt, NeoForge, Forge) and Minestom agents; plain `java.base` sockets on daemon threads, so it works in the
   module layers of NeoForge and Forge and never holds the JVM open.
3. **server.properties is unchanged.** The agent sends the pack itself; ADR 0051's offer rules stay as they are.

## Consequences

- A dev server started by hand, after any MSC session, sends the last pack MSC built.
- The pack is not required on such a start (the agent does not know the session's choice); players can decline it.
- A server shipped without the msc agent (a real deployment) still needs a pack host of its own: a URL in
  `server.properties` or the plugin's own sending. Not done here: msc-stdlib could carry a pack host for that.
