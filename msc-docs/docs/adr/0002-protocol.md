# 0002: Core-to-agent protocol

Date: 2026-11-10. Status: accepted. Spec: [protocol.md](../protocol.md).

## Context

The core runs outside the game server and must drive any platform (Paper now; Fabric and Minestom later) through a thin agent inside the server. The core and each agent are built by different threads, so the contract has to be precise and testable on its own.

## Decision

- **Transport:** one TCP connection on the loopback interface. The **core listens and the agent connects**, because the core starts servers and already knows where it listens. The core passes `MSC_CORE_ADDRESS` (`host:port`) and `MSC_TOKEN` to the server process as environment variables.
- **Framing:** one UTF-8 JSON object per line, at most 16 MiB. Easy to log and read, no extra library.
- **Handshake:** agent sends `hello` (protocol version, token, agent info with its operation list); the core answers `welcome` or `goodbye` with an error code. Tokens are compared in constant time. (Protocol 2 replaced the token in `hello` with a mutual proof: [ADR 0043](0043-agent-link-auth.md).)
- **After the handshake** both sides are symmetric: requests with ids, responses, one-way events, ping/pong.
- **JSON library:** Gson, which Paper, Fabric (through Minecraft) and Minestom all ship, so agents need not shade a JSON library.
- **Thin agents:** agents read and write blocks, run commands and apply resource packs. Text slices, summaries, log condensing and token budgets are done in the core, so they are written once for every platform.
- **Block data:** vanilla block-state strings (`minecraft:oak_stairs[facing=north]`), sent as a palette plus indices in Y-Z-X order (X fastest, as in Sponge schematics). Writes use `-1` for "leave unchanged". One call covers at most 1,048,576 blocks; the core splits larger jobs.
- **Every write returns an edit id** that `world.undo` accepts.
- **MCP server:** the core uses the official MCP Java SDK.

## Consequences

- Adding a platform means implementing the operations in `Ops` on top of `AgentClient` and `OpRouter`; nothing in the core changes.
- Incompatible changes bump `ProtocolVersion.CURRENT`; mismatched sides refuse each other at handshake with `version_mismatch`.
