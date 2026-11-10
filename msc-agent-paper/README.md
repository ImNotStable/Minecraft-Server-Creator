# msc-agent-paper

A Paper plugin that runs inside the dev server and carries out the core's world and server commands.

**Phase:** MVP  
**Status:** Planning. No code yet.

## Scope

- Connects to `msc-core` over `msc-protocol`.
- Executes block edits in batches on the main thread without stalling the server.
- Reads regions for world views: blocks, heightmaps, entities.
- Runs commands and reports results for smoke tests.
- Applies resource packs to connected players.

## Depends on

- `msc-protocol`
- Paper API, latest stable Minecraft release.

## Stack

Java 25, Gradle Kotlin DSL.

## More

See `PLAN.md` for this module's plan, and the project plan in `msc-docs` or [the shared plan doc](https://claude.ai/artifact/DLkdACibUcy9G29V9cjmUe).
