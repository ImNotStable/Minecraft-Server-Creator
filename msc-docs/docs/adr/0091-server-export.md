# ADR 0091: server_export for Docker and Pterodactyl, with backups and updates

Status: accepted, 2026-11-23. Decided by Claude, item 5 of Jeremiah's gap list: "shipping: Docker/Pterodactyl export,
scheduled backups, update handling".

## Context

Checked against main first: MSC backs the dev world up before edits (ADR 0041, 0069) and checks its own pinned
versions upstream (ADR 0039), but nothing turned the dev server into something to run for players, nothing scheduled
backups of a running server, and nothing kept a deployed server or its plugins up to date. `plugins install=` did not
record where a jar came from.

The established ways, measured on omen (Docker 29):

- **itzg/minecraft-server** and **itzg/mc-proxy** download the server for `TYPE` and `VERSION` (Paper, Purpur, Folia,
  Fabric, Quilt, NeoForge, Forge; Velocity, Waterfall) on every start, newest build first, and fetch plugins from
  Modrinth (`MODRINTH_PROJECTS`) and SpigotMC (`SPIGET_RESOURCES`). **itzg/mc-backup** backs a world up over RCON
  on an interval and prunes old backups. All three are the standard community images.
- **Pterodactyl** eggs (`PTDL_v2` JSON) install a server with a script in `ghcr.io/pterodactyl/installers:alpine`
  and run it in a `yolks` Java image; the panel has its own schedules and backups.

## Decision

1. **A new tool, `server_export target=docker|pterodactyl dir= world=`**: shipping had no tool. The tool-list guard
   rises by 400 characters.
2. **Docker**: a Compose project on the itzg images: `EULA: ${EULA:-FALSE}` (the user sets `EULA=TRUE` in `.env`;
   MSC never does), `TYPE`, `VERSION`, loader versions, `MEMORY 2G`, RCON with a random password, the recorded
   Modrinth and SpigotMC plugins as update sources, the other jars copied, and an mc-backup service (every
   `BACKUP_INTERVAL`, 6h, kept 7 days). A proxy network (ADR 0088) becomes one project: the proxy on `itzg/mc-proxy`
   listening on all addresses, a service per backend with its own backups, the proxy's config pointing at
   `<name>:25565`. Minestom gets a Dockerfile with the project's jar (no RCON, so no backup service).
3. **Pterodactyl** (Paper, Purpur, Folia, Fabric, Velocity, Waterfall: the platforms whose server jar one download
   gives): an egg whose install script fetches the newest build of `VERSION` (Fill, Purpur's API, Fabric's meta; for
   Waterfall its modules too), the `java_25` yolk, the right ready line and stop command, and `server-files.zip`.
   Updates are a reinstall; backups are the panel's schedules (README).
4. **What ships**: configs and plugin data folders, not the server jar, the msc agent, logs, caches, `eula.txt`, the
   dev pack host's local URL (cleared, with a note to host the pack), or the world unless `world=true` (refused while
   the server runs). `server.properties` gets an empty `server-ip`, port 25565 and `online-mode=true` unless behind a
   proxy; Geyser binds all addresses.
5. **`plugins install=` records its source** in `run/.msc-plugins` (`modrinth:chunky Chunky-Paper-1.5.66.jar`).

Verified on omen: a Paper export started from `docker compose up` with `EULA=TRUE`: the image fetched Chunky from
Modrinth, loaded the copied plugin, and `mc-backups backup now` wrote a world tarball. The Velocity network export
(proxy, lobby, game, Geyser) started, and the test player joined through the containerized proxy and switched to
`game`. The egg's install script, run in `installers:alpine`, downloaded Paper 26.3, and the `java_25` yolk with the
unzipped files and the egg's startup line reached "Done". The panel running on omen was not touched.

## Consequences

- One call turns a finished game into a deployable server with updates and backups.
- The image updates the server build and the recorded plugins; a new Minecraft version is a change of `VERSION`, and
  the user's own plugins are rebuilt and copied, as the README says.
- Hangar plugins and other jars are copied, not updated by the image (it has no Hangar source).
- Not done: NeoForge, Forge, Quilt and Minestom eggs (their installers are not one download), and importing the egg
  into a panel (that needs the panel's admin key).
