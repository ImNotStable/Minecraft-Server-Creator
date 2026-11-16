# ADR 0053: Builds by their hash, and a pack host ends with its server

Status: accepted, 2026-11-16, decided by Claude (#752, #753). Amends [ADR 0051](0051-publish-the-pack-from-one-state.md)
(its #750 amendment, points 3 and 4) and [ADR 0017](0017-workspace-and-pack-lifetime.md) (the standalone host).
Point 6's free pack port is amended by [ADR 0054](0054-pack-ports-and-host-identity.md) (#755): it is never a port the
workspace uses otherwise, and a leftover host is reused by its identity, not by what its port serves (#756).

## Context

Two holes were left after #750, each in a class of paths rather than one call.

- **A server stopped without its pack host (#752).** The leftover's server and its standalone host were stopped by
  different steps: `server_start` stopped the host in "take over the pack", which only a start runs, so
  `dev_cycle restart=false` stopped the leftover and left its host serving with no server, past every session (the
  session end stopped only a host this session held), holding `MSC_PACK_PORT` so the next `pack` waited 5 s and moved
  to another port. A leftover that exited on its own left the same orphan. `server_stop`, build_undo's rollback and
  the session end each had their own copy of the rule.
- **One shared file for every session's build (#753).** The standalone host served `.msc/build/pack.zip`, whatever it
  held, and every build rewrote that file. A `pack` in a second live session (whose server is the first session's,
  so it left that offer alone, #750 point 4) replaced the zip, and the first session's offered URL and the URL its
  agent had sent players answered 404, also after it ended. A build after an earlier session ended did the same to
  the URL the leftover's agent still hands out (#750's consequences).

## Decision

1. **A server that outlives the session ends through one function, with its pack host.** `ServerRun.end` stops a
   leftover and, when asked, this session's own server (detached or not), then calls `ResourcePack.release`. Every
   call that stops or replaces the server goes through it: `server_start` and a dev_cycle start ("stop the server an
   earlier session left running"), dev_cycle with or without restart ("stop a server an earlier session left holding
   the jar", "stop the server"), `server_stop` and build_undo's rollback. A leftover that resists its stop is a
   refusal, and its host keeps serving it.
2. **A standalone host runs only while the server outlives the session.** `ResourcePack.release` goes by the one
   state, `ServerManager.lifetime`: while the server outlives the session it does nothing; otherwise it stops the host
   this session serves with (started here or reused) and, unless another live session runs the server (whose host it
   is), the host `.msc/pack-host.pid` names, as no server is left for it. It runs after every stop above, at the start
   of every publish that does not need a host, and at the session end and a switch away that closes the workspace, so
   a leftover that exited on its own loses its host at the next of those.
3. **A build is a file named by its hash, never rewritten.** Every build also writes `.msc/build/pack-<sha1>.zip`
   (`pack.zip` stays the workspace's current build, which `current()`, `file=` and an earlier session read). The
   standalone host is started with the folder and answers `/pack-<sha1>.zip` with that file, when it is there and its
   content has that hash. So a URL stays served as long as its file is there, whichever session built a later pack,
   and a host taken over from an earlier session serves the new build at once and the earlier ones as before. Before a
   host serves a build (reused or started), the publishing session writes that build's file again if it is missing.
4. **Old build files are pruned by what may still ask for them.** After every publish and at the session end, the
   build files are deleted except this session's build, the build `server.properties` offers and the build the
   workspace's server was last sent (`.msc/pack-sent`, written by every send, so a later session knows what a
   leftover's agent hands out). Never while another live session runs the server: its builds are its own. The
   in-process host serves from memory, so a pruned file never breaks a session's own URL.
5. **A pack while another live session runs the server serves on a free port at once.** That session's host holds the
   workspace's pack port; the session does not wait 5 s for it, and does not remember the free port in
   `.msc/pack-port`, which stays that workspace's.

6. **The pack port is one the system never hands out, and a host is up only when it says so.** The pack's port
   changes hands between processes: from the in-process host to the standalone one, and from a host that ended to
   the next one, in this session or a later one. A free port was picked with a bind to port 0, so it came from the
   ephemeral range, which the system also uses for every other bind to port 0 and for the local end of every
   outgoing connection. Anything on the machine could take it in that gap. Two things then went wrong: the next host
   could not bind it and the pack moved to another port, and the core counted the standalone host as up because a
   connection to the port succeeded, which reached whatever had taken it. So the host claimed by the answer and the
   offer served nothing. Now:
   - A free pack port (the first one, and the one taken when the remembered port is not free) is picked at random
     below the ephemeral range: 20000 to 32767 by default, or below the start of Linux's
     `ip_local_port_range` when that is lower. Only a bind that names the port can take it there. If that range has
     nothing free, it falls back to port 0. Amended by [ADR 0054](0054-pack-ports-and-host-identity.md): never a
     port the workspace uses otherwise, such as 25565 or 25577, which that range holds (#755).
   - The standalone host prints `pack host serving on port <port>` once it serves, and only that line counts as
     the host coming up. A listener that is not the host, or a host that exits or stays silent for 15 s, is a host
     that did not come up (point 3 of ADR 0051).

## Consequences

- `server_detach`'s promise holds on every path: the next session's `server_start`, `dev_cycle` (also
  `restart=false`) or `server_stop` stops the server and the pack host, and its answer says `stopped the pack host
  left running by an earlier session` where that host ran.
- A second live session's `pack` never takes the first session's offer or sent URL away, during that session or
  after it ends; the second session's own URL is served in-process until the server is its to publish for.
- `.msc/build` holds a few `pack-<sha1>.zip` files beside `pack.zip`, typically one to three.
- A host started by an MSC before this decision serves `pack.zip` itself; a build still rewrites that file, so such a
  host is reused as before, and the next one started serves the folder.
- A prune and another session's start of a host race only within the host's start time: the publishing session
  writes the build's file before the host starts and again once it is recorded.
- A workspace whose remembered port is in the ephemeral range, from an MSC before this decision, keeps it until it
  is taken once; the free port picked then is outside that range.
- Under loopback churn (thousands of short-lived connections and port-0 listeners on the machine, as from parallel
  builds), `PackLifecycleOrderTest`'s leftover table and `WorkspaceOwnershipTest` failed 5 rows over 3 runs before
  point 6; after it they passed 3 runs, and 2 more together with the rest of `PackLifecycleOrderTest`,
  `KeepServingTest` and `PackHostTest`.
- `PackLifecycleOrderTest` adds dev_cycle `restart=false` on a leftover with its host running, gone and never started,
  a leftover that exits on its own, and two live sessions on one workspace each running pack, dev_cycle (with and
  without restart), `server_detach` and their end in various orders. After every step and after both sessions end,
  no pack host runs without a server, and while a server runs the URL `server.properties` offers and the URL its agent
  was last sent are served.
