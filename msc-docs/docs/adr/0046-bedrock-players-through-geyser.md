# ADR 0046: Bedrock players through Geyser on `server_setup`

Status: accepted, 2026-11-15, decided by Claude.

## Context

Backlog item 10: an AI developing a Paper server should be able to make it reachable by Bedrock players (phones,
consoles, Windows) and keep that in mind while developing. The standard way is [Geyser](https://geysermc.org), a
protocol translator that listens for Bedrock clients on UDP (19132 by default) and joins the Java server for them,
plus Floodgate, which lets a Bedrock player in with their Xbox sign-in and no Java account.

What is already there:

- `server_setup` installs Paper (`PaperDownloader`, sha256 from Fill) or Purpur, caches jars in `.msc/cache` and keeps
  checksummed ones in the machine's `DownloadStore` (ADR 0038), and copies the msc agent into `plugins/` through a
  staging folder (`ScanFolders.copy`, #417).
- `server.properties` is read only through `ServerPropertiesFile` (ADR 0033). The dev defaults bind the server to
  loopback (`server-ip=127.0.0.1`) in offline mode: a local dev server.
- The tool list is at its guard (30 tools, under 16,000 characters), so no new tool.

GeyserMC publishes builds through a download API (`https://download.geysermc.org/v2`):
`/v2/projects/{geyser|floodgate}/versions/latest/builds/latest` answers the newest build's `version`, `build` and,
per platform, the jar's `name` and `sha256`; the jar is at `.../versions/{version}/builds/{build}/downloads/spigot`.
Paper and Purpur load the Spigot builds (`Geyser-Spigot.jar`, `floodgate-spigot.jar`).

Geyser's `config.yml` (layout version 12 as of Geyser 2.12) has `bedrock.address`, `bedrock.port`, `java.auth-type`
and `config-version`. Geyser fills a key that is missing with its default, rewrites the file only when it is empty or
its version is migrated, and migrates older layouts itself. On a plugin platform (Geyser-Spigot) the `java` address
and port are not settings: Geyser fronts the server it runs in.

## Decision

1. **An opt-in on `server_setup`, no new tool**: `bedrock` (boolean). `bedrock=true` on Paper or Purpur downloads the
   newest Geyser-Spigot and Floodgate builds from the GeyserMC API (`GeyserDownloader`), checks each against the API's
   sha256 (an answer without one is refused), caches them as `.msc/cache/<project>-spigot-<build>.jar`, keeps them in
   the machine's store, and copies them into `plugins/` under the API's names, so a newer build replaces the older one.
   Offline, the newest cached build is used, as for Paper. The downloads run in the setup's install step; placing the
   jars and the config is its own step after the agent (`Steps`).
2. **A folder that has Geyser keeps it up to date**: a later `server_setup` without `bedrock` updates both jars too.
   `bedrock=false` removes the two jars and keeps their config folders. Other platforms refuse `bedrock=true` in one
   line before anything changes (Geyser has Fabric and NeoForge builds, but this slice covers the plugin platforms).
3. **Geyser's config is written once, then it is the user's** (`BedrockConfig`). When
   `plugins/Geyser-Spigot/config.yml` is missing, it is written with only `bedrock.address`, `bedrock.port`,
   `java.auth-type: floodgate` and `config-version: 12`; Geyser supplies every other default. `bedrock.address` follows
   `server-ip` (loopback for the dev defaults, all addresses when it is empty), so the Bedrock port is no more exposed
   than the Java one; `0.0.0.0` in the file lets phones and consoles on the LAN in, and the result says so. The port is
   the first free UDP port from 19132 (100 tried). A rerun changes nothing in an existing file except `bedrock.port`,
   and only when another program holds it (moved to the first free one, said in the result); comments, order and the
   user's other edits stay.
4. **The Bedrock port is reported in one line**, `bedrock: UDP <address>:<port> (Geyser, fronting Java port <port>;
   ...)`, by `server_setup` and in `server_start`'s and `dev_cycle`'s start report after the mode line, when Geyser is
   in `plugins/` on Paper or Purpur. The Java port is `server-port` as the server reads it (`ServerAddress`).
5. Where the API lives is `LoaderInstaller.Urls.geyserApi` (tests point it at a local fake, like the other services).

## Consequences

- An AI can make its dev server reachable by Bedrock players with one argument and sees the port on every start.
- The dev server stays local by default; opening it to the LAN is a deliberate edit of Geyser's config.
- Geyser's newest build is taken, not a pinned one: Geyser follows new Minecraft versions quickly, and a pin would
  need a bump per release. A Geyser that does not yet support the server's Minecraft version fails at start in the
  server log (`server_logs mode=triage` shows it as a plugin that failed to enable).
- Floodgate on an offline-mode server still lets Bedrock players in (with its name prefix); the Java side stays
  unauthenticated as before.

What is left, not in this slice:

- **Velocity-side Geyser**: with a proxy, Geyser and Floodgate belong on the proxy (Floodgate also on each backend,
  sharing its `key.pem`). The proxy work (backlog item 9) is on branch `claude/backlog-folia-proxies`, not merged;
  this follows it.
- **Bedrock resource packs**: the `pack` tool builds a Java pack. Bedrock clients need a converted pack (Geyser's
  `packs/` folder, a tool such as java2bedrock); custom items and blocks need Geyser mappings. Not done.
- **A Bedrock test client**: none exists here. `player_test` joins over the Java protocol (msc-bot, MCProtocolLib);
  nothing in MSC speaks the Bedrock protocol, so Bedrock play is checked only by a person with a Bedrock client.
- **Not run live**: no real Paper start with Geyser was made here (that needs the EULA accepted). The written
  `config.yml` follows Geyser's source (layout version 12); that Geyser 2.12 reads the short file as described is
  unverified. On Windows, a Bedrock client on the same machine needs a loopback exemption to reach `127.0.0.1`.
