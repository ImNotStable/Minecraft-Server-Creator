# {{name}}

Waterfall (BungeeCord) proxy plugin, Java {{javaVersion}}, Gradle Kotlin DSL. Scaffolded by Minecraft Server Creator.

## Commands

- `./gradlew build`: compile; the plugin jar is `build/libs/{{projectId}}-{{version}}.jar`.
- `./gradlew runWaterfall`: dev proxy in `run/` with this plugin loaded (run-waterfall). Waterfall has no EULA step.

## Layout

- `src/main/java/{{packagePath}}/{{mainClass}}.java`: entry point, a `Plugin`.
- `src/main/resources/bungee.yml`: the plugin metadata; `main` names the entry point.
- `gradle/libs.versions.toml`: dependency and plugin versions.
- `.msc/`: Minecraft Server Creator's cache. If its tools are available, use them to start the server and read logs.
