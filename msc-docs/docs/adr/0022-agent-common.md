# ADR 0022: Shared agent code in msc-agent-common

Status: accepted, 2026-11-12. Decided by Claude under Jeremiah's hand-over of build decisions. Roadmap round 2 item R2.

## Context

The Paper agent came first (ADR 0006); the Fabric (ADR 0009) and Minestom (ADR 0010) agents copied it and swapped the
server API. By v0.3.0 the three agents held about 1,000 duplicated lines: six files identical apart from the package
(`AgentRoutes`, `CoreAddress`, `EditHistory`, `MainThread`, `PackedPos`, `SlicedJob`), eight more that differed only in
the block state type or one world call (`ChangeSource`, `UndoRecord`, `EditJob`, `ReadJob`, `HeightmapJob`,
`WorldOps`, `ServerOps`, the core connection), and three copies of the same tests. Every protocol change (sign text in
#41, entities in #25) had to be made three times, and the copies had started to drift (world lists in errors, log
wording, timeout messages).

## Decisions

1. **New module `msc-agent-common`**, plain Java, depending only on `msc-protocol`. Package
   `io.github.imnotstable.msc.agent.common`. Each agent depends on it with `implementation` and bundles it like
   `msc-protocol`: shaded into the Paper plugin and the Minestom library jar, nested (jar-in-jar) in the Fabric mod.
2. **The operations are generic over the platform's block state type `S`** (`BlockData`, `BlockState`, `Block`):
   `WorldOps<S>`, `EditJob<S>`, `ReadJob<S>`, `UndoRecord<S>`, `EditHistory<S>`, `ChangeSource<S>`. `ServerOps`,
   `AgentRoutes`, `HeightmapJob`, `PackedPos`, `CoreAddress`, `SlicedJob`, `MainThread` (with a shared `await`) and
   `AgentLink` (connect, backoff, reconnect, ready and player events) need no type parameter.
3. **A platform supplies four adapters**, the shape the Fabric agent already had (it was written for testability):
   - `AgentWorlds<S>`: world names, lookup by name, block state parsing (with each platform's own error help).
   - `AgentWorld<S>`: one world's blocks, surface heights, entities and signs, plus hooks the platforms differ on:
     `sameState` (Fabric compares by identity, Minestom by state id, Paper by `equals`), `paletteKey`, `peek` (Minestom
     reads without NBT), and `loadArea`/`loadPositions` (only Minestom loads chunks before a job; no-ops elsewhere).
   - `AgentServer`: platform id and versions, worlds, players, running a command, sending a resource pack.
   - `MainThread`: the platform's scheduler (Bukkit scheduler, Fabric tick hook, Minestom scheduler) stays per agent.
4. **Logging goes through a four-method `AgentLog`**, so common code needs no logging library; Paper adapts its JUL
   logger, Fabric slf4j, Minestom `System.Logger` (`AgentLog.of`).
5. **Shared constants** (`AgentDefaults`: 20 ms tick budget, 2 min op timeout, 10 s connect timeout, about 8 full-size
   edits of undo) live in one place.
6. **Tests follow the code.** The operations are tested once in msc-agent-common against an in-memory world with
   string block states (`MapWorld`) and a fake server; the copied `EditHistory`, `PackedPos` and `CoreAddress` tests
   moved there. Each agent keeps the tests that exercise its adapters on its real or mocked server (MockBukkit,
   Minestom's test environment, Minecraft bootstrap for Fabric) and its smoke test against a real `CoreServer`.

## Consequences

- One protocol change is now one edit plus, at most, one adapter method per platform. A fourth platform (Sponge, a
  proxy) needs only the adapters.
- Small behaviour alignments that come with the merge: every agent's "no world named" error lists the worlds (Fabric and
  Minestom did; Paper did not); every main-thread timeout says "main thread did not finish"; heightmap jobs check the
  deadline every 64 columns (Minestom's value; a check costs nanoseconds); Fabric's undo palette uses `equals`, which
  is identity for its interned block states.
- The agents' public API is unchanged: `MscAgentPlugin`, `MscAgentMod` and `MscAgent.start()`/`MscAgent.name(...)`
  (used by the Minestom templates). `BukkitMainThread` and `SchedulerMainThread` now implement the common
  `MainThread`.
- `settings.gradle.kts` includes the new module (agreed with the foundation thread); no catalog or build-logic change.
