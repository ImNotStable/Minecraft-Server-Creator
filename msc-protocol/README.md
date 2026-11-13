# msc-protocol

The shared message types and local socket transport between `msc-core` and the in-server agents, published as one versioned library.

**Phase:** MVP  
**Status:** Built. Protocol version 1; the overview is `msc-docs/docs/protocol.md`.

## Scope

- Request and response types for server control, world edits and world views.
- A local socket transport with framing and an auth token. Ping/pong messages exist for round-trip checks; nothing sends them on a timer, so a hung peer is noticed only when a call times out.
- A version handshake so the core and an agent refuse mismatched versions.
- No platform code: plain Java so Paper, Fabric and Minestom agents can all depend on it.

## Depends on

- Spec in `msc-docs`.

## Stack

Java 25, Gradle Kotlin DSL.

## More

See `PLAN.md` for this module's plan, and the project plan in `msc-docs` or [the shared plan doc](https://claude.ai/artifact/DLkdACibUcy9G29V9cjmUe).
