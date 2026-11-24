# ADR 0100: World, build and player tools on Folia

Status: accepted, 2026-11-24. Decided by Claude, from Jeremiah's open list (ADR 0045 and 0088 left it "not done").

## Context

ADR 0045 ran the Paper agent on Folia for server operations only. Folia has no main thread: loaded chunks are grouped
into regions, each ticked on its own thread, and a chunk may only be touched from the thread of the region that owns it.
The agent's world jobs (`EditJob`, `WorldReadJob`, `HeightmapJob`, entity reads) assumed one thread that owns every
chunk. Measured on Folia 26.3 build 27 on omen before deciding:

- Console commands run on the global region thread. `/summon`, `/fillbiome`, `/place feature` and `/setblock` work
  there for loaded chunks; `/data`, `/save-all` and `execute ... run` are not registered on Folia.
- `Bukkit.dispatchCommand` from a region thread throws "Dispatching command async", so the Paper agent's
  `/data`-based block entity data (ADR 0042) cannot work on Folia even where `/data` exists.
- `Bukkit.isOwnedByCurrentRegion(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ)` tells whether the calling region
  owns a whole area; Folia merges neighbouring regions once their chunks are loaded next to each other.

## Decision

1. **Jobs carry their chunks.** `MainThread` gains `call(task, ChunkArea)` and `run(job, ChunkArea)`, defaulting to
   the plain forms, and `WorldOps` passes the area of every read, heightmap, entity read, write and undo. No other
   agent changes.
2. **`RegionThreads`** (Paper agent, Folia only) runs a world job on the region scheduler at the area's middle chunk,
   one slice per tick of that region within the agent's tick budget, and steps it only while
   `isOwnedByCurrentRegion` says that region owns the whole area. Until it does (a merge in progress) the job waits a
   tick, up to 200 ticks, then fails with `too_large` ("work on a smaller area") without touching a chunk. A job whose
   caller timed out changes nothing more. Server-wide work still goes to the global region thread.
3. **`FoliaChunks`** loads the area's chunks asynchronously and adds a plugin chunk ticket to each on its own region
   thread before the job, so they stay loaded and merge into one region. Reads release the tickets when done; edits,
   whose caller has no hook afterwards, release them after the op timeout plus a minute (a shortcut comment says when
   to add a release hook).
4. **`FoliaBlockNbt`** replaces `/data` on Folia: the block state's own NBT (`getSnapshotNBT`, `loadData`) and
   Minecraft's SNBT parser by reflection, merged as `/data merge` does, on the region thread. `DataCommands` and it
   share a `BlockNbt` interface. Reflection on server internals is a shortcut, recorded in the code: a rename breaks
   block entity data on Folia only, with a clear error.
5. **Backups while Folia runs** copy the world without `save-all` (Folia has none) and say so, as Minestom's do:
   "this backup is the world as its regions last autosaved it (edits since are not in it; build_undo still undoes
   each edit)". Backups of a stopped server are complete.
6. `AgentRoutes.serverOnly` is removed; the setup note and the docs say the world tools work on Folia.

## Verified on omen (Folia 26.3 build 27, the built agent and core over MCP)

`server_setup platform=folia`, `server_start`; `build_batch` fills of 61x61 and 201x201 (13x13 chunks), a sign with
text and a chest with items (both read back with block data by `structure_save`), `entity`, `biome` and `feature`
steps; `world_summary` with entities, `world_slice`, `build_undo` of every edit, a backup with the note, and
`player_test` joining, pathing and reporting. Unit tests: `RegionThreadsTest` (thread, slices, merge wait, giving up,
timeouts, failures) and the core's backup note.

## Consequences

- Every world, build and player tool works on Folia as on Paper, each job on one region.
- An area whose chunks never come under one region (far apart, larger than Folia's region limits) fails with a message
  to work on a smaller area, instead of touching another region's chunks.
- Not done: splitting one large job across several regions (not needed while loaded neighbours merge), and a flushed
  live backup on Folia.
