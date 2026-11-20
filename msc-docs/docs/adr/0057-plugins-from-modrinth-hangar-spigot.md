# ADR 0057: Plugins and mods from Modrinth, Hangar and SpigotMC, checked on every call

Status: accepted, 2026-11-20, decided by Claude on Jeremiah's request.

## Context

Jeremiah asked for four things, held to the project's values (quality, speed, few tokens in tool results):

1. Downloading any server jar is one command; the AI never searches for a URL.
2. Validation is automatic and tells the AI exactly what it did wrong.
3. Plugins and mods download simply, from Modrinth, Hangar, SpigotMC and the like, so the AI can build a real server
   with LuckPerms, EssentialsX and so on. The AI decides what to add: no recommendations.
4. Development templates use the latest Gradle and the Gradle plugins each platform recommends.

What was there: `server_setup platform=... version=...` already downloads, checks and installs every platform's server
jar in one call (Paper, Purpur, Folia, Fabric, Quilt, NeoForge, Forge, Velocity; Minestom is the project's own jar).
The templates already use each platform's recommended plugin: run-paper and run-velocity, Fabric Loom, NeoForge
ModDevGradle, ForgeGradle 7, Shadow for Minestom, the foojay toolchain resolver, and the repository's Gradle wrapper.
Nothing installed a third-party plugin or mod, nothing read the jars in `plugins/` or `mods/`, and
`checkUpstreamVersions` (ADR 0039) did not look at Gradle or its plugins.

## Decision

- **One new tool, `plugins`.** `search=` asks Modrinth (filtered to the platform's loaders, server-side projects, mods
  and plugins only), Hangar (Paper family and Velocity) and SpigotMC through Spiget (Paper family), five results each,
  one line per result in the source's own order. `install=` takes ids (`modrinth:<slug>`, `hangar:<slug>`,
  `spigot:<number>`; a bare id is Modrinth's) and installs each with its required dependencies (Modrinth's `required`
  dependencies, Hangar's required plugin dependencies that are Hangar projects; the rest are named as `not installed`).
  With neither it lists the jars. It never ranks or suggests: the AI chooses. CurseForge is left out: its API needs a
  key.
- **The version.** A release built for the server's Minecraft version wins; a pre-release only when no release is. A
  plugin (Paper family, Velocity) with no build for that version gets its newest one with a one-line note, as Bukkit
  plugins usually keep loading; a mod with none is refused with the newest version it has, as mods do not load across
  Minecraft versions. The Minecraft version is the one `server_setup` installed, written to `run/.msc-minecraft`, so
  there is no version argument to get wrong.
- **Downloads** go through the existing `Downloads` (stall limit, `.part` file, checksum). Modrinth's sha512 and
  Hangar's sha256 are checked and the jar is kept in the machine's download store; Spiget gives none, which the result
  says. A file name from a source is used only when it is a plain `.jar` name. An install replaces an older jar with
  the same plugin id instead of adding a second copy. Projects hosted outside the source (EssentialsX on Hangar links
  GitHub) and paid SpigotMC resources are refused in one line; the same project may be on another source.
- **Automatic checks (`AddonCheck`).** Every `plugins` call, `server_start` and `dev_cycle` read the descriptors of the
  jars in the platform's folder (`plugin.yml`, `paper-plugin.yml`, `fabric.mod.json` with its jar-in-jar mods,
  `quilt.mod.json`, `META-INF/neoforge.mods.toml`, `META-INF/mods.toml`, `velocity-plugin.json`) and say, one line
  each, which will not load: built for another platform, a required dependency missing, or one id in several jars;
  `plugins` also names jars with no descriptor, which a start leaves to the server (a mod loader may load a library
  jar). Nothing is printed when all is well. YAML and TOML are read line by line (no parser in the catalog);
  a descriptor in an unusual style reads as having fewer dependencies, never more, so a problem line is never false.
- **Build tooling in the upstream check.** `checkUpstreamVersions` now also lists Gradle (from the wrapper) and the
  Gradle plugins the templates pin: Fabric Loom (Fabric's maven), ModDevGradle, ForgeGradle, run-paper, Shadow and the
  foojay resolver (Plugin Portal). All were current on 2026-11-20 except Gradle, raised from 9.9.1 to 9.9.2; the
  templates copy the repository's wrapper, so they use it too.
- **The tool list guard rises from 16,000 to 16,400 characters.** The tool costs about 400 characters (some 100 tokens
  per conversation) after its text was cut to the minimum and its version argument removed; no existing tool could
  carry search and install without making its own description longer by as much.

## Consequences

- The AI goes from a name to an installed, checksum-checked jar in two calls (`plugins search=`, `plugins install=`),
  and learns about a missing dependency or a wrong-platform jar before the server's own log says it at length.
- Not added to the templates: paperweight-userdev (beta, and only for code against Minecraft internals: it makes the
  first build minutes long), resource-factory (would replace a six-line `plugin.yml`), Shadow for Paper and Velocity
  (nothing to shade until a project adds a library). A project that needs one adds it; the upstream check lists it
  once it is in the catalog.
- Runtime pins that are behind (Fabric API, NeoForge, Forge's latest over its recommended build) are left for the
  usual upstream-check round: they change the agents, not the tooling.
