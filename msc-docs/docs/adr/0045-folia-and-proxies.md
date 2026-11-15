# ADR 0045: Folia servers and a Velocity proxy

Status: accepted, 2026-11-15, decided by Claude (backlog item 9).

## Context

MSC develops for seven platforms; each workspace has one (ADR 0011, 0027), with one server process in `run/` that the
core starts, watches and stops, and one msc agent inside it for the world, build and player tools. Backlog item 9 asks
for Folia servers and Velocity or BungeeCord/Waterfall proxies, so an AI can develop for them as it does for Paper.

What each needs, measured against that model:

- **Folia** is Paper with regionised multithreading. PaperMC publishes it on the same Fill API v3 as Paper (project
  `folia`, versions named like Minecraft's, builds with SHA-256). It loads only plugins whose `plugin.yml` says
  `folia-supported: true`, and it has no main thread: `Bukkit.getScheduler()` throws, console commands run on the
  global region thread, and a chunk may only be touched from the thread of the region that owns it at that tick. The
  Paper agent schedules everything on the Bukkit scheduler, and its world jobs (`SlicedJob`s over whole regions,
  `EditHistory`) assume one thread that owns every chunk.
- **Velocity** is a proxy, not a Minecraft server: no world, no EULA, plugins in `plugins/`, its own API
  (`com.velocitypowered:velocity-api`, 4.3.1 now). Fill publishes it as project `velocity` with its own version line.
  It prints `Done (Xs)!` when ready and takes `stop` (an alias of `shutdown`) on its console. The real use case is a
  proxy plus a backend: players join the proxy, which forwards them to a Paper server; with modern forwarding the
  proxy's `forwarding.secret` must equal the backend's `proxies.velocity.secret` in `config/paper-global.yml`, and the
  backend then refuses direct joins.
- **BungeeCord** comes only from SpigotMC's Jenkins (`lastSuccessfulBuild`, no checksum, no versioned API);
  **Waterfall** is end of life since 2024. Velocity is PaperMC's supported proxy.

Limits: no new MCP tools, the tool list under 16,000 characters (15,879 by the tools.md count on main before this change), `msc-protocol` and the
other shared files untouched where possible.

## Decision

1. **Two new platforms**, `folia` and `velocity`, in `Platform`. `PaperDownloader` takes the Fill project from the
   platform (`paper`, `folia`, `velocity`), so both download, checksum, cache (`<project>-<version>-<build>.jar`, offline
   fallback) and use the machine's download store exactly as Paper does. `server_setup platform=folia` installs
   `folia.jar`, Paper's dev defaults and the Paper agent, and says in one line that plugins need
   `folia-supported: true` and that world, build and player tools do not work there. Folia needs the EULA like Paper.
2. **The Paper agent runs on Folia, server operations only.** `plugin.yml` says `folia-supported: true`. On Folia
   (detected by its `RegionizedServer` class) the agent runs `server.info`, `server.command` and `pack.apply` on the
   global region scheduler (`GlobalRegionMainThread`) and reports platform `folia`; it does not route the world
   operations (`AgentRoutes.serverOnly`). The core already refuses an operation an agent does not list; that line now
   ends `; server_command still works`. World edits on Folia would need each slice run on the thread of the region that
   owns its chunks, regions that merge and split between ticks, and an undo history safe across threads: not a slice
   to ship untested.
3. **Velocity as a proxy workspace.** `server_setup platform=velocity` installs the Velocity version pinned in the
   catalog (`velocity = "4.3.1"`; it serves every recent Minecraft version, so `version` is not used), writes
   `velocity.toml` when it is missing (Velocity 4.3's defaults, config version 2.9, except: bind `127.0.0.1:25577`,
   offline mode, no key authentication, modern forwarding, one server `lobby = 127.0.0.1:25565`), writes
   `forwarding.secret`, and creates `plugins/`. No defaults, game mode, agent or EULA step. It starts as
   `java -Xmx512M -jar velocity.jar`; `dev_cycle` deploys a plugin to `plugins/` and restarts it, as for Paper.
4. **The backend opts in with `proxy=`**, one new `server_setup` argument (no new tool): `proxy=velocity` on a Paper,
   Purpur or Folia workspace sets `proxies.velocity` `enabled: true`, `online-mode: false` and the secret in
   `config/paper-global.yml` (only those lines; the file is written when missing, Paper fills in the rest), and
   `proxy=none` sets `enabled: false`. Refused on any other platform before anything changes. The proxy and the backend
   live in two workspaces (one server per workspace stays the rule), so the secret is the **machine's**:
   `~/.msc/velocity.secret` (32 random hex digits, made once; `CoreConfig.proxySecret`), copied into each proxy's
   `forwarding.secret` on every setup. The dev backend binds loopback and runs offline, as every dev server does.
5. **World, build and player tools on a proxy answer one line**: `Velocity is a proxy with no world or msc agent:
   world, build and player tools do not apply; use them in the backend's workspace`, from `AgentHub.require` (every
   world and build tool goes through it) and `player_test`, instead of waiting for an agent and timing out. The start
   report says `a proxy: no world or agent`. `server_command`, `server_logs` and `server_wait` use the proxy's console.
6. **A `velocity-plugin` template**: Java 25, `velocity-api` as `compileOnly` and annotation processor (the `@Plugin`
   annotation becomes `velocity-plugin.json`), a `/hello` `SimpleCommand`, a `PostLoginEvent` greeting, a plain JUnit
   test of the logic (Velocity has no mock server), `run-velocity` (same run-task version as `run-paper`) for
   `./gradlew runVelocity`. It ships no `run/` server files. Plugin ids are the project id; `velocity` is refused.
   `Platform.detect` reads `velocity-api` in the build files as Velocity, and `api_search` indexes the Velocity API and
   Adventure from the Gradle cache.
7. **Tool text**: `platform` lists `folia` and `velocity`, `proxy` is one short argument, `project_scaffold` names
   `velocity-plugin`. The additions are paid for by trims, so the list shrinks (by 12 characters: 15,879 to
   15,867 as tools.md counts it, with the Geyser `bedrock` argument of ADR 0046 in): `api_search` no longer
   lists the platforms, `api_class`'s example is `Player` only, `platform` drops "Default: the project's", and the
   minigame templates and two folder arguments are worded shorter.

## Consequences

- An AI can scaffold, set up, build, deploy, start, stop and read the logs of a Folia server or a Velocity proxy, and
  wire a Paper backend to the proxy with one argument, all against local fakes in the tests.
- On Folia, `world_*`, `build_batch`, `build_undo`, `structure_save` and `player_test`'s world-reading actions refuse
  with the agent's missing operation; the start report has no ground line there. The AI uses `server_command`.
- A backend set up with `proxy=velocity` refuses direct joins, the test player included: `player_test` works only on a
  backend without it. Turning it off is `proxy=none`.
- The machine EULA acceptance now also covers Folia, which runs Mojang's server like Paper (TERMS.md says so).
- Shared files touched: `gradle/libs.versions.toml` (the `velocity` version, read by `msc-templates` and so by
  `server_setup`), `msc-agent-common` (`AgentRoutes.serverOnly`). `msc-protocol` is unchanged.

Not done, for later:

- World, build and player tools on Folia: run each slice of a world job on its region's thread
  (`RegionScheduler.execute(plugin, world, chunkX, chunkZ, ...)`), with jobs split by region and an undo history that
  is safe across region threads.
- A proxy and its backend in one workspace, started and stopped together by one `server_start`/`dev_cycle`, and
  `player_test` joining through the proxy. Today the backend is another workspace (`msc_workspace` plus
  `server_detach`, or a second session).
- BungeeCord (Jenkins builds without checksums) and Waterfall (end of life): `bungeeguard`/`ip_forward` setup and a
  BungeeCord template.
- Velocity versions other than the pinned one, and more than one backend in `velocity.toml` (edit it by hand; setup
  keeps an existing file). The upstream version check (ADR 0039) does not yet list Velocity.
- `verifyTemplates` builds `velocity-plugin` and its doc names against the real Velocity 4.3.1 API. The rest is
  verified only against fakes: no live Folia or Velocity run was possible in the cloud session (it needs the PC). In
  particular unverified: Folia firing `ServerLoadEvent`, `dispatchCommand` with a custom sender on Folia's global
  region thread, Paper accepting a `paper-global.yml` without `_version` (it should assume the newest), and Velocity
  4.3.1 accepting the written `velocity.toml`.
