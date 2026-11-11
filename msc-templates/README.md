# msc-templates

Starter server projects per platform, ready for the AI to build on, with tests and conventions wired in. `msc-core` scaffolds them through a small Java API; nothing else needs to know how they are stored.

**Phase:** MVP  
**Status:** Paper plugin and Paper dev server templates done and verified (Java 25, Paper 26.3.build.248, MockBukkit 4.120.0). Fabric and Minestom later.

## Templates

| Id | What you get |
| --- | --- |
| `paper-plugin` | A Paper plugin project: Java 25, Gradle KTS with its own wrapper and version catalog, a `/hello` command (Brigadier) and a join listener, a plain JUnit test and a MockBukkit test, `CLAUDE.md` conventions for the AI, and a dev server in `run/` (`./gradlew runServer`, via run-paper). |
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
| `description` | `SpawnLobby, a Paper plugin made with Minecraft Server Creator.` | `withDescription` |
| `author` | empty | `withAuthor` |
| `serverIp` | `127.0.0.1` | `withServerIp` |
| `serverPort` | `25565` | `withServerPort` |
| `rconPort` | `25575` | `withRconPort` |
| `rconPassword` | 24 random hex characters | `withRconPassword` |
| `levelType` | `minecraft:flat` (also sets `generator-settings` layers) | `withLevelType` |
| `acceptEula` | `false`: writes `eula=false`. Set it only when the user has accepted the [Minecraft EULA](https://aka.ms/MinecraftEULA) | `withAcceptEula(boolean)` |

Invalid values throw `IllegalArgumentException` when the variables are built. Pinned versions (`javaVersion`, `mcVersion`, `paperVersion`, `mockbukkitVersion`, `junitVersion`, `assertjVersion`, `runPaperVersion`, `foojayVersion`) come from the repo's version catalog at build time and are also available as placeholders.

## Writing a template

- Sources live in `src/main/templates/<id>/`. Add `template.properties` with `platform` and `description`.
- `{{variable}}` works in file contents and in paths (`src/main/java/{{packagePath}}/{{mainClass}}.java`). An unknown placeholder fails the scaffold, and a test checks no `{{` survives.
- Name a file `*.tmpl` when it would otherwise be picked up by this repo's Gradle or git (`build.gradle.kts.tmpl`, `.gitignore.tmpl`); the suffix is dropped when scaffolding.
- Binary files (`.jar`, `.png`, ...) are copied as is. `gradlew` is made executable.
- The `packTemplates` task copies the sources into the jar under `msc/templates/`, adds the repo's Gradle wrapper to `paper-plugin`, nests `paper-server` under its `run/`, and writes `index.txt`, `<id>/files.txt` and `versions.properties`.

## Tests

- `./gradlew :msc-templates:test`: the API, variables and rendering (runs anywhere).
- `./gradlew :msc-templates:verifyTemplates`: scaffolds `paper-plugin` into `build/verify/` and runs its own `gradlew build`, so the template compiles against Paper and its MockBukkit tests pass. Needs Java 25 and repo.papermc.io, so it is not part of `check`.

## Decisions

See [ADR 0003](../msc-docs/docs/adr/0003-templates.md).
