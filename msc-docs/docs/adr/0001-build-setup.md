# 0001: Build setup

Date: 2026-11-10. Status: accepted.

## Context

The project is one monorepo with ten modules that several threads will build in parallel. The MVP targets Paper on the latest stable Minecraft release, which is 26.3 (Paper 26.3 has been on the stable channel since 2026-10-11). Minecraft 26.1 and later need Java 25.

## Decision

- **Java 25**, the current LTS, for every module, set once in `build-logic`. The foojay resolver plugin lets Gradle download a Java 25 toolchain when the machine has none. `-Pmsc.javaVersion=NN` exists only for experiments.
- **Gradle 9.9.0** through the wrapper, Kotlin DSL everywhere.
- **Convention plugins** in the included build `build-logic`:
  - `msc.java-conventions`: toolchain, UTF-8, `-parameters`, `-Xlint:all -Werror`, JUnit 6 + AssertJ, short test logs.
  - `msc.application-conventions`: the above plus `application`, for `msc-core` and `msc-bench`.
- **One version catalog**, `gradle/libs.versions.toml`. Module builds never hard-code versions.
- **Paper API `26.3.build.+`**. Since 26.1, Paper versions look like `26.3.build.231-stable`; Paper recommends `<mc>.build.+` to follow the latest build of one Minecraft version.
- **Group `io.github.imnotstable.msc`**, package root `io.github.imnotstable.msc.<module>` (for example `...msc.core`, `...msc.agent.paper`).
- **Ownership.** `settings.gradle.kts`, `gradle.properties`, `gradle/`, `build-logic/` and `msc-protocol/` belong to the foundation thread. Module threads stay inside their own folder and ask for catalog or protocol changes instead of making them.

## Consequences

- A module needs only `plugins { id("msc.java-conventions") }` plus its dependencies.
- `msc-agent-paper` resolves Paper from `repo.papermc.io`; a build environment that cannot reach that host can still build every other module.
- Moving to the next Minecraft version is a one-line catalog change plus whatever API changes Paper makes.
