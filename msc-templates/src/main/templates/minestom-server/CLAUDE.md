# {{name}}

Minestom server for Minecraft {{minestomMcVersion}}, Java {{javaVersion}}, Gradle Kotlin DSL. Scaffolded by Minecraft Server Creator.

## Commands

- `./gradlew build`: compile and build the runnable server jar `build/libs/{{projectId}}-{{version}}.jar` (Shadow).
- `./gradlew run`: dev server in `run/` on {{serverIp}}:{{serverPort}}.

## Layout

- `src/main/java/{{packagePath}}/{{name}}Server.java`: entry point (`main`).
- `libs/msc-agent-minestom.jar`: Minecraft Server Creator's agent, started in `main`. Keep it; it stays idle unless Minecraft Server Creator started the server.
- The world is generated in memory and not saved: it starts fresh on every restart.
- `gradle/libs.versions.toml`: dependency and plugin versions.
- `libs/repo/`: msc-stdlib, an optional library already declared as a dependency. Its API is listed in `libs/stdlib/API.md`; call its Stdlib init method with the server process in main before using it.
- `.msc/`: Minecraft Server Creator's cache. If its tools are available, use them to start the server and read logs.
