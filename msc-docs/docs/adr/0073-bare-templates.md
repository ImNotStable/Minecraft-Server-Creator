# ADR 0073: Bare project templates

Status: accepted, 2026-11-22. Asked for by Jeremiah; the details below decided by Claude.

## Context

Jeremiah: the templates are too opinionated and dictate how code is written. Models copy the patterns in their
context, so example classes, managers and framework code in a scaffold bias everything generated after it. ADR 0072
showed it: Sky Sprint's agent needed no lookups because the template code already held every Minestom call the task
used.

The templates shipped a `/hello` command, a `Greeter`, join listeners, sample tests, a Minestom `Console`, three game
templates (`paper-game`, `fabric-game`, `minestom-game`: kits, stages, progress, `/game`), and a CLAUDE.md per
template with conventions, recipes and an msc-stdlib tour telling the AI how to structure its code.

## Decision

1. **Game templates are removed**, with their tests (`SharedRulesTablesTest`, `GameProgressTemplatesTest`,
   `TemplateRecipesTest`) and the doc-names fixtures.
2. **Every template is bare scaffolding**, like the IntelliJ Minecraft Development plugin: Gradle build, settings,
   wrapper, `.gitignore`, version catalog, the platform's standard plugins (paperweight-userdev, resource-factory and
   run-paper for Paper; Loom for Fabric; ModDevGradle for NeoForge; ForgeGradle 7 for Forge; Shadow and `application`
   for Minestom; run-velocity for Velocity) and the platform's metadata (`bukkitPluginYaml` via resource-factory,
   `fabric.mod.json`, `neoforge.mods.toml`, `mods.toml` + `pack.mcmeta`, the `@Plugin` annotation).
3. **The only Java is the entry point, empty**: `onEnable`/`onDisable` for Paper, `onInitializeServer` for Fabric, an
   `@Mod` constructor for NeoForge and Forge, `onProxyInitialization` for Velocity. Minestom's `main` is the smallest
   server a player can join: `init`, one flat instance, the spawn listener, the agent, `start`. No tests ship;
   `TemplatesTest.aProjectHasOnlyItsEntryPointAsCode` keeps it that way.
4. **CLAUDE.md states facts only**: the build and run commands, where the entry point and metadata are, the EULA
   rule, the dev server address. No conventions, recipes or architecture advice.
5. **msc-stdlib stays declared, unused.** It is dependency configuration (catalog entry, `libs/repo/`, jar-in-jar
   wiring), which the ask keeps; it lives in `libs/`, not in the project's sources, and nothing calls it. CLAUDE.md
   says in one line that it is optional, where its API index is, and that its init method runs first if used. Test
   dependencies (JUnit, MockBukkit, fabric-loader-junit, Minestom testing) also stay as configuration.
6. **The Minestom console moved into the agent.** msc-core stops a Minestom server and runs `server_command` by
   writing to its standard input, which the template's `Console` read. `MscAgent` now starts that reader itself, only
   when msc-core started the server (like its dev `/tp`), so the project needs no console code.

## Consequences

- A Minestom world is no longer saved between `dev_cycle` restarts; CLAUDE.md says so. The e2e test now checks that
  an edit reaches the restarted server instead of that a build survives.
- Bench task `game-sky-sprint` scaffolds `minestom-server`; its prompt lists every class and signature the hidden
  tests call, and the stdin console it needs. The agent writes the whole game, so its results do not compare with
  runs before this ADR. `HiddenTestsCompileTest` compiles the hidden tests against stubs of that API in
  `msc-bench/src/test/resources/prompt-api/`.
- Agents look up more (`api_search`, `api_class`) where they used to copy template code.
