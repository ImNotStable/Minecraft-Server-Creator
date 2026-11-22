# msc-templates

Bare project scaffolding per platform (Gradle build, standard plugins, metadata, one empty entry point), like the IntelliJ Minecraft Development plugin makes. No sample code, tests or frameworks, so nothing in the project steers how the AI writes code (ADR 0073). `msc-core` scaffolds them through a small Java API; nothing else needs to know how they are stored.

**Phase:** MVP  
**Status:** Bare templates for Paper, Fabric (also Quilt), NeoForge, Forge, Minestom and Velocity, each with only its entry point as code (ADR 0073). Versions come from the repository's catalog; `verifyTemplates` builds each one.

## Templates

| Id | What you get |
| --- | --- |
| `paper-plugin` | Paper plugin: paperweight-userdev (Paper server, Mojang-named, to compile against), resource-factory (`plugin.yml` from `bukkitPluginYaml { }`), run-paper (`./gradlew runServer`, dev server in `run/`), a `JavaPlugin` with empty `onEnable`/`onDisable`. |
| `fabric-mod` | Fabric server mod: Fabric Loom (server jar only, `./gradlew runServer` in `run/`), `fabric.mod.json`, an empty `DedicatedServerModInitializer`. |
| `neoforge-mod` | NeoForge server mod: ModDevGradle (`./gradlew runServer` in `run/`, `unitTest` set up), `neoforge.mods.toml`, an empty `@Mod` class. Mod id is the project id with `_` for `-`. |
| `forge-mod` | Minecraft Forge server mod: ForgeGradle 7 (`./gradlew runServer` in `run/`), `mods.toml` and `pack.mcmeta`, an empty `@Mod` class. |
| `minestom-server` | Minestom server: Shadow (runnable jar) and `application` (`./gradlew run`), a `main` that starts a flat in-memory world with the msc agent (`libs/msc-agent-minestom.jar`, idle unless msc-core started the server; it reads `stop` and console commands from standard input while msc-core runs the server). |
| `paper-server` | Only the dev server config, for a server directory the core manages itself: `eula.txt` and `server.properties` (offline mode, bound to 127.0.0.1, RCON on, flat creative world with explicit layers, peaceful, no nether or structures). `eula=false` unless the variables say the user accepted it.. |

## Using it from msc-core

```java
import io.github.imnotstable.msc.templates.*;

TemplateVariables vars = TemplateVariables.of("Spawn Lobby")      // derives everything else
        .withPackageName("dev.example.lobby");                      // optional overrides
ScaffoldResult result = Templates.scaffold(Templates.PAPER_PLUGIN, targetDir, vars);
result.files();                       // every file written
result.values().get("rconPassword");  // plus rconPort, serverPort, serverIp, mcVersion, ...
```

- `Templates.ids()`: packaged template ids. `Templates.info(id)`: `platform` and `description`. `Templates.versions()`: versions pinned into scaffolded projects.
- `Templates.scaffold(id, target, vars)` throws `IllegalArgumentException` for an unknown id, `IllegalStateException` if `target` exists and is not an empty directory (it never overwrites; a directory holding only MSC's own `.msc` folder counts as empty), and `IOException` if writing fails.
- After scaffolding `paper-plugin`: `gradlew build` (or `gradlew.bat build`) in the target builds it; the jar is `build/libs/<projectId>-<version>.jar`.

### Variables

`TemplateVariables.of(name)` splits the name into words (`"spawn lobby"`, `"spawn-lobby"` and `"SpawnLobby"` all work; digits stay with their word, so `"E2eDemo"` gives `e2e-demo`) and derives:

| Variable | Default for "Spawn Lobby" | Override |
| --- | --- | --- |
| `name` (plugin name) | `SpawnLobby` | `withName` |
| `projectId` | `spawn-lobby` | `withProjectId` |
| `packageName` | `com.example.spawnlobby` | `withPackageName` |
| `mainClass` | `SpawnLobbyPlugin` (no second `Plugin` suffix) | `withMainClass` |
| `version` | `0.1.0-SNAPSHOT` | `withVersion` |
| `description` | `SpawnLobby, made with Minecraft Server Creator.` | `withDescription` |
| `author` | empty | `withAuthor` |
| `serverIp` | `127.0.0.1` | `withServerIp` |
| `serverPort` | `25565` | `withServerPort` |
| `rconPort` | `25575` | `withRconPort` |
| `rconPassword` | 24 random hex characters | `withRconPassword` |
| `levelType` | `minecraft:flat` (also sets `generator-settings` layers) | `withLevelType` |
| `acceptEula` | `false`: writes `eula=false`. Set it only when the user has accepted the [Minecraft EULA](https://aka.ms/MinecraftEULA) | `withAcceptEula(boolean)` |

Invalid values throw `IllegalArgumentException` when the variables are built, including a package segment that is a Java keyword or literal (name `Void` gives `com.example.void`) and a `projectId` outside 2-64 characters (it is also the mod id). `scaffold` also refuses variables that would name a class like one of its imports (`Minecraft` with a Minestom template gives `MinecraftServer`) or write two files to one path, before writing anything. Pinned versions (`javaVersion`, `mcVersion`, `paperVersion`, `mockbukkitVersion`, `junitVersion`, `assertjVersion`, `runPaperVersion`, `foojayVersion`, `fabricLoaderVersion`, `fabricApiVersion`, `fabricLoomVersion`, `minestomVersion`, `minestomMcVersion`, `slf4jVersion`, `shadowVersion`) come from the repo's version catalog at build time and are also available as placeholders.

## Writing a template

- Sources live in `src/main/templates/<id>/`. Add `template.properties` with `platform` and `description`.
- `{{variable}}` works in file contents and in paths (`src/main/java/{{packagePath}}/{{mainClass}}.java`). An unknown placeholder fails the scaffold, and a test checks no `{{` survives.
- Name a file `*.tmpl` when it would otherwise be picked up by this repo's Gradle or git (`build.gradle.kts.tmpl`, `.gitignore.tmpl`); the suffix is dropped when scaffolding.
- Binary files (`.jar`, `.png`, ...) are copied as is. `gradlew` is made executable.
- Text a template ships (CLAUDE.md, README.md, Javadoc, comments) speaks to the author of that one project. Rules about keeping templates alike belong here and in this module's tests, not in a scaffold.
- Every Java name a template's CLAUDE.md or README.md writes in backticks must be in the template's own code (`TemplateDocNamesTest`). A template ships only its entry point as Java code and no tests (`TemplatesTest.aProjectHasOnlyItsEntryPointAsCode`, ADR 0073).
- The `packTemplates` task copies the sources into the jar under `msc/templates/`, adds the repo's Gradle wrapper to every project template, the Minestom agent jar to `minestom-server`, nests `paper-server` under the `run/` of `paper-plugin`, `fabric-mod`, `neoforge-mod` and `forge-mod`, and writes `index.txt`, `<id>/files.txt` and `versions.properties`.

## Tests

- `./gradlew :msc-templates:test`: the API, variables and rendering (runs anywhere).
- `./gradlew :msc-templates:verifyTemplates`: scaffolds every project template (`paper-plugin`, `fabric-mod`, `neoforge-mod`, `forge-mod`, `minestom-server`, `velocity-plugin`) into `build/verify/` and runs each one's own `gradlew build tasks --all`, so the templates compile against Paper, Fabric, NeoForge, Forge, Minestom and Velocity and every task their docs name exists. The builds share one Gradle daemon in its own registry under `build/verify/daemons/`. Needs Java 25, repo.papermc.io, maven.fabricmc.net, maven.neoforged.net, maven.minecraftforge.net and Maven Central, so it is not part of `check`.

## Decisions

See [ADR 0003](../msc-docs/docs/adr/0003-templates.md); the Fabric template in [ADR 0009](../msc-docs/docs/adr/0009-fabric-agent.md). Bare templates: [ADR 0073](../msc-docs/docs/adr/0073-bare-templates.md).
