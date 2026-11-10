# 0003: Starter templates

Date: 2026-11-10. Status: accepted.

## Context

`msc-core` scaffolds new server projects for the AI. The scaffolded project is what the AI then works in, so it should build and test on the first try, carry its own conventions, and start a dev server the core can drive.

## Decision

- **msc-templates owns rendering.** Templates are classpath resources; the core calls `Templates.scaffold(id, dir, TemplateVariables)` and never reads the resource layout. Agreed with the msc-core thread.
- **Two Paper templates.** `paper-plugin` is a full plugin project; `paper-server` is only the dev server config, for server directories the core manages itself. `paper-plugin` nests `paper-server` under `run/`, so there is one source for server defaults.
- **`{{variable}}` placeholders** in contents and paths, all derived from one project name. Unknown placeholders and invalid values fail early; scaffolding never overwrites a non-empty directory.
- **Standalone scaffolded projects.** Each gets its own Gradle wrapper (copied from this repo at build time), its own version catalog with versions pinned from ours, the foojay toolchain resolver and run-paper. Java 25 is fixed in the template because Minecraft 26.x needs it.
- **Plugin style.** `plugin.yml` (Bukkit metadata; MockBukkit loads it directly), commands through Paper's Brigadier lifecycle API rather than `plugin.yml`, MiniMessage for text, logic kept out of the main class so it can be unit tested.
- **Tests.** JUnit 6 + AssertJ + MockBukkit (`mockbukkit-v26.3`, 4.120.0). One plain unit test and one MockBukkit test show both patterns.
- **Dev server defaults.** EULA accepted with a comment pointing to it (local dev server only), offline mode, bound to 127.0.0.1, RCON on with a random per-project password, flat creative peaceful world, no nether or structures, view and simulation distance 6, pausing when empty off so tools keep working without players.
- **CLAUDE.md in every scaffolded project** with build and test commands, layout, Paper conventions (Adventure/MiniMessage, Brigadier, main-thread rules) and test patterns, kept short to save tokens.

## Consequences

- Adding a template is a folder under `msc-templates/src/main/templates/`; no core change.
- The template's real build (Paper + MockBukkit) can only be checked where Java 25 and repo.papermc.io are available: `./gradlew :msc-templates:verifyTemplates`.
- Template versions come from `gradle/libs.versions.toml` (including `mockbukkit` and `foojay`), so bumping the catalog bumps new scaffolds.
