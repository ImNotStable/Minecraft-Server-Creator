# ADR 0041: Whole-world backups and rollback on `build_undo`

Status: accepted, 2026-11-14, decided by Claude.

## Context

Every agent keeps a per-edit undo history (`EditHistory`, block-entity NBT included), and the core keeps its edit ids
(`WorldService`). That protects one edit at a time, and only while it lasts:

- The agents keep the last `AgentDefaults.UNDO_BLOCKS` changed blocks (8 × 1,048,576 = 8,388,608); older parts are
  dropped and `build_undo` reports them as "no longer kept".
- Nothing survives a restart: the history lives in the server's memory, the edit ids in the core's. A `dev_cycle`
  restart, a crash mid-edit, or a new session leaves no way back.
- A bad big `build_batch` (a structure paste, terrain `bands`, a 100×100 fill) can be undone only while it is the
  recent past, and edits outside MSC (a player, a plugin's own code) never could be.

Where each platform keeps its world, all directly in the server folder (`run/`):

| Platform | World folders |
| --- | --- |
| Paper, Purpur | `world`, `world_nether`, `world_the_end`, each with `level.dat` |
| Fabric, Quilt, NeoForge, Forge | one `world` with `level.dat`, the other dimensions inside it |
| Minestom | what the project's code saves. The `minestom-*` templates use `AnvilLoader` on `run/world` (region files, no `level.dat`) and save **only on stop**; a project that sets no chunk loader persists nothing |

The tool list is at its guard (30 tools, under 16,000 characters), so no new tool.

## Decision

1. **Backups are plain folder copies** in `<workspace>/.msc/backups/<yyyyMMdd-HHmmss>-<label>/`, one sub-folder per
   world folder. A world folder is any folder directly in the server folder holding a `level.dat` or a `region/`
   folder, which covers every layout above without a per-platform list. Copied to `<name>.part` and renamed when
   complete, so a crash never leaves a backup that looks whole. No zip: copying is fastest, and restoring is a rename.
2. **Kept: the newest 5, and fewer once they pass 2 GiB together**; the newest, and the one a rollback restores, always
   stay.
3. **Consistent copies.** With the server running and an agent connected, the core runs `save-off`, `save-all flush`
   (synchronous on vanilla and Paper: it returns once every chunk is on disk) and, after the copy, `save-on`, over the
   existing `server.command` op. Without an agent it types the same into the console and waits for "Saved the game".
   Minestom has no such commands and saves only on stop, so a backup of a running Minestom server is the world as last
   stopped, and the result says so.
4. **Rollback stops first.** `build_undo backup=<name>` (exact name, else the newest containing the text): stop the
   server (every platform saves on stop, Minestom included), copy the current world as a `before-rollback` backup (so
   a rollback can be rolled back), restore, start again if it was running, and answer with the start report. The core
   forgets its edit ids: they name blocks of the world that was replaced.
5. **The restore is a swap with a way back.** The backup is copied into `run/.msc-restore/new/` (same volume as the
   world, so the swap is renames), the list of folders written, the current world folders renamed into
   `.msc-restore/old/`, the new ones renamed in, the old ones deleted. A failure puts the old folders back; a crash is
   put back (or finished, when every new folder was in) by the next backup or restore.
6. **Automatic before big builds.** A `build_batch` that places 100,000 blocks or more takes a backup after its names
   are checked and before its first block is written, unless the newest backup is younger than 5 minutes (the undo
   history covers the edits in between; 5 minutes keeps a series of big builds from copying the world each time). The
   build result says `backup <name> taken first`. A failed backup does not stop the build; the result says so.
7. **On `build_undo`, one argument**: `backup` takes `list`, `save [label]` or a backup to restore. To fit the guard,
   two texts lost words that said nothing new (`world_summary`'s "in a few lines", `project_scaffold`'s `dir`
   default): the list as the client receives it is 15,973 characters (`McpBridgeTest`'s guard is 16,000).
8. **Windows.** The running server holds a lock on `session.lock`, which is never copied (the server writes a new one
   on start). Region files are opened shared, so reading them while the server runs works. Renames and deletes are
   retried for 5 seconds while Windows reports a file in use (a virus scanner, Explorer, a server still exiting).

No protocol change.

## Consequences

- A whole world can go back to any of the last few backups, across restarts, crashes and sessions, and past the undo
  depth; a rollback costs a server restart.
- A backup costs a copy of the world: for a superflat dev world, a few MB; a big world can take seconds and is bounded
  by the 2 GiB budget.
- Turning autosave back on assumes it was on: a server whose user ran `save-off` by hand has it on afterwards.
- A server MSC attached over `MSC_CORE_ADDRESS` (not in the server folder) has no world folders here: big builds skip
  the backup, and `save` says no world folder was found.
- Not verified on Windows: the lock and retry behaviour is from how Java opens files there, not from a run.

## Amendment, 2026-11-15: no guessing which backup (#573, #574)

- Decision 4 picked "the newest containing the text", and every name contains the empty text, so `backup=""` (which
  clients send for an unset string) rolled the world back to the newest backup. Now a blank `backup`, or a part that
  more than one name contains, is refused in one line before anything stops; only an exact name or a part one name
  has restores.
- "Newest" was the name order, so two backups from one second sorted by label. Each backup now holds a `.order` file,
  its place in the order backups were taken; the list, the pick and the prune go by it (backups without one sort
  last, by name).
- The console path of decision 3 (no agent: wait for "Saved the game", refuse and still send `save-on` when it never
  comes) and the Minestom note have behaviour tests (`WorldBackupToolsTest`, with `FakeServerMain` answering
  `save-all flush`).
