# ADR 0066: A standard library for the plugins and mods MSC makes

Status: accepted, 2026-11-22. Decided by Claude. Jeremiah asked for a standard library that every plugin or mod
the AI creates uses, listed in the templates' `gradle/libs.versions.toml`, covering databases that are flexible
and configurable, particles, GUIs and the other tools servers commonly need, and asked Claude to make the design
calls.

## Context

Every generated plugin rewrote the same things: an inventory menu and its click listener, an item builder,
a cooldown map, a JSON or YAML save file, particle rings. Each copy had its own bugs (items taken out of menus,
saves on the main thread, MySQL-only SQL), and each cost tokens. Scaffolded projects are separate Gradle builds
with no access to this repository, and msc-stdlib is not on Maven Central.

## Decision

1. **Two layers.** `msc-stdlib` is platform-neutral Java: `db` (`Database` over HikariCP for SQLite, MySQL,
   MariaDB and PostgreSQL; `Table` schemas and `upsert` SQL per backend; `Migration`s recorded per namespace in
   `msc_migrations`, so plugins can share one database; `Store<V>` key-value records through a `Codec`, JSON by
   default; `Store.memory()` for tests), `time` (`Cooldowns`, `Durations`), `geom` (`Vec3`, `Shapes`: line, circle,
   arc, disc, sphere, helix, cuboid edges, star, random points), `sched.Scheduler` (later, repeat, countdown, async
   and back) and `util.WeightedRandom`. Platform adapters build on it; `msc-stdlib-paper` is the first: `Stdlib.init`,
   `Gui` (chest and paged menus, editable slots), `ItemBuilder`, `Text` (MiniMessage with safe placeholders),
   `ParticleEffect` (spawn, draw a shape, animate), `Locations`, `Sidebar`, `PlayerData` (load at login, save on quit)
   and `PaperDatabase` (backend from `config.yml`).
2. **Shipped inside the project as a Maven repository.** Each stdlib module's `stdlibRepo` task lays out its jar,
   sources jar and pom (`build/repo/io/github/imnotstable/msc/<module>/<version>/`); `packTemplates` copies them into
   the Paper templates as `libs/repo/`. The template's catalog names `msc-stdlib-paper` like any other library, and
   its build adds `maven(uri("libs/repo"))` for that group only. The project builds offline from MSC and keeps
   working after MSC upgrades; a new MSC writes the new version only into new projects. The pom comes from
   `maven-publish`'s pom task, but the files are copied by a `Sync`: publish tasks never count as up to date, and the
   Gradle metadata file carries a timestamp, so either would rebuild the templates jar on every build.
3. **Bundled into the plugin jar.** The Paper templates' `jar` task unpacks every `implementation` dependency into the
   plugin (msc-stdlib and HikariCP, ~400 KB). Paper loads each plugin in its own class loader, so two plugins with
   different stdlib versions do not clash. Paper already ships Gson, slf4j and the SQLite and MySQL drivers, so the
   stdlib compiles against Gson only and the template excludes slf4j; MariaDB and PostgreSQL drivers are the
   server owner's to add, and `Database.open` names the missing driver class. Not shaded or relocated
   (shortcut: relocation matters only if a server loads stdlib classes from another plugin first; add Shadow
   relocation then).
4. **Own version.** `msc-stdlib` in the repo catalog (1.0.0), independent of MSC's version; raise it on any API change.
5. **Used by default.** Both Paper templates call `Stdlib.init(this)` in `onEnable`, and their `CLAUDE.md` lists the
   library before the recipes. paper-plugin's recipes build a shop on it (database, migration, store, player data,
   menu, cooldown, particles) with a MockBukkit test, so `verifyTemplates` compiles and runs it against the pinned
   versions. A `doc-names` file per Paper template uses every stdlib name the guides list.

## Phases

- Phase 1 (this ADR): msc-stdlib, msc-stdlib-paper, paper-plugin and paper-game.
- Phase 2a (1.1.0): `Text` moves to msc-stdlib (`text.Text`, Adventure compile-only: Paper and Velocity ship it, the
  Minestom adapter brings MiniMessage, which Minestom lacks). `msc-stdlib-minestom`: `Stdlib.init(process)`, `Gui`
  (an `Inventory` subclass, so one listener on the library's own event node finds the menu from the player's open
  inventory), `ItemBuilder`, `ParticleEffect` (packets to an instance or players), `Locations`, `PlayerData` (loads in
  `AsyncPlayerConfigurationEvent`), `MinestomScheduler`. Sidebars stay Minestom's own `Sidebar`. minestom-server and
  minestom-game ship it with the SQLite driver as a runtime dependency (no Minestom server ships one), packed into the
  fat jar by Shadow. `packTemplates` gives each template only the core and its own adapter.
- Phase 2b: Fabric (`include` jar-in-jar, a JSON config helper), Minestom (in its fat jar), then NeoForge, Forge and
  Velocity, each an adapter module implementing `Scheduler` and its own menus and particles. `api_search` and
  `api_class` index the stdlib jars from the project's `libs/repo/` (today they see only the platform's jars; the
  guide points at the sources jar instead).

## Left out

- MongoDB: a document store needs its own driver (several MB) and API; `Store` is an interface, so a Mongo backend
  can be added without changing callers. H2: Paper does not ship it and SQLite covers the file case.
- Commands and config wrappers on Paper: Paper's Brigadier API and `getConfig()` already do the job, and the guide
  already teaches them.
- Holograms, boss bars, NPCs: a line each with the Paper API (`TextDisplay`, `BossBar`); add when generated code
  keeps repeating them.
- Folia: `PaperScheduler` uses the Bukkit scheduler (shortcut comment in the code).
- MockBukkit 4.123 does not implement scoreboard number formats or score display names, so `Sidebar` tests skip
  under it; the guide says to test what fills the lines instead.
- MySQL, MariaDB and PostgreSQL are not run in CI (no database services on the runners); the SQL they get is
  asserted as text, SQLite runs for real.
