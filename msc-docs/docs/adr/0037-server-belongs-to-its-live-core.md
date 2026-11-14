# ADR 0037: A server belongs to the core that started it while that core lives

Status: accepted, 2026-11-14. Decided by Claude under ADR 0029. Amends [ADR 0013](0013-install-and-first-use.md) point 8 and [ADR 0014](0014-detach.md). Closes #482.

## Context

ADR 0013 point 8 and ADR 0031 let a core stop a server "left running by an earlier session": a server whose core was killed (on Windows a core killed with its Claude Code session cannot stop its server) or that was detached. `.msc/server.pid` recorded only the server's pid and start time, and nothing told a server whose core had died from one whose core was still running. With two Claude Code sessions open on one workspace, the second core's `msc_status` called the first core's live server a leftover, and its `server_start`, `dev_cycle` or `server_stop` stopped it (killed without a save where RCON is off). The first core was not told, and its next start took the second core's server the same way.

## Decision

1. **The pid file names the owning core.** `.msc/server.pid` holds `serverPid serverStart corePid coreStart [resisting]`: the server, then the core that started it, each by pid and process start time (epoch millis). `ServerManager` writes its own identity (`PidFile.Identity.current()`, injectable in tests so two managers stand for two cores).
2. **Leftover means the owner is not alive.** The owner counts as alive only when a process with its pid runs and has the same start time, the check already used for the server; never by pid alone, so a reused core pid does not keep a dead core's server from being cleaned up, and an unknown start time (0) counts as not alive. An old-format file (two words, from cores before this ADR, or the pack host's file) has no owner: a leftover exactly as before. A file this very core wrote is its own, as before (a core that lost track of its server takes it as a leftover).
3. **A live other owner is a refusal, checked first.** While another live core's server runs, `server_start`, `server_stop`, `dev_cycle` (it deploys into that server's folder, restart or not) and `server_setup` refuse in one line: the server's pid, the other core's pid, "use that session, or end it", and that nothing changed. The check comes before any side effect (the Steps rule "check first"; `server_stop` checks before it touches a leftover pack host). `ServerManager.start` and `stopLeftover` check again under their monitor, so a race between the check and the step still never stops the other server. `msc_status` says `running under another session that is still running` with both pids.
4. **A detached server keeps its owner.** `server_detach` changes nothing in the pid file. While the detaching session lives, other sessions refuse to touch the server, as for any server it runs; once that session ends, its core is gone and the server is a leftover, so the next session's `server_start`, `dev_cycle` or `server_stop` stops it (saved over RCON) as ADR 0014 promised. A server started outside MSC and attached through `MSC_CORE_ADDRESS` (the benchmark) has no pid file and is never touched, as before.
5. **A stop removes only its own pid file.** A core whose stop waited for its server deletes `.msc/server.pid` only when the file still names that server, so a pid file another core's start wrote meanwhile stays (as #323 did for one core).

### Why owner fields and not a workspace-wide core lock

A `FileLock` on `.msc/core.lock` held for each core's life was considered. It is not smaller, and it behaves worse:

- It says "some core is alive here", not "the core that started this server is alive". Core A dies leaving its server, core B opens and idles, core C opens: C sees B's lock and refuses, though the server is A's leftover. Fixing that needs the owner in a file anyway.
- On Windows a `FileLock` is mandatory: the locked region cannot be read by another process, so the lock file cannot also carry the owner's pid for the refusal unless it locks a region apart from the text, and a locked file cannot be deleted or replaced. Release after a process is killed is done by the OS but is not promised to be immediate. On Linux the lock is advisory and per process.
- Java's `FileLock` is per JVM: two cores in one JVM (tests; a `msc_workspace` switch back to a folder) throw `OverlappingFileLockException` and need in-process bookkeeping.
- A lock is a held resource for the core's whole life; owner fields are two numbers written once at start.

Owner fields reuse the identity check the server already had, hold nothing open, read the same on both systems (`ProcessHandle.info().startInstant()` comes from `GetProcessTimes` on Windows and `/proc` on Linux, the same value for every reader), and need no cleanup when a core dies: its start time no longer matches anything.

## Consequences

- Two sessions on one workspace no longer take the server from each other: the second one is told whose it is and what to do.
- A core killed outright still leaves a server the next core stops, on both systems, as ADR 0013 point 8 intended.
- An older core reading a new-format file sees no valid pid file: it treats it as stale and may delete it. Mixed core versions on one workspace happen only during an update with a session still open; ending the old session settles it.
- The pack host's pid file (`.msc/pack-host.pid`) keeps the old format: it follows its server, so a refusal on the server comes before any pack-host step, and a pack host left by a live core whose detached server already exited is stopped by the next start, as before.
- Tests: `ServerOwnershipTest` runs two `ServerManager`s as two cores (the other core's identity is a real child process, killed to stand for a killed core), the old format, a reused core pid and a pid file this core wrote; `RefusalsChangeNothingTest` covers the four refusals; the reused-server-pid tests are unchanged.
