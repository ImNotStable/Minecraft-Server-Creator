# {{name}}

Paper plugin for Minecraft {{mcVersion}}, Java {{javaVersion}}, Gradle Kotlin DSL. Scaffolded by Minecraft Server Creator.

## Commands

- `./gradlew build`: compile; the plugin jar is `build/libs/{{projectId}}-{{version}}.jar`.
- `./gradlew runServer`: dev server in `run/` with this plugin loaded (run-paper).

## Layout

- `src/main/java/{{packagePath}}/{{mainClass}}.java`: entry point.
- `build.gradle.kts`, block `bukkitPluginYaml { }`: plugin metadata; resource-factory writes `plugin.yml` from it at build time.
- paperweight-userdev puts the Paper server, Mojang-named, on the compile classpath.
- `gradle/libs.versions.toml`: dependency and plugin versions.
- `libs/repo/`: msc-stdlib, an optional library already declared as a dependency. Its API is listed in `libs/stdlib/API.md`; call its Stdlib init method with the plugin in onEnable before using it.
- `.msc/`: Minecraft Server Creator's cache. If its tools are available, use them to start the server and read logs.

## Dev server

- `run/eula.txt` must say `eula=true` before the server starts. Accepting the Minecraft EULA (https://aka.ms/MinecraftEULA) is the owner's decision: if it says `false`, ask them, never change it yourself.
- Offline mode, bound to {{serverIp}}:{{serverPort}}; RCON on port {{rconPort}}, password `rcon.password` in `run/server.properties`.

## From scratch

- Write every system, config, texture, model, structure and piece of text in this project yourself, for this server. Minecraft Server Creator and msc-stdlib give primitives (APIs, build steps, checks), never ready-made content: no stock themes, sounds, messages, prefab builds or textures to copy.
