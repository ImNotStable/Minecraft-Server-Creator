# ADR 0051: Publish the pack from one state

Status: accepted, 2026-11-15, decided by Claude (#743, #744, #745). Amends [ADR 0017](0017-workspace-and-pack-lifetime.md)
points 3 and 4; the workspaces a switch keeps ([ADR 0050](0050-agent-belongs-to-its-workspace.md) point 5) publish
through it.

## Context

The pack of a detached server had a hole each round, and each hole depended on the order of the calls: a pack built
after `server_detach` was served in-process only (#741); then the fix moved the pack to the standalone host inside
the build, so a host that did not come up failed the whole `pack` call and claimed the pack was still served when
nothing served the new build, with `(... 2 more)` as the reason (#744); `server.properties` was written only when
the build came before the detach, since `pack` followed an offer but never added one (#743); and the hand-over on a
switch away could never run (#745). Every entry point (`pack`, dev_cycle's rebuild, a start's send, `server_detach`, a
switch away and back, the session end) carried its own part of the rule, and each fix added another place.

## Decision

1. **One state.** Whether this workspace's server is detached (`ServerManager.detached`) is the only thing that decides
   who serves the pack. `ResourcePack` reads it; it keeps no copy.
2. **One function.** `PackPublisher.publish` is what every entry point calls after it changed the build, or what must
   serve it: `pack`, dev_cycle's rebuild, the start's send and its take-over of an earlier session's offer,
   `server_detach`, a switch away (before the in-process host stops), the switch back, and the session end. It goes by
   the state alone, never by what ran before, so the order of those calls does not matter. In this order:
   - **serve** (`ResourcePack.serve`): while the server is detached, the standalone host serves the build, started
     when it is not running (also when it ended meanwhile); otherwise this session serves it, and a standalone host it
     started earlier is stopped. A build never moves the pack: it is served wherever it is served now, and `serve` alone
     decides where it should be.
   - **offer**: where the platform reads `server.properties` (`Platform.readsPackProperties`), the file offers the build
     while the build outlives the session (the standalone host serves it), and once it offers this workspace's pack it
     keeps following the build (ADR 0017 point 4, for the next MSC start). With no build left, such an offer is cleared.
     Minestom and Velocity get nothing written, as before (#718).
   - **send**: the connected agent gets the build when it was not last sent exactly this build and the workspace has a
     pack to send; `pack` and the start's send always send it.
3. **A host that does not come up is an answer.** The build stays served in-process and is still sent; no new offer
   goes into `server.properties`, as the pack ends with the session. The answer of `pack`, dev_cycle, `server_detach` says `could not start a standalone pack host
   (<reason>), so the resource pack is served by this session only, until it ends`, and a switch away says `..., so
   nothing serves the resource pack until the switch back`. The next publish tries the host again. The reason is one
   line: `PackHost.main` prints its exception's message, and the core reads past launcher notes and stack trace lines.
4. Removed: `keepServing`, the `standalone` field, `handOver`, `unpark`. `server_stop` still stops the host it detached
   with its server.

## Consequences

- `pack` then `server_detach` and `server_detach` then `pack` (or a dev_cycle rebuild) leave the same
  `server.properties` and the same host. `PackLifecycleOrderTest` checks every order of detach, pack, dev_cycle, a
  switch away and back, `server_stop` and the session end on Paper's fake server and on Minestom.
- `pack`'s and `server_detach`'s steps are now `serve the pack`, `offer the pack in server.properties`, `send it to the
  server's players`, the same names for both.
- A detached server that keeps failing its host costs every publish one more try (up to the host's start time).
- ResourcePack's monitor asks `ServerManager.detached`, a second nesting into ServerManager beside AgentHub's
  (`Core`'s lock order); ServerManager calls into neither part.
