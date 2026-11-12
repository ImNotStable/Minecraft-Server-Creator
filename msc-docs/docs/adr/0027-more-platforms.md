# ADR 0027: NeoForge, Forge, Quilt and Purpur

Status: accepted, 2026-11-12. Decided by Claude under Jeremiah's hand-over of build decisions, on Jeremiah's request
"Add other server types like Forge, NeoForge, Quilt, etc".

## Context

MSC supported Paper, Fabric and Minestom (ADR 0011). Jeremiah asked for more server types. All current loaders target
Minecraft 26.3, the version MSC pins: NeoForge 26.3.0.102, Forge 26.3-66.1.5, Quilt Loader 0.30.2 and Purpur 26.3
build 2658 were the newest on 2026-11-12. Minecraft 26.x ships unobfuscated, so a mod that only touches vanilla classes
compiles to the same bytecode on every loader: the loaders differ in their metadata file and their event APIs, not in
how Minecraft's own classes are named.

The Fabric agent was already written as thin loader wiring around vanilla adapters (`FabricLevel`, `FabricLevels`,
`FabricServerAccess`, `TickMainThread`, `BlockStates`, `LevelNames`), which used nothing from Fabric except the loader's
own version.

## Decisions

1. **Six platforms with a server to install, one without.** `Platform` gains `PURPUR`, `QUILT`, `NEOFORGE` and
   `FORGE`, each with its server jar, `plugins/` or `mods/`, an EULA step, and the agent it runs (`Platform.agent()`).
   Platforms that run another's add-ons share its agent and API index: Purpur runs `msc-agent-paper`, Quilt runs
   `msc-agent-fabric`. The installed agent is named for the agent (`mods/msc-agent-fabric.jar` on Quilt).
2. **Shared vanilla code, not a fourth copy.** The Minecraft side of the Fabric agent moves to
   `msc-agent-vanilla/src/main/java` (package `io.github.imnotstable.msc.agent.vanilla`) as `VanillaLevel`,
   `VanillaLevels`, `VanillaServer`, `TickMainThread`, `BlockStates`, `LevelNames` and a `VanillaAgent` that owns the
   life cycle (`starting`, `started`, `stopping`, `tick`, `joined`, `quit`). Each loader's entry point only forwards its
   events to `VanillaAgent` and names itself with a `Loader(platform, version)`.
   - It is a **source folder, not a Gradle project**: each agent module adds it with `sourceSets.main.java.srcDir` and
     compiles it against its own Minecraft. A project dependency would put a Loom-built jar on a ModDevGradle class path
     (or the reverse), whose Minecraft variants do not match. The classes are compiled three times, which is cheap.
   - It is **tested once, in msc-agent-fabric**, whose `fabric-loader-junit` already bootstraps Minecraft; the Fabric
     module adds `msc-agent-vanilla/src/test/java` as a test source folder.
3. **`msc-agent-neoforge`** builds with NeoForge's ModDevGradle (`net.neoforged.moddev`, repositories plugin in
   `settings.gradle.kts` like Loom). Entry point `MscAgentNeoForge`, `@Mod(dist = DEDICATED_SERVER)`, listeners on
   `NeoForge.EVENT_BUS`. Mod id `msc_agent` (NeoForge ids allow no hyphens).
4. **`msc-agent-forge`** does not use ForgeGradle: ForgeGradle 7 declares project repositories, which this build
   forbids (`FAIL_ON_PROJECT_REPOS`). It uses ModDevGradle's vanilla mode (`neoFormVersion`) for plain Minecraft and
   compiles against Forge's own API jars (`forge:universal`, `fmlcore`, `javafmllanguage`, `eventbus`) as
   `compileOnly`. Entry point `MscAgentForge` with EventBus 7's per-event `BUS.addListener`.
5. **Both mod jars shade** msc-agent-common and msc-protocol (Shadow) instead of jar-in-jar: the packages are unique to
   the agent, so they sit in its module without conflicts, and the setup is the same on both loaders.
6. **Quilt runs the Fabric agent.** Quilt Loader loads Fabric mods and answers as `fabricloader` 0.19.x, so the agent's
   `fabricloader` floor drops from 0.20.1 to 0.17.0 (it uses no newer loader API). `MscAgentMod` reports `quilt` when
   `quilt_loader` is present. Quilt's own QSL stopped at 1.21.1; Fabric API 0.166.0 runs on Quilt and is installed as
   on Fabric. The `fabric-mod` and `fabric-game` templates get the same floor, so their mods run on Quilt too. There is
   no Quilt template: a Quilt workspace uses `fabric-mod` and `server_setup platform=quilt`; `dev_cycle` keeps the
   workspace's Quilt platform for a Fabric project (and Purpur for a Paper project).
7. **Installing.** `LoaderInstaller` in msc-core downloads to `.msc/cache` and runs each project's own installer in the
   server directory, as the projects document: NeoForge `--install-server <dir> --server-jar` (its server starter
   jar becomes `server.jar`), Forge `--installServer <dir>` (its runnable `forge-<version>-shim.jar` is copied to
   `forge-server.jar`, so the platform has a fixed jar name), Quilt `install server <mc> <loader> --download-server`.
   An installer only runs when the server directory lacks that loader version. Purpur comes from its own API
   (`/v2/purpur/<mc>/latest`). Forge's installer asks automated users to support the project; MSC runs it once per
   version and caches it.
8. **Versions** are pinned in `gradle/libs.versions.toml` (`neoforge`, `neoform`, `moddev`, `forge`,
   `forge-eventbus`, `forgegradle`, `quilt-loader`, `quilt-installer`) and reach the core through the templates'
   `versions.properties`. `server_setup` refuses a Minecraft version the pinned loader does not target, before any
   download. Purpur takes any version its API knows.
9. **Templates** `neoforge-mod` (ModDevGradle, tests run inside NeoForge with `unitTest`, including the real Brigadier
   command test) and `forge-mod` (ForgeGradle 7, its own project repositories, plain JUnit tests). Both carry the same
   `/hello` command, join greeting, `CLAUDE.md` conventions and `run/` dev server as `fabric-mod`. Game templates for
   the new loaders are left out until a game is built on one.
10. **Platform detection** from project files: `quilt.mod.json` is Quilt, `fabric.mod.json` Fabric,
    `META-INF/neoforge.mods.toml` NeoForge, `META-INF/mods.toml` Forge. Purpur cannot be told from a Paper plugin, so it
    is only the remembered platform.
11. **Not added.** Folia: its region threads make the Bukkit scheduler the Paper agent uses throw, so it needs its own
    agent; worth it only when someone wants a Folia server. Velocity, BungeeCord: proxies, not game servers; nothing for
    the agent to build in. Spigot/Bukkit: Paper covers their API with a supported download service.
12. **The EULA rule is unchanged** (ADR 0020): every new platform runs Mojang's server, so `server_start` refuses until
    `eula.txt` or the owner's machine acceptance says yes. Nothing here accepts it.

## Consequences

- `server_setup platform=neoforge|forge|quilt|purpur` installs the server and the agent; checked end to end in the cloud
  through an installed core for all four, up to the EULA step. NeoForge and Forge list `msc_agent` as a valid mod on
  their real 26.3 servers, Quilt loads the Fabric agent and Fabric API up to the EULA step. The agents' operations on a
  running NeoForge, Forge or Quilt world still need one check on a machine whose owner accepted the EULA.
- A change to the Minecraft side of the agents is made once, in `msc-agent-vanilla`, and tested in the Fabric module.
- The NeoForge and Forge modules add about 100 s to a cold build (ModDevGradle decompiles and recompiles Minecraft
  once per version; cached afterwards).
- `api_search` on NeoForge and Forge indexes the loader's API jars from the Gradle cache (`net.neoforged:neoforge`,
  `net.minecraftforge:forge`, eventbus); Minecraft's own classes are not indexed there yet.
