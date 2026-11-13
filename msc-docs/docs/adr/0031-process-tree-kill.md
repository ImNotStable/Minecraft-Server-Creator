# ADR 0031: One identity-proven process tree kill, in msc-process

Status: accepted, 2026-11-13. Decided by Claude under ADR 0029. Closes #357 and #358.

## Context

msc-core kills a command with its children when a build or `dev_cycle` is interrupted or times out, when a server
does not stop in time, and when a leftover server is stopped (`ProcessRunner.destroyTree`). msc-bench does the same
for agents, servers and check commands, and its Ctrl-C hook does it for everything a run started
(`ChildProcesses.killTree`, `killAll`). There were two copies of this code.

- The bench copy killed a single `descendants()` snapshot, so a child forked during the kill outlived the bench (#358,
  the race #339 fixed in core).
- The core copy (#339) kept sweeping with `root.descendants()` after it had killed the root. JDK 25's
  `ProcessHandleImpl.descendants()` looks up the parent's start time in its snapshot. When the root is gone that
  start time stays 0, and every live process that records the root's pid as its parent passes the filter. On
  Windows an orphan keeps its dead parent's pid and pids are reused quickly, so the sweep could force-kill unrelated
  processes on the owner's PC, together with their children (#357).

ADR 0004 says the bench must not depend on core's code. The only modules both use are `msc-protocol` (the
core-to-agent wire contract, shaded into every agent jar) and `msc-bot` (the test player). Neither is a home for a
process killer.

## Decision

1. A new leaf module, `msc-process` (package `io.github.imnotstable.msc.process`, no dependencies), holds the only
   tree kill. msc-core and msc-bench depend on it. Its public API is `ProcessTree.destroy(ProcessHandle)`.
2. **The kill rule.** A process is signalled only when it is proven to descend from the root. A process is proven
   when all of these hold:
   - its recorded parent pid is the pid of a proven process;
   - that parent is in the same snapshot with its own start instant;
   - the process started no earlier than that parent.

   Such a parent was alive from its start until the snapshot, so it held the pid when the child started, and pids are
   unique among live processes. A parent pid alone proves nothing. A parent missing from the snapshot proves
   nothing. A process whose start instant is unknown proves nothing.
3. **Signals by identity.** Every signal goes to a process by pid and start instant. Kills go through the JDK's
   `destroyForcibly`, which checks the start time in native code. SIGSTOP and SIGCONT go through `/bin/sh` only to
   a process that was alive under that identity just before the call. Unix hands out pids in sequence, so a pid is
   not reused within that moment.
4. **`descendants()` only on a live handle.** The JDK's own guard holds only when the parent is in its snapshot, so
   `descendants()` is called only on a handle that is alive just before and just after the call. `TreeKill` then
   checks every edge again under the rule above.
5. **Kill order.**
   - On Unix the tree is frozen top-down with SIGSTOP until a snapshot proves no new process. Then everything is
     killed, the root last, and nothing is swept afterwards. A stopped process that survives its kill (no
     permission) is continued, so it is not left stopped.
   - On Windows, and wherever the freeze cannot finish, the tree is killed from the leaves up, with a snapshot per
     round. The root goes last, once a snapshot proves no live descendant. Nothing is looked up after the root
     dies.
6. The decision logic, `TreeKill`, is a pure function over a small `ProcessTable` interface. `LiveProcessTable` is
   the real table; the tests use a fake that can behave like Windows or Unix.

## Consequences

- Windows has no stop signal. A child that a process forks after the last snapshot that saw that process alive
  cannot be proven once that process is gone. That child is left running rather than risk killing an unrelated
  process.
- Start instants have millisecond precision, the same precision the JDK's guard relies on.
- The Windows branch has been checked only against the fake table, which models orphans that keep a dead parent's
  pid and immediate pid reuse. The tests include a property test with random churn, and a test where an unrelated
  process records the root's pid as its parent and must survive. It stays that way until a run on the PC.
- `ProcessRunner.destroyTree` and `ChildProcesses.killTree` are one-line delegates, so their callers did not change.
