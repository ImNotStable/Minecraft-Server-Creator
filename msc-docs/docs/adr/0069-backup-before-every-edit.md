# ADR 0069: A world backup before every edit, at most one per five minutes

Status: accepted, 2026-11-22, decided by Claude. Extends [ADR 0041](0041-world-backups.md).

## Context

ADR 0041 took a whole-world backup only before a `build_batch` placing 100,000 blocks or more. Every other edit relied
on the in-memory undo history, which a restart, a crash or a new session drops, and which never covered edits made
through `server_command` (`/fill`, `/clone`, `//set`) or by the test player.

The tools that can change the world, from the tool registry (27 tools):

| Tool | Changes the world | Backup first |
| --- | --- | --- |
| `build_batch` | every non-dry run (shapes, `replace=`, structure pastes, terrain) | yes |
| `build_undo edit=` | puts an edit's old blocks back | yes |
| `server_command` | any command but a few that only read or talk | yes, unless every line is one of `list help say tell msg w tellraw me seed version ver plugins pl tps mspt save-all save-on save-off` |
| `player_test` | `use`, `attack`, `interact` and `/command` actions | yes, for those actions (`/command` as `server_command`) |
| `build_undo backup=` | the rollback itself | no: it already saves the current world as `before-rollback` |
| `dev_cycle`, `server_setup`, `project_scaffold`, `plugins`, `pack`, `texture`, `model_create`, `structure_save` | no: code, configuration, pack or files; a plugin changing the world on its own is outside MSC, as in ADR 0041 | no |
| `world_*`, `server_logs`, `server_wait`, `msc_status`, `api_*`, `texture_view`, server start, stop and detach, `msc_workspace` | no | no |

Measured on 2026-11-22 on the CI host (Paper 1.21, `level-type=flat`, fresh world, spawn chunks loaded): the three
world folders are 10.5 MB (3.4 + 3.6 + 3.5 MB, the nether and end too, as Paper keeps their spawn areas); copying them
takes 0.01 s with a warm page cache; `save-all flush` answered within the same second. A backup of a dev world costs
well under a second, most of it the flush.

## Decision

1. **Whole-world, throttled.** Each tool call in the table above takes a backup (label `auto`) before it changes
   anything, unless the newest backup is younger than 5 minutes; the undo history covers the edits in between. No
   block threshold any more. Region snapshots through the undo machinery were not chosen: they live in the server's
   memory (lost on restart, the gap this closes) and would need a protocol change, while a whole dev world copies in
   milliseconds.
2. **Bounded.** The prune of ADR 0041 still keeps the newest 5 within 2 GiB. It also keeps the session's first
   automatic backup (the world before the session's first edit), so a long session's backups never push it out; the
   cap is still 5 (the newest and that one count toward it).
3. **Discoverable.** The result keeps ADR 0041's one line, `backup <name> taken first (build_undo backup=NAME restores
   it)`; `build_undo`'s `backup` description says `Edits save one first`. The tool list is 16,588 characters
   (`McpBridgeTest` guard: under 16,600).
4. **Never in the way.** No server of this session and no agent: no backup (nothing can edit, the call fails on its
   own). A server MSC attached over `MSC_CORE_ADDRESS` has no world folders here and takes none. A failed backup does
   not stop the edit; the result says so.

## Consequences

- An agent loop pays one flush and copy per five minutes of editing, under a second on a dev world; a big world is
  bounded by the 2 GiB budget.
- The `server_command` read-only list is a shortcut: any other command, a plugin's own included, counts as an edit, so
  a read-only plugin command can take an unneeded backup (at most one per five minutes).
- `player_test` with only movement takes none, though the player's own data in the world folder changes.
