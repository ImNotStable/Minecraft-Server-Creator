# {{name}}

Fabric server mod for Minecraft {{mcVersion}}, Java {{javaVersion}}, Gradle Kotlin DSL. Scaffolded by Minecraft Server Creator.

## Commands

- `./gradlew build`: compile; the mod jar is `build/libs/{{projectId}}-{{version}}.jar`.
- `./gradlew runServer`: dev server in `run/` with this mod and Fabric API loaded (Loom).

## Layout

- `src/main/java/{{packagePath}}/{{mainClass}}.java`: entry point.
- `src/main/resources/fabric.mod.json`: mod metadata; version and Minecraft range are filled in at build time.
- `gradle/libs.versions.toml`: dependency and plugin versions.
- `libs/repo/`: msc-stdlib, an optional library already declared as a dependency. Its API is listed in `libs/stdlib/API.md`; call its Stdlib init method in the initializer before using it.
- `.msc/`: Minecraft Server Creator's cache. If its tools are available, use them to start the server and read logs.

## Dev server

- `run/eula.txt` must say `eula=true` before the server starts. Accepting the Minecraft EULA (https://aka.ms/MinecraftEULA) is the owner's decision: if it says `false`, ask them, never change it yourself.
- Offline mode, bound to {{serverIp}}:{{serverPort}}; RCON on port {{rconPort}}, password `rcon.password` in `run/server.properties`.

## From scratch

- Write every system, config, texture, model, structure and piece of text in this project yourself, for this server. Minecraft Server Creator and msc-stdlib give primitives (APIs, build steps, checks), never ready-made content: no stock themes, sounds, messages, prefab builds or textures to copy.
