# {{name}}

Velocity proxy plugin, Java {{javaVersion}}, Gradle Kotlin DSL. Scaffolded by Minecraft Server Creator.

## Commands

- `./gradlew build`: compile; the plugin jar is `build/libs/{{projectId}}-{{version}}.jar`.
- `./gradlew runVelocity`: dev proxy in `run/` with this plugin loaded (run-velocity). Velocity has no EULA step.

## Layout

- `src/main/java/{{packagePath}}/{{mainClass}}.java`: entry point. Its `@Plugin` annotation is the plugin metadata; the annotation processor writes `velocity-plugin.json`. The `id` must stay lower case.
- `gradle/libs.versions.toml`: dependency and plugin versions.
- `libs/repo/`: msc-stdlib, an optional library already declared as a dependency. Its API is listed in `libs/stdlib/API.md`; call its Stdlib init method with the plugin and the proxy on proxy initialization before using it.
- `.msc/`: Minecraft Server Creator's cache. If its tools are available, use them to start the server and read logs.
