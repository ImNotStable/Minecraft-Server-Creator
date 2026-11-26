# ADR 0120: Tool-choice rules, Serena for Java, quiet CI

Status: accepted, 2026-11-26, decided by Claude at Jeremiah's request.

## Context

Jeremiah asked to cut token use greatly: a solid set of CLAUDE.md rules for Serena, graphify and ponytail, with
specific rules on which tool to use when, and plain, minimal logs. ADR 0116 brought graphify and ponytail to cloud
threads and quieted Gradle there.

Serena (semantic code tools over a language server) is Jeremiah's claude.ai account plugin, so it already starts in
every cloud thread. Out of the box it could not read this repository: JDTLS imports Gradle projects with its own
Gradle 8.14 on Java 21, and this build needs the 9.9 wrapper on Java 25 (Fabric Loom), and JDTLS's annotation
processing init script fails ("resolution without an exclusive lock") under `org.gradle.parallel` and the
configuration cache. Without the import it finds symbols in a file but no references across files. Its config also
defaults to 150,000-character answers, the "interactive" mode (ask the user at every doubt) and onboarding memories.

Measured on this repository (characters returned):

| Question | grep / Read | graphify | Serena |
|----------|-------------|----------|--------|
| Callers of `agentLeft` (unique name) | 246 | - | about 400 |
| Callers of `AgentHub.close` (common name) | thousands of `.close()` hits | - | about 1,500, exact |
| References to class `AgentHub` | 13,384 | 338 (`explain`) | 31,184 |
| What is in `AgentHub.java` | 19,212 (whole file) | - | about 900 (overview) |
| Impact of `awaitReady` | - | 2,437 (`affected`) | none found |
| "How does the server wait for the agent" | many files | 2,855 (`query --budget 800`) | - |

Serena's start costs about 2.5k tokens of instructions and 1 to 3 minutes of Java import.

## Decision

1. **CLAUDE.md has a "Token rules" table** that names the tool for each kind of question: graphify for concepts,
   impact and doc sections; Serena for a file's outline, one symbol's body, references of common or overloaded
   names, and renames; grep for unique names and exact text; Edit for small edits. Plus rules for plain output
   and for subagents.
2. **Serena is configured by `agent-tools.sh`** (cloud; the same settings by hand on the PC and omen): the project's
   wrapper, `gradle_java_home` on JDK 25, and a Gradle home of its own (`~/.gradle-serena`, sharing caches and the
   wrapper with `~/.gradle`) without parallel and configuration cache; answers capped at 20,000 characters; no web
   dashboard; base mode `editing` only. `.serena/project.yml` is committed with `no-onboarding` and `no-memories`.
   Project-level `ls_specific_settings` are not honoured by Serena today, so the Java settings live in the global
   config.
3. **CI runs Gradle with `-q`** instead of `--console=plain`. Failed tests still print in full at quiet level
   (ADR 0116), so a session reading a CI log gets the failures, not hundreds of task lines.
4. **`gradlew-filter.sh` also drops NeoForge's NFRT progress lines** from stdout: about 5 KB of colour-coded
   "Started working on / Used cache of" lines each time the configuration cache misses, which `-q` does not hide.
   A full cloud build after a clean went from 7.1 KB to 1 KB (the rest was a real test failure).
5. MSC's own build output already goes through `LogCondenser` and its tools through `Budget`; unchanged.

## Consequences

- A thread asks the right tool first and reads less. The table is the rule; a session that skips it should say why.
- Serena's first answer in a thread waits for its Java import.
- A human reading CI sees failures and warnings, not the task list; rerun with `--console=plain` to see it.
