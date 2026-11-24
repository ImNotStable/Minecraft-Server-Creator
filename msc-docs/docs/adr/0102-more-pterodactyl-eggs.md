# ADR 0102: Pterodactyl eggs for NeoForge, Forge, Quilt and Minestom

Status: accepted, 2026-11-24. Decided by Claude, from Jeremiah's open list (ADR 0091's "not done").

## Context

ADR 0091's `server_export target=pterodactyl` covered the platforms whose server is one download (Paper, Purpur,
Folia, Fabric, Velocity, Waterfall) and refused NeoForge, Forge, Quilt and Minestom: their installers are Java programs
and Pterodactyl's `installers:alpine` image has no Java; Minestom's server is the project's own jar.

## Decision

1. **Mod loaders install in `eclipse-temurin:25-jdk`** (bash, `apt-get install curl jq`), running the loader's own
   installer for a new `LOADER_VERSION` variable that defaults to the version the dev server runs (pinned in this build
   of msc-core):
   - NeoForge: `neoforge-<v>-installer.jar --install-server . --server-jar`, which leaves `server.jar`.
   - Forge: `forge-<v>-installer.jar --installServer .`, then its `forge-<v>-shim.jar` copied to `forge-server.jar`.
   - Quilt: `quilt-installer` (the pinned installer version) `install server VERSION LOADER_VERSION --download-server`,
     which leaves `quilt-server-launch.jar` and the vanilla `server.jar` it starts.
   Each egg's `SERVER_JARFILE` is the dev server's jar name, so the startup line is the same `java ... -jar` as the
   others. Updates are a reinstall after changing `VERSION` or `LOADER_VERSION`; mods go in `mods/` (README).
2. **Minestom**: the egg installs nothing; `server-files.zip` carries the project's jar as `server.jar`. The Minestom
   template's entry point now binds `SERVER_IP` and `SERVER_PORT` when set (Pterodactyl sets both; the Docker export's
   Dockerfile sets `0.0.0.0:25565`), the dev address otherwise, and prints `Minestom listening on IP:PORT`, the egg's
   ready line. Before this, an exported Minestom server bound the dev address (127.0.0.1) in Docker too. A project
   scaffolded earlier needs those two lines in its main; the export's note says so.
3. With every platform covered, the refusal is gone; a proxy network still asks for one server per member.

Per ADR 0101, nothing here generates content: the eggs install and run what the agent made.

## Verified on omen

For NeoForge 26.3.0.102, Forge 26.3-66.1.9 and Quilt 0.30.2 on Minecraft 26.3: the export from a real `server_setup`,
the egg's install script run in `eclipse-temurin:25-jdk` with the egg's variables, then `server-files.zip` unpacked and
the egg's startup line run in `ghcr.io/pterodactyl/yolks:java_25`: each reached the egg's ready line ("Done (...)! For
help, type") within 20 s. The Minestom egg and the template change are covered by `ServerExportTest` and the template
build; no Minestom container run.

## Consequences

- Every server platform MSC develops for can be shipped to a Pterodactyl panel.
- Not done: importing the egg into a panel (needs its admin key), and eggs for a whole proxy network.
