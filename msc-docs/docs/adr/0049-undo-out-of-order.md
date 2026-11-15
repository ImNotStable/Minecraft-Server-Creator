# ADR 0049: Undoing an edit that is not the latest

Status: accepted, 2026-11-15, decided by Claude (#695). Amended 2026-11-15 (#701, #702, #704, #708; see the end).

## Context

`build_undo edit=<id>` takes any remembered edit, not only the latest. The agent's undo puts back the blocks the edit
replaced, as they were when it ran. When a later edit still standing set some of the same positions, that undo
silently wiped the later edit's blocks there and answered OK; undoing the later edit afterwards then put back blocks of
the edit already undone. The world ended in a state no sequence of edits made (a house shell undone under its
furniture wiped the furniture, and undoing the furniture rebuilt part of the old shell).

The options were: refuse such an undo and name the edits to undo first; undo only the positions no later edit set and
say how many were skipped; or leave it and document it.

## Decision

Refuse it. An edit is undone only when no later edit that is still applied set any of its positions. Otherwise the
call changes nothing and answers `e1 not undone: later edit e2 set some of the same blocks, which undoing e1 would
wipe. Undo it first (build_undo edit=e2), then e1.`, listing every such edit newest first. An older edit that no later
one overlaps is undone as before.

"Set" means every position the build placed a block at, also where the block was already there: the core keeps each
edit's positions (`Footprint`: its box and one bit per cell, at most 1 MiB for the largest build) beside its agent
parts. A build that failed half-way and left parts under a new id keeps the whole build's positions, which only refuses
more.

World-changing edits run one at a time per server (#697): a build from its first read of the world to its new id, an undo from
its overlap check to its id leaving the list, each under one lock in `WorldService`. A call sent while another runs
waits for it. Without this, an undo checked overlap before a running build was remembered, and a build wrote while an
undo of the same blocks ran, so parallel tool calls broke the rule above, and ids went out in the order builds
finished rather than wrote. Reserving each build's footprint before its writes was not chosen: it needs the same check
for builds and a wait or refusal for each overlapping pair, which is more code to get right for little gain: builds
on one server rarely run in parallel on purpose. Only a build's argument checks run outside the lock (#704).

Skipping the overlapped positions was not chosen: the edit would be half undone under an id that is gone, and the
blocks it kept would belong to no edit.

## Consequences

- After any sequence of builds and undos, the world is what replaying the edits still applied, oldest first, gives
  (a seeded property test checks this, and another sends overlapping builds and undos at once). Undoing newest first always works, so the default (the latest edit) is never
  refused.
- Each remembered edit costs its footprint in memory until it is undone or its server run ends, as its agent ids do.
  The agent's undo history lives in the server process, so the core forgets every edit made before the server last
  stopped or started (a server_stop, a dev_cycle restart, a server that stopped on its own; #699): such an id is
  unknown to build_undo, and it no longer refuses older edits it overlaps.
- An edit whose later overlapping edits were lost to the agent's bounded undo history stays refused until those are
  undone (partly); `build_undo backup=` rolls back further.

## Amendment, 2026-11-15: an edit belongs to one server run (#701, #702, #704)

Three findings each came from a step of an edit that did not belong to the same run or the same turn as the rest:
a build masked `replace=` against the world before it waited for the lock (#704), the clean-up of a build cut off by a
stop sent its agent ids to whatever agent connected next (#702), and a build or undo cut off by a stop offered its id
again although the id ended with that run (#701). One rule now covers them:

- **An edit has one run.** A run is the server process the core started (none for an attached server) and the agent
  connection to it (`WorldService.Run`). A build takes the run when it gets the lock and sends every write, name check
  and clean-up undo of its parts on that connection only; an undo sends on the run its edit was made in. A call never
  waits for, or reaches, a later agent: a restarted agent numbers its edits from the start again, so an old id could
  undo another edit there. The edit ends when its process stops or is replaced, or its connection closes; an agent
  that reconnects to a running server also ends it, which only loses ids, never undoes the wrong blocks.
- **Everything that decides what a build writes runs under the lock**: its `replace=` masks and `copy` reads, its block
  name check, its backup, its writes and its new id. So the edits apply in id order, and the replay rule in
  Consequences holds for parallel calls too (the concurrency test sends a `replace=` build during another one).
- **An id is named only while its run lasts.** A build whose clean-up fails or an undo that stops half-way while the
  run goes on (a timeout) names the id that undoes the rest, as before. Once the run has ended, the answer names no id
  and no count (#708): the agent applies each part over several ticks, so the part in flight may be half applied, and
  what stays is what the server saved (Minestom saves only when it stops, so after a crash none of it may stay). A
  build or undo cut off this way, before its first part finished or after any part, gives one answer: the box (the
  build's, or the undone edit's) may be partly changed, by an amount that depends on what the server saved;
  `world_summary` shows what stayed, then `build_undo backup=` rolls the world back if a backup exists, or a
  `build_batch` over it sets the blocks wanted. A closed agent connection is never the answer: any call it cuts off
  says the server stopped or restarted.
