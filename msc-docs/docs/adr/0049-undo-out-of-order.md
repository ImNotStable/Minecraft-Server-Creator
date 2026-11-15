# ADR 0049: Undoing an edit that is not the latest

Status: accepted, 2026-11-15, decided by Claude (#695).

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

World-changing edits run one at a time per server (#697): a build from its first write to its new id, an undo from
its overlap check to its id leaving the list, each under one lock in `WorldService`. A call sent while another runs
waits for it. Without this, an undo checked overlap before a running build was remembered, and a build wrote while an
undo of the same blocks ran, so parallel tool calls broke the rule above, and ids went out in the order builds
finished rather than wrote. Reserving each build's footprint before its writes was not chosen: it needs the same check
for builds and a wait or refusal for each overlapping pair, which is more code to get right for little gain: builds
on one server rarely run in parallel on purpose. A build's reads and block-name check run outside the lock.

Skipping the overlapped positions was not chosen: the edit would be half undone under an id that is gone, and the
blocks it kept would belong to no edit.

## Consequences

- After any sequence of builds and undos, the world is what replaying the edits still applied, oldest first, gives
  (a seeded property test checks this, and another sends overlapping builds and undos at once). Undoing newest first always works, so the default (the latest edit) is never
  refused.
- Each remembered edit costs its footprint in memory for the life of the core process, as its agent ids do.
- An edit whose later overlapping edits were lost to the agent's bounded undo history stays refused until those are
  undone (partly); `build_undo backup=` rolls back further.
