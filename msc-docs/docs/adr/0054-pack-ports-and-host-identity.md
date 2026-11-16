# ADR 0054: Pack ports the workspace does not use, and pack hosts told by their identity

Status: accepted, 2026-11-16, decided by Claude (#755, #756). Amends [ADR 0053](0053-builds-by-hash-and-hosts-end-with-their-server.md)
(point 6, the free pack port) and the #750 amendment of [ADR 0051](0051-publish-the-pack-from-one-state.md) (point 3,
which host is reused).

## Context

Two holes after #754, each a class rather than one call.

- **A pack port on a port the workspace needs (#755).** ADR 0053 point 6 picks a free pack port at random from 20000
  to 32767, checking only that it is free at that moment. That range holds 25565 (the server's default and the dev
  backend's) and 25577 (the dev proxy's). A pack built before the server runs could take the server's port; it was
  remembered in `.msc/pack-port` and taken again by every later session, so every `server_start` and `dev_cycle` failed
  with "server port already in use", and the fix line blamed a leftover server. Nothing listed the ports a workspace
  uses: the server's port, RCON, query, Geyser, the dev proxy and the agent link were each known only where they were
  set up.
- **A host told by what its port serves (#756).** Whether to reuse the standalone host `.msc/pack-host.pid` names was
  decided by "the port this workspace's pack is served on answers 200 for the build". With a fixed `MSC_PACK_PORT`,
  this session's own in-process host can be what answers there while the earlier session's host serves on a port it
  moved to. The session then adopted that host as if it were on the fixed port, stopped its own host, and the `pack`
  answer and the Paper offer named a URL nothing served, past both sessions.

## Decision

1. **One list of the ports a workspace uses** (`WorkspacePorts`), read each time it is asked, as the files change: the
   defaults (25565 game and dev backend, 25575 RCON, 25577 dev proxy, 19132 Geyser), what the server folder sets
   (`server-port`, `rcon.port` and `query.port` in `server.properties`, `bedrock.port` in Geyser's config, every
   address and `port` in `velocity.toml`) and the core's own agent link port. Each port maps to what uses it.
2. **The pack never takes a port on that list.** The free-port pick skips reserved ports (a random start, then the next
   port up that is not reserved, wrapping around), and the system's pick (port 0, the fallback) is dropped when it lands
   on one. A remembered `.msc/pack-port` that is now reserved is dropped and a free port picked, which is remembered
   instead. A fixed `MSC_PACK_PORT` on a reserved port is refused by name: `MSC_PACK_PORT=25577 is the dev proxy's port
   (...). Set MSC_PACK_PORT to another port, or unset it to serve the pack on a free one`; a start reports it as a pack
   not sent. An in-process host whose port became reserved (the server folder changed) moves at the next publish, and
   a start makes it let go of that port before the server binds.
3. **A start whose port an MSC pack host holds says whose.** The "server port already in use" fix asks the port who
   holds it (point 4): this session's own host lets go of it, is never given it again in this session, and the fix says
   `this session's resource pack host held port N and has moved to a free one: start the server again`; this
   workspace's standalone host or another one is named with its pid. This covers a port the core cannot know, such as
   one a Minestom main class sets.
4. **Every pack host says who it is, and is told by that alone.** Each host answers `GET /msc-pack-host` with
   `msc-pack-host pid=<pid> token=<token>`: the process it runs in and a random token. A standalone host gets its token
   on its command line; the core writes `.msc/pack-host.id` (`<pid> <port> <token>`) before `.msc/pack-host.pid`, in a
   file of its own so an MSC from before this decision still reads the pid file. A leftover host is reused only when
   the pid file names a live process, the id file names the same pid, its port is not reserved, and the host on that
   port answers that pid and token (and serves the build). The URL then names that port. Anything else is stopped and
   replaced. So another process on the port, a host from an MSC before this decision, and this session's own
   in-process host (whose pid is the core's and whose token is its own) are never taken for it. The standalone host's
   ready line (ADR 0053 point 6) stays how a start knows its host came up; no other place decides ownership by probing
   a port.

## Consequences

- A pack is never served on 25565, 25575, 25577, 19132 or a port the workspace's server folder sets; a workspace whose
  `.msc/pack-port` names one heals at the next pack, publish or start.
- A fixed `MSC_PACK_PORT` that names such a port fails `pack` (and a start's send) with a line that names the port's
  use, instead of a server that cannot start.
- With a fixed `MSC_PACK_PORT`, a reused host may serve on another port than the fixed one (the one it moved to while
  the fixed one was held); the answer and the offer name the port it serves on. The next host started serves on the
  fixed port again.
- A standalone host started by an MSC before this decision has no identity file: the first publish that would reuse it
  stops it and starts one on the same port, so the offered URL stays the same.
- `PackPortTest` covers a remembered port on the server's default port and on ports `server.properties` sets, a fixed
  port the workspace uses, and a start whose port this session's pack host holds. `PackHostTest` checks that the pick
  never returns a reserved port over seeded tables, also when every pick lands on 25565 or 25577 first.
  `PackLifecycleOrderTest` runs the two-session and leftover tables on a fixed pack port too, with orders where the
  second session packs before the first starts (#756's order among them), and checks after every step and after both
  sessions end that the offer is served by the host the pid file names, by its identity.
