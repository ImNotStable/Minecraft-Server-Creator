# ADR 0010: Minestom agent and server template

Status: accepted, 2026-11-11. Decided by Claude under Jeremiah's hand-over of build decisions.

## Context

Minestom is a library, not a server jar: there is no plugin loader and no vanilla gameplay, and a Minestom server is the user's own `main` method. The core's tools must work against it the same way they work against Paper ([ADR 0006](0006-paper-agent.md)). The newest Minestom build, `2026.10.07-26.2`, speaks Minecraft 26.2, one release behind Paper 26.3.

## Decisions

1. **The agent is a library the server calls once.** `MscAgent.start()` after `MinecraftServer.init()`; it connects exactly like the Paper agent (`MSC_CORE_ADDRESS` / `MSC_TOKEN`, or the `msc.core.address` / `msc.token` properties, with retry and reconnect) and stays idle when neither is set, so it can ship in production builds. No Java agent, reflection or classpath tricks.
2. **It ships as one shaded jar inside the template.** `msc-agent-minestom` shades `msc-protocol` (Gson comes from Minestom). `msc-templates` copies the jar into the `minestom-server` template as `libs/msc-agent-minestom.jar`, and the scaffolded project depends on it with `implementation(files(...))`. Nothing has to be published to a Maven repository. A scaffolded project keeps the agent version it was created with; re-copy the jar to update.
3. **Behaviour matches the Paper agent.** All nine `Ops` are implemented with the same limits, error codes, skip-unchanged edits, in-memory undo (about 8 full-size edits, undo returns a new id), physics off for fills, void air outside the build height, and the same events. The platform-free classes (`EditHistory`, `PackedPos`, `CoreAddress`, `SlicedJob`, `MainThread`) are copies of the Paper agent's; a shared agent-common module is worth it once the Fabric agent is a third copy.
4. **World names are a tag.** Minestom instances only have UUIDs. `MscAgent.name(instance, "world")` stores the name in the `msc:world` tag; unnamed instances are addressed by UUID. `server.info` lists named worlds first.
5. **World work runs on the global scheduler at tick start**, in 20 ms slices per tick like Paper. Those tasks run before instances tick, so they never race instance threads. Chunks a call touches are loaded (or generated) first, off the tick thread.
6. **Commands are the server's own.** `server.command` runs through Minestom's `CommandManager` with a capturing console sender. Minestom has no vanilla commands, so `/time` or `/gamemode` answer `success=false` with `Unknown command: time`. World tools do not depend on commands, so every tool still works; tools that send vanilla commands get that one-line answer.
7. **TPS is measured by the agent** (Minestom has no counter): one sample per tick over the last minute, capped at 20.
8. **Heightmaps are scanned**, starting at the top of each chunk's highest non-empty section, so the answer is exact and cheap for normal terrain. An empty column reports `minY - 1`.
9. **Template `minestom-server`**: a standalone Gradle KTS project (own wrapper and catalog) with a flat world like vanilla superflat, creative spawn, a `/hello` command, a stdin console (`stop` stops cleanly, other lines run as commands, which is how msc-core stops servers), a `Done (...)!` line the core's readiness check matches, `slf4j-simple` logging, a runnable shaded jar (`build/libs/<projectId>-<version>.jar`), plain JUnit and Minestom `@EnvTest` tests, and a `CLAUDE.md` that spells out what Minestom does not do. No Mojang jar and no EULA step.
10. **Tests use Minestom's own test environment** (`net.minestom:testing`): real instances and a real server process without a network, with `-Dminestom.inside-test=true`. 28 tests, including a smoke test with a real `CoreServer` over a socket.

## Checked (2026-11-11, cloud container, Java 25)

The scaffolded template builds and passes its own tests. Its jar was started with a protocol core attached: handshake, `server.ready`, `server.info` (`minestom 2026.10.07-26.2`, MC 26.2, `[world]`, 20 TPS), a 983,040-block fill and its undo, read, heightmap, the `hello` command, an unknown command, `stop` on stdin and `server.stopping` all behaved as specified, and the server exited with code 0.

## Consequences

- Edits cost about 4.5 µs per block in `Instance#setBlock`, so a full 1M-block fill takes about 12 s of wall time at 20 TPS (Paper is faster). `AbsoluteBlockBatch` could cut this but changes physics and undo handling; left for when it matters.
- msc-core still starts Paper only. Running a Minestom project through the core needs core work: scaffold `minestom-server`, build with Gradle, then start `java -jar build/libs/<jar>` in the server directory (the existing `serverCommand` config can already do the last step), skip the Paper download and the EULA check, and index the Minestom jar for `api_search`.
- Minestom builds trail Minecraft releases; the version comes from the catalog (`minestom`), and the template's Minecraft version is read from its suffix.
