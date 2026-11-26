# Minecraft Server Creator

A toolset that helps an AI develop custom Minecraft servers. One Gradle monorepo; each `msc-*` folder is a module, except `msc-agent-vanilla`, a source folder the Fabric, NeoForge and Forge agents compile in (ADR 0027). The plan is in `msc-docs/docs/plan.md`, decisions in its Decisions table and in `msc-docs/docs/adr/`.

## Build

- Java 25 (Gradle downloads it if missing), Gradle 9.9 wrapper, Kotlin DSL.
- `./gradlew build` builds and tests everything. `./gradlew :msc-protocol:test` for one module.
- Versions live only in `gradle/libs.versions.toml`. Shared build setup lives in `build-logic/` (`msc.java-conventions`, `msc.application-conventions`, `msc.previous-protocol`, `msc.stdlib-conventions` for the standard library shipped in scaffolded projects, ADR 0066).
- A test that reads files outside its own module gets them only through `msc.repo-inputs` (`repoInputs.set(...)` in the build, `RepoFiles` in the test), so they are test inputs too. A set of repository-wide files goes on its own Test task (`repoInputs.on(task).set(...)`, like msc-core's `repoChecks`), so an edit elsewhere doesn't rerun a whole suite.
- `-Xlint:all -Werror` is on: fix warnings, don't suppress them broadly.
- Cloud sessions: `.claude/hooks/session-start.sh` installs JDK 25 at `/opt/jdk25`, adds the Maven Central mirror and warms Gradle (ADR 0038); build with `JAVA_HOME=/opt/jdk25`.

## Code

- Package root: `io.github.imnotstable.msc.<module>`, for example `io.github.imnotstable.msc.core`, `io.github.imnotstable.msc.agent.paper`.
- Every change comes with tests (JUnit 6 + AssertJ). Keep test output short.
- Prefer records and small classes. No new libraries without a catalog entry.

## From scratch (ADR 0106)

- MSC gives the agent general primitives, guidance and quality review checks, never prefabs, presets, one-line
  builders, canned templates or stock assets (builds, interiors, landforms, textures, models, themes, sounds, styled or
  canned text, sample code). The agent creates all of that itself. Remove any you find.
- Want better output? Strengthen a guide (`msc-core/.../guides/`) or a review check, not a generator.
  `FromScratchTest` (msc-core `repoChecks`) fails on new preset steps, options and stdlib constants; an allowlist
  entry needs a reason in an ADR.

## Token-saving tools (required, ADR 0055, ADR 0116)

Use these on every task. If one is missing, say so in your reply instead of silently working without it.

- **graphify** (code graph, local AST, no API key). Ask it before grepping or reading source, and say so in subagent
  prompts that explore code: `graphify query "<question>" --budget 800`, `graphify explain "AgentHub"`,
  `graphify affected "Region"`, `graphify path "A" "B"`. Then read only the lines it names (Read `offset`/`limit`).
  Long docs (`plan.md` 55 KB, `tools.md` 87 KB): read one section, `graphify section "Post-MVP roadmap"` (a heading;
  when it is ambiguous, pass the id it lists), or grep the Decisions table, not the whole file. Never read `GRAPH_REPORT.md` whole (67 KB).
- **ponytail** plugin: smallest complete change. `/ponytail-review` before a PR.
- **Quiet Gradle**: a hook adds `-q` to `./gradlew` runs (failures and warnings still print). Run one module's tests
  until the end; add `MSC_GRADLE_VERBOSE=1` only when you need task output.
- Where they are: PC graph in `D:\Projects\Minecraft Server Creator\graphify-out\` (from a worktree add
  `--graph "D:\Projects\Minecraft Server Creator\graphify-out\graph.json"`; refresh with `graphify update .` from the
  main checkout). Cloud: the environment's setup script runs `.claude/hooks/session-start.sh`, which installs both and
  builds the graph in the checkout. Hooks: `.claude/settings.json`. Details and the standing task to keep looking:
  `msc-docs/docs/agent-efficiency.md`.

## Ownership while threads work in parallel

- Shared files: `settings.gradle.kts`, `gradle.properties`, `gradle/`, `build-logic/`, `msc-protocol/`, this file. Only the foundation thread changes them; other threads ask for changes.
- Each module thread stays inside its own `msc-*` folder.
- The core-to-agent contract is `msc-protocol` (`Ops`, `Events`, messages); overview in `msc-docs/docs/protocol.md`.

## Git

- Commit to `main` in small commits with clear messages. No force pushes, no history rewrites.
