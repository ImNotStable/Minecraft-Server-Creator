# {{name}}

Velocity proxy plugin for Velocity {{velocityVersion}}, Java {{javaVersion}}, Gradle Kotlin DSL. Scaffolded by Minecraft Server Creator.

## Commands

- `./gradlew build`: compile and run all tests. Run it after every change; it must pass before you stop.
- `./gradlew test --tests '*GreeterTest'`: one test class.
- `./gradlew runVelocity`: start a dev proxy in `run/` with this plugin loaded. Stop it with `stop` on the console.
- Plugin jar: `build/libs/{{projectId}}-{{version}}.jar`.

## Layout

- `src/main/java/{{packagePath}}/{{mainClass}}.java`: entry point. Only wiring here (listeners, commands); put logic in its own classes.
- The `@Plugin` annotation on the main class is the plugin's metadata; the build turns it into `velocity-plugin.json`. Its `id` must stay lower case.
- `src/test/java/`: plain unit tests (`GreeterTest`). Velocity has no mock server, so keep logic out of listeners and commands and test it directly.
- `gradle/libs.versions.toml`: every dependency version. Add versions there, never inline in `build.gradle.kts`.

## Velocity conventions

- A proxy has no world: no blocks, entities or inventories. It sees players, their connections and the backend servers they are on (`ProxyServer`, `Player`, `RegisteredServer`).
- Listeners are any class with `@Subscribe` methods, registered with `getEventManager().register(this, listener)`. Players log in with `PostLoginEvent`; `ServerConnectedEvent` fires on every switch of backend.
- Commands implement `SimpleCommand` (or `BrigadierCommand` for arguments) and are registered with `getCommandManager().register(...)`, as in `{{mainClass}}`.
- Text is Adventure `Component`s, written as MiniMessage (`<green>Hi <player>`). Insert player input with `Placeholder.unparsed`.
- Event handlers run on Velocity's thread pools; never block them with I/O or sleeps. Use `getScheduler().buildTask(this, ...)` for later or repeating work.

## Dev proxy

- With Minecraft Server Creator: `server_setup platform=velocity` installs the proxy in `run/` (no EULA step), listening on 127.0.0.1:25577 and sending players to a backend at 127.0.0.1:25565 by modern forwarding. Set up that backend in its own workspace with `server_setup platform=paper proxy=velocity` and start it; then `dev_cycle` here builds, deploys and restarts the proxy.
- World, build and player tools work on the backend's workspace, not here.
