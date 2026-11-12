# msc-templates

Starter server projects per platform, ready for the AI to build on, with tests and conventions wired in. `msc-core` scaffolds them through a small Java API; nothing else needs to know how they are stored.

**Phase:** MVP  
**Status:** Paper plugin and Paper dev server templates done and verified (Java 25, Paper 26.3.build.248, MockBukkit 4.120.0). Fabric mod template done and verified (Fabric Loader 0.20.1, Fabric API 0.166.0+26.3, Loom 1.19.4). Minestom server template done and verified in the cloud (Minestom 2026.10.07-26.2). Paper game template (`paper-game`) builds and passes its 15 tests against Paper 26.3.build.249 and MockBukkit 4.120.0. Minestom game template (`minestom-game`, 14 tests, `@EnvTest` game tests) and Fabric game template (`fabric-game`, 9 tests) build and pass in the cloud; `fabric-game`'s player paths still need a check on a real Fabric server.

## Templates

| Id | What you get |
| --- | --- |
| `paper-plugin` | A Paper plugin project: Java 25, Gradle KTS with its own wrapper and version catalog, a `/hello` command (Brigadier) and a join listener, a plain JUnit test and a MockBukkit test, `CLAUDE.md` conventions for the AI, and a dev server in `run/` (`./gradlew runServer`, via run-paper). |
| `paper-game` | Everything in `paper-plugin`, plus a minigame base set from `config.yml`: join kit, adventure mode, keep inventory, respawn at the current stage, no natural mob spawns, frozen time and weather, difficulty, an ordered list of stages with spawn points and goal boxes, progress saved to `progress.yml`, and a `/game` admin command (`status`, `stage <name or n> [player]`, `next`, `reset`, `kit`) that works from the console, so an AI can check state and skip to any stage while testing. Built from what the Lumen Siege and Gearfall playtests both rewrote ([playtest findings](../msc-docs/docs/playtest-findings.md), item 16). |
| `minestom-game` | Everything in `minestom-server`, plus the same minigame base as `paper-game`, set in `GameConfig.game()`: join kit, adventure mode, respawn at the current stage, frozen time and clear weather, an ordered list of stages with spawn points and goal boxes, progress saved to `run/progress.properties`, and a `/game` admin command (`status`, `stage <name or n> [player]`, `next`, `reset`, `kit`) for the console and listed admins. `GameTest` shows the `@EnvTest` pattern for game tests (connect a named player, fire a move, check the stage). No EULA step, so this is the quickest base for a prototype. |
| `fabric-mod` | A Fabric server mod project: Java 25, Gradle KTS with Fabric Loom (server jar only), its own wrapper and version catalog, a `/hello` Brigadier command and a join greeting through Fabric API events, a plain JUnit test and a command test that runs the real command tree inside Fabric Loader, `CLAUDE.md` conventions, and a dev server in `run/` (`./gradlew runServer`) with `paper-server`'s vanilla config. |
| `fabric-game` | Everything in `fabric-mod`, plus the same minigame base, set in `GameConfig.game()`: join kit, adventure mode, keep inventory, respawn at the current stage, no natural mob spawns, frozen time and clear weather, difficulty, stages with goal boxes, progress saved to `run/<projectId>-progress.properties`, and a `/game` admin command (ops, console, RCON) with tab completion. `GameCommandTest` runs the real command tree. |
| `minestom-server` | A Minestom server project: Java 25, Gradle KTS with its own wrapper and version catalog, flat world, `/hello` command, the msc agent built in (`libs/msc-agent-minestom.jar`, idle unless msc-core started the server), a stdin console (`stop`), plain JUnit and Minestom `@EnvTest` tests, `CLAUDE.md` conventions, and a runnable jar (`./gradlew run`, or `java -jar build/libs/<projectId>-<version>.jar`). No EULA step. See [ADR 0010](../msc-docs/docs/adr/0010-minestom-agent.md). |
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
- `Templates.scaffold(id, target, vars)` throws `IllegalArgumentException` for an unknown id, `IllegalStateException` if `target` exists and is not an empty directory (it never overwrites), and `IOException` if writing fails.
- After scaffolding `paper-plugin`: `gradlew build` (or `gradlew.bat build`) in the target builds and tests it; the jar is `build/libs/<projectId>-<version>.jar`.

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

Invalid values throw `IllegalArgumentException` when the variables are built. Pinned versions (`javaVersion`, `mcVersion`, `paperVersion`, `mockbukkitVersion`, `junitVersion`, `assertjVersion`, `runPaperVersion`, `foojayVersion`, `fabricLoaderVersion`, `fabricApiVersion`, `fabricLoomVersion`, `minestomVersion`, `minestomMcVersion`, `slf4jVersion`, `shadowVersion`) come from the repo's version catalog at build time and are also available as placeholders.

## Writing a template

- Sources live in `src/main/templates/<id>/`. Add `template.properties` with `platform` and `description`.
- `{{variable}}` works in file contents and in paths (`src/main/java/{{packagePath}}/{{mainClass}}.java`). An unknown placeholder fails the scaffold, and a test checks no `{{` survives.
- Name a file `*.tmpl` when it would otherwise be picked up by this repo's Gradle or git (`build.gradle.kts.tmpl`, `.gitignore.tmpl`); the suffix is dropped when scaffolding.
- Binary files (`.jar`, `.png`, ...) are copied as is. `gradlew` is made executable.
- The `packTemplates` task copies the sources into the jar under `msc/templates/`, adds the repo's Gradle wrapper to every project template, the Minestom agent jar to `minestom-server` and `minestom-game`, nests `paper-server` under the `run/` of `paper-plugin`, `paper-game`, `fabric-mod` and `fabric-game`, and writes `index.txt`, `<id>/files.txt` and `versions.properties`.

## Tests

- `./gradlew :msc-templates:test`: the API, variables and rendering (runs anywhere).
- `./gradlew :msc-templates:verifyTemplates`: scaffolds every project template (`paper-plugin`, `paper-game`, `fabric-mod`, `fabric-game`, `minestom-server`, `minestom-game`) into `build/verify/` and runs each one's own `gradlew build`, so the templates compile against Paper, Fabric and Minestom and their tests pass. Needs Java 25, repo.papermc.io, maven.fabricmc.net and Maven Central, so it is not part of `check`.

## Decisions

See [ADR 0003](../msc-docs/docs/adr/0003-templates.md); the Fabric template in [ADR 0009](../msc-docs/docs/adr/0009-fabric-agent.md). The Minestom and Fabric game templates set the game up in Java (`GameConfig.game()`) instead of a config file: neither platform has Bukkit's YAML config, a rebuild is part of every dev cycle anyway, and the compiler checks items and coordinates.
