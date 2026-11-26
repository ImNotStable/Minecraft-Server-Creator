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

## Token rules (required, ADR 0055, ADR 0116, ADR 0120)

Every byte a tool returns stays in context for the rest of the session. Use the cheapest tool that answers, in this
order. If graphify, Serena or ponytail is missing, say so in your reply instead of silently working without it.

| You need | Use | Not |
|----------|-----|-----|
| Where a feature or concept lives, how parts connect | `graphify query "<question>" --budget 800` | grep for guessed words, reading files to find out |
| What a change to X can break (impact radius) | `graphify affected "X"`; `graphify path "A" "B"` | Serena reference chains |
| One section of a long doc (`plan.md`, `tools.md`, guides) | `graphify section "<heading>"`, or grep then Read `offset`/`limit` | Reading the doc whole; `GRAPH_REPORT.md` ever |
| What is in a Java file | Serena `lsp.get_symbols_overview(path, depth=1)` | Read of the whole file |
| The code of a known class or method | Serena `lsp.find_symbol("Class/method", include_body=True)`, or Read with `offset`/`limit` at a line graphify gave | Read of the whole file |
| Callers of a method with a unique name | `grep -rn "name(" --include=*.java` (Grep, `head_limit`) | Serena (slower, same answer) |
| Callers of a common or overloaded name (`close`, `run`, `register`, `of`) or one overload | Serena `lsp.find_referencing_symbols(..., max_answer_chars=4000)` | grep (floods with unrelated hits) |
| Rename or delete a symbol across modules | Serena `lsp.rename_symbol` / `lsp.safe_delete_symbol` | Hand edits in many files |
| Exact text: strings, config keys, messages, Kotlin DSL, Markdown | Grep with `-l` or `head_limit` | graphify or Serena |
| Small edit | Edit | Serena `replace_symbol_body` (re-sends the whole body) |

- **Serena** costs about 2.5k tokens to start and 1 to 3 minutes for its Java import: use it when a task needs two or
  more symbol lookups in Java, not for a one-off. Never run its onboarding or write Serena memories (project memory
  and this file hold what a session needs). Pass `max_answer_chars` on reference searches.
- **ponytail**: the smallest complete change; `/ponytail-review` before a PR. It never cuts tests, error handling or
  what the task asked for.
- **Plain, short output.** Gradle: a hook adds `-q` (failures and warnings still print); `MSC_GRADLE_VERBOSE=1` only
  when you need task output. Run one module's tests until the end. Everything else: `git log --oneline`,
  `git diff --stat` before a full diff, `curl -sS`, and `| tail -n 40` or a grep on any command whose output can be
  long. Never print a whole log, build report or JSON file; grep it.
- **Subagents** get these rules in their prompt and return a conclusion, not file dumps.
- Where the tools are: PC graph in `D:\Projects\Minecraft Server Creator\graphify-out\` (from a worktree add
  `--graph "D:\Projects\Minecraft Server Creator\graphify-out\graph.json"`; refresh with `graphify update .` from the
  main checkout). Cloud: the environment's setup script runs `.claude/hooks/session-start.sh`; its `agent-tools.sh`
  installs graphify and ponytail, builds the graph and configures Serena. Details and the standing task to keep
  looking: `msc-docs/docs/agent-efficiency.md`.

## Ownership while threads work in parallel

- Shared files: `settings.gradle.kts`, `gradle.properties`, `gradle/`, `build-logic/`, `msc-protocol/`, this file. Only the foundation thread changes them; other threads ask for changes.
- Each module thread stays inside its own `msc-*` folder.
- The core-to-agent contract is `msc-protocol` (`Ops`, `Events`, messages); overview in `msc-docs/docs/protocol.md`.

## Git

- Commit to `main` in small commits with clear messages. No force pushes, no history rewrites.
