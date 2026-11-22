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

## Standard library (msc-stdlib)

The project depends on MSC's standard library (`libs.msc.stdlib.velocity`, in `libs/repo/`, bundled into the plugin jar); `Stdlib.init(this, proxy)` runs first in `onProxyInitialization`. Use it before writing your own.

Find what you need without reading sources: `grep -n "Repository" libs/stdlib/API.md` (one line per public method), `graphify query "send a player to another server" --graph libs/stdlib/graphify-out/graph.json`, then read only the lines you need in `libs/stdlib/src/`.

- Proxy: `Servers` (`send`, `current`, `players`, `broadcast`), `PlayerData` (network-wide records loaded at login, saved on disconnect), `Lang` (each player's language), `Stdlib.scheduler()`. Velocity cannot hide signed chat, so there is no chat prompt here.
- Players see (Adventure, from msc-stdlib's `ui` package): `Feedback` (`success`, `fail`, `info`, `actionBar`, `title`), `Sounds` presets, `Bar` boss bars (`Bar.timer(Stdlib.scheduler(), ...)`).
- Data and messaging: `Repository` records with no SQL, `Database` (bundle a driver first; see `build.gradle.kts`), `Store` (`jsonFiles`, `cached`, `writeBehind`), `Cache`, `Economy`, `Config`, `Messenger` and `Redis` (cross-server messages, locks, counters; add the Jedis driver to `libs.versions.toml`), `Mongo`.
- Utilities: `Format`, `Durations`, `Cooldowns`, `RateLimiter`, `Leaderboard`, `EventBus`, `Sequence`, `Randoms`, `Text.mm`.

## Dev proxy

- With Minecraft Server Creator: `server_setup platform=velocity` installs the proxy in `run/` (no EULA step), listening on 127.0.0.1:25577 and sending players to a backend at 127.0.0.1:25565 by modern forwarding. Set up that backend in its own workspace with `server_setup platform=paper proxy=velocity` and start it; then `dev_cycle` here builds, deploys and restarts the proxy.
- World, build and player tools work on the backend's workspace, not here.
