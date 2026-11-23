# ADR 0088: Proxy networks, Waterfall, and Geyser on a proxy

Status: accepted, 2026-11-23. Decided by Claude, item 2 of Jeremiah's gap list: "platform support for Folia, proxy
networks (Velocity and BungeeCord/Waterfall linking several backend servers: templates, forwarding secrets, server
switching, multi-server launch/test), and Geyser + Floodgate for Bedrock players".

## Context

Checked against main first. ADR 0045 and 0046 already gave: Folia (server operations only), a Velocity proxy
workspace with a `velocity-plugin` template and the machine's forwarding secret, a Paper backend opted in with
`proxy=velocity` in another workspace, and Geyser + Floodgate on Paper and Purpur. Their "not done" list is this item:
one proxy and one backend in two workspaces (started apart, the test player never through the proxy), no BungeeCord or
Waterfall, one backend in `velocity.toml`, Geyser only on the plugin servers, and no world tools on Folia.

Measured on omen:

- **Waterfall** is still built (build 615, June 2026, Minecraft 26.2) and published on Fill with a SHA-256 for the jar
  and for each BungeeCord command module (`module:cmd_server` and six more). It prints "Waterfall has reached end of
  life" and speaks one Minecraft version behind Paper (26.2 while Paper is on 26.3): a 26.3 backend kicks a player
  coming through it ("Outdated client! Please use 26.3"). Without its modules in `modules/` it fetches them from the
  retired API v2 and fails, so `/server` and `/glist` do not exist. It prints no "Done", only `Listening on /host:port`,
  and stops with `end`.
- **BungeeCord** itself comes only from md-5's Jenkins, without checksums (ADR 0045); Waterfall runs the same plugins.
- **Geyser 2.12** refuses a loopback Bedrock address: "NetherNet cannot work that way: every player will time out,
  including ones on this machine". ADR 0046 bound Geyser like `server-ip`, which is loopback on every dev server, so
  Bedrock players could never join. GeyserMC publishes Velocity (`velocity`) and BungeeCord (`bungeecord`, Floodgate
  `bungee`) builds beside the Spigot ones, with SHA-256s.

## Decision

1. **Waterfall is a proxy platform** like Velocity (`Platform.WATERFALL`, `proxy()`): `server_setup
   platform=waterfall` installs the newest build of the pinned line (catalog `waterfall = "1.21"`, in
   `checkUpstreamVersions`) and its modules, checked against Fill's SHA-256s, and writes `config.yml` once (loopback
   `127.0.0.1:25577`, offline, `ip_forward: true`, one server `lobby`). The ready line also matches `Listening on /...`;
   the stop command is the platform's (`end`). `plugins` searches Modrinth (waterfall, bungeecord) and Hangar
   (WATERFALL). A backend opts in with `proxy=bungeecord` (`settings.bungeecord: true` in `spigot.yml`; it turns
   Velocity forwarding off and the other way round). BungeeCord itself stays out (no checksums).
2. **A `waterfall-plugin` template**: Java 25, `waterfall-api` from PaperMC's repository, `bungee.yml` with the version
   from Gradle, an empty `Plugin`, `run-waterfall` (the run-task version of run-paper). It is built in
   `verifyTemplates`. It ships no msc-stdlib (there is no BungeeCord adapter yet). `bungee.yml` and `waterfall-api` are
   Waterfall's signs in `Platform.detect`, `bungee.yml` an addon kind in `AddonCheck`, and `api_search` indexes the
   Waterfall API modules.
3. **A network in one proxy workspace**: `server_setup servers=lobby,game` on a proxy makes a Paper backend per name in
   `run/servers/<name>/` (ports from 25566, dev defaults, forwarding from that proxy, the machine's EULA acceptance;
   `version` picks their Minecraft, by default the core's for Velocity and `waterfall-minecraft` (26.2) for Waterfall),
   rewrites the proxy's server list in place (Velocity's `[servers]` table, `try` and `[forced-hosts]`; BungeeCord's
   `servers:` and each listener's `priorities`) and writes `run/servers/network.txt`. Names are checked before
   anything changes, and a network without the EULA accepted is refused then too. `acceptEula` and `version` are read
   by a proxy setup with `servers` (the `network` way in `WayArgs`).
4. **Started and stopped with the proxy** (`Network`, held by `ServerManager`): once the proxy is ready, `server_start`
   and `dev_cycle` start each backend that is not running and wait for its ready line; they keep running across a proxy
   restart, so a `dev_cycle` of a proxy plugin does not restart the network. `server_stop` and the session's end stop
   them (`stop`, then the process tree), unless detached. Each writes its console to `console.log` and its process to
   `.msc-server.pid`, so a backend an earlier core left running is stopped before a new start. `server_command
   "@game list"` types on a backend's console and answers with what it printed in the next 1.5 s. No agent runs on a
   backend: one hub serves one agent per workspace.
5. **The test player through the proxy**: with a network, `player_test` joins the proxy's port (from its config), not
   gated as a world tool. It has no block view there, so `goto`, `walk`, `forward` and `jump` are refused before it
   joins, as is `gamemode` (set it on the backend with `@name`). Commands, `/server <name>`, chat, chat buttons, menus,
   NPCs and the HUD work. A server switch is a re-configuration, which ends the step that caused it; its line says so.
6. **Geyser on a proxy** (`GeyserDownloader.Flavor`): `bedrock=true` on Velocity or Waterfall installs that proxy's
   Geyser and Floodgate builds (cached per download key), with the config in `plugins/Geyser-Velocity/` or
   `plugins/Geyser-BungeeCord/`, fronting the proxy's port. A Geyser built for another platform (after a switch) is
   refused until `bedrock=true` replaces it or `bedrock=false` removes it; a replace removes the other flavor's jars.
7. **Geyser binds all addresses when the server binds loopback** (amends ADR 0046 decision 3): Geyser cannot serve
   Bedrock on loopback, and Floodgate signs every Bedrock player in through Xbox, unlike the offline Java side. An
   explicit non-loopback `server-ip` is still followed.

Verified live on omen: a Velocity network (lobby, game) and a Waterfall network (hub, arena on 26.2) started from one
`server_start`; `player_test` joined through each proxy, ran `/server`, chatted on the other backend and read the
clickable server list; `@arena list` showed the player there; `server_stop` stopped all three processes. Geyser 2.12.1
and Floodgate 2.2.5 loaded on Velocity and Geyser said "Done".

## Consequences

- An AI can build, start, play-test and stop a whole multi-server network from one workspace, on either proxy, with
  Bedrock players on the proxy.
- Backend plugins go into `run/servers/<name>/plugins/` by hand or with `plugins` in a backend workspace; `dev_cycle`
  deploys only to the proxy.
- The tool list grows by about 300 characters (the `servers` argument, `@backend`, the two platform names).

Not done, for later:

- **Folia world, build and player tools.** Every world job would have to run each slice on the thread of the region
  that owns its chunks (`RegionScheduler`), with regions that merge and split between ticks and an undo history safe
  across threads: a rewrite of the Paper agent's job runner, not something to fold into this change. Folia keeps its
  server operations (ADR 0045).
- `dev_cycle` into a backend of the network, an msc agent on each backend (one hub per workspace today), and Floodgate
  on the backends (only needed for its API there).
- A msc-stdlib adapter for BungeeCord, and BungeeCord itself.
