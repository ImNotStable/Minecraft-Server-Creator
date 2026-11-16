# ADR 0053: Builds by their hash, and a pack host ends with its server

Status: accepted, 2026-11-16, decided by Claude (#752, #753). Amends [ADR 0051](0051-publish-the-pack-from-one-state.md)
(its #750 amendment, points 3 and 4) and [ADR 0017](0017-workspace-and-pack-lifetime.md) (the standalone host).

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
- `PackLifecycleOrderTest` adds dev_cycle `restart=false` on a leftover with its host running, gone and never started,
  a leftover that exits on its own, and two live sessions on one workspace each running pack, dev_cycle (with and
  without restart), `server_detach` and their end in various orders. After every step and after both sessions end,
  no pack host runs without a server, and while a server runs the URL `server.properties` offers and the URL its agent
  was last sent are served.
