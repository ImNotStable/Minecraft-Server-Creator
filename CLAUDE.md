# Minecraft Server Creator

A toolset that helps an AI develop custom Minecraft servers. One Gradle monorepo; each `msc-*` folder is a module, except `msc-agent-vanilla`, a source folder the Fabric, NeoForge and Forge agents compile in (ADR 0027). The plan is in `msc-docs/docs/plan.md`, decisions in its Decisions table and in `msc-docs/docs/adr/`.

## Build

- Java 25 (Gradle downloads it if missing), Gradle 9.9 wrapper, Kotlin DSL.
- `./gradlew build` builds and tests everything. `./gradlew :msc-protocol:test` for one module.
- Versions live only in `gradle/libs.versions.toml`. Shared build setup lives in `build-logic/` (`msc.java-conventions`, `msc.application-conventions`, `msc.previous-protocol`).
- A test that reads files outside its own module gets them only through `msc.repo-inputs` (`repoInputs.set(...)` in the build, `RepoFiles` in the test), so they are test inputs too. A set of repository-wide files goes on its own Test task (`repoInputs.on(task).set(...)`, like msc-core's `repoChecks`), so an edit elsewhere doesn't rerun a whole suite.
- `-Xlint:all -Werror` is on: fix warnings, don't suppress them broadly.
- Cloud sessions: `.claude/hooks/session-start.sh` installs JDK 25 at `/opt/jdk25`, adds the Maven Central mirror and warms Gradle (ADR 0038); build with `JAVA_HOME=/opt/jdk25`.

## Code

- Package root: `io.github.imnotstable.msc.<module>`, for example `io.github.imnotstable.msc.core`, `io.github.imnotstable.msc.agent.paper`.
- Every change comes with tests (JUnit 6 + AssertJ). Keep test output short.
- Prefer records and small classes. No new libraries without a catalog entry.

## Code graph (graphify)

- A code graph of the repo lives in `D:\Projects\Minecraft Server Creator\graphify-out\` (main checkout only, git-excluded). Read `GRAPH_REPORT.md` there before broad searches.
- Ask it: `graphify query "<question>"`, `graphify explain "AgentHub"`, `graphify affected "Region"`, `graphify path "A" "B"`. From a worktree add `--graph "D:\Projects\Minecraft Server Creator\graphify-out\graph.json"`.
- Refresh after changes, from the main checkout: `graphify update .` (local AST, no API key).

## Ownership while threads work in parallel

- Shared files: `settings.gradle.kts`, `gradle.properties`, `gradle/`, `build-logic/`, `msc-protocol/`, this file. Only the foundation thread changes them; other threads ask for changes.
- Each module thread stays inside its own `msc-*` folder.
- The core-to-agent contract is `msc-protocol` (`Ops`, `Events`, messages); overview in `msc-docs/docs/protocol.md`.

## Git

- Commit to `main` in small commits with clear messages. No force pushes, no history rewrites.
