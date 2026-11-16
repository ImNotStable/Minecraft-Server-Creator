# ADR 0051: Publish the pack from one state

Status: accepted, 2026-11-15, decided by Claude (#743, #744, #745). Amended 2026-11-15 (#747, see the end). Amends [ADR 0017](0017-workspace-and-pack-lifetime.md)
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

## Amendment, 2026-11-15: an offer only while a host outlives the session (#747)

Point 2's offer followed the build once the file offered this workspace's pack, whether or not anything served it past
the session. So when the standalone host did not come up on a `server_detach` and the file already offered the pack
(an earlier detach's, kept pointed at the build by every start), the detached server, which outlives the session, kept
offering a URL that dies with it, and the answer said `server.properties now offers the pack too, so a restart outside
MSC keeps it` and that the next session stops `the pack host`, which did not exist: each line was worked out on its
own, from whether the file was written.

1. **The offer of a detached server needs a host that outlives the session.** While the server is detached and no
   standalone host serves the build, an offer of this workspace's pack is withdrawn (cleared), not followed; point 3's
   "no new offer" holds for an offer already there too. The next publish whose host comes up (`pack`, dev_cycle's
   rebuild, `server_detach`, a switch, the session end) offers the build again. A server that is not detached ends
   with the session, so its offer still follows the build for the next MSC start (ADR 0017 point 4).
2. **Every answer line on the pack host and the offer comes from one place**, `PackPublisher.Published`, made from
   whether the standalone host serves the build past the session (`outlives`) and what the publish did to the offer
   (`Offer`: offered, withdrawn, cleared, untouched). `server_detach` names `the resource pack host`, `a restart
   outside MSC keeps it` and `the pack host` the next session stops only when a host outlives the session; the
   host-trouble line of `pack`, dev_cycle, `server_detach` and `msc_workspace` ends `; server.properties offers no
   pack until a standalone host serves it` when the offer was withdrawn.
3. `PackLifecycleOrderTest` adds a table where the host stops coming up and comes back among detach, pack and a
   switch, with and without an earlier offer kept by a stop and a start, and checks each answer's claims on the host
   and the offer against what runs and what the file says, also after the session end.
