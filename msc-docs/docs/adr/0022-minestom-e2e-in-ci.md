# ADR 0022: A real Minestom server end to end in CI

Status: accepted, 2026-11-12. Decided by Claude under Jeremiah's hand-over of build decisions (roadmap round 2, R1).

## Context

Every Fabric and Minestom break in the last round was found by a person play-testing on the PC. CI ran unit tests only (ADR 0012): no step started a server, because Paper and Fabric need the Mojang EULA accepted and CI must never accept it. Minestom is a library, not Mojang's server, and has no EULA, so a real server can run in CI.

## Decisions

1. **One test, the whole loop, through the installed core.** `MinestomEndToEndTest` (msc-core test tree, tag `e2e`) starts `msc-core` from the `installDist` output as a child process and speaks MCP over its stdin and stdout, the way Claude Code does. It calls `project_scaffold` (template `minestom-game`), `server_setup`, `dev_cycle` (the project's own Gradle wrapper builds it, the core deploys and starts it), `build_batch`, `world_slice`, `world_summary`, `world_render`, `player_test` (a real headless client joins, moves and chats), `world_entities` (sees that player), a second `dev_cycle` (restart; the build survives), `server_logs` and `server_stop`, then closes stdin and expects exit code 0. Running from the installed distribution also checks what ships in it: the agent jar inside the template and the previous-version client in `bot/`.
2. **`./gradlew :msc-core:e2eTest`, not part of `build`.** The default `test` task excludes the `e2e` tag, like `template-build`. The task depends on `installDist`, passes its folder to the test, and writes every tool result to `msc-core/build/e2e/transcript.txt` with the core's stderr next to it.
3. **Its own CI job, Linux and Windows.** `ci.yml` gets an `e2e` job (same matrix, `fail-fast: false`, 40-minute timeout) that runs `e2eTest`, then `:msc-templates:verifyTemplates` even if the first failed, and always uploads `msc-core/build/e2e/` and the test reports. It runs next to `build`, not after it, so a PR waits for the slower of the two, not their sum.
4. **Still no EULA in CI.** Only Minestom is started. Paper and Fabric servers stay on the PC, where the owner's own acceptance is recorded (ADR 0020).

## Consequences

- A PR that breaks scaffolding, the Minestom agent, the core's MCP layer, building, world reading, rendering or the test player fails CI on both systems.
- CI costs two more jobs per run; locally the test takes about 40 seconds once Gradle has the dependencies.
- Paper- and Fabric-only code paths (server downloads, `eula.txt`, plugin and mod deploys) are still covered only by unit tests and PC runs.
