# msc-bench

Benchmark tasks and a runner that measure whether the toolset makes AI server development better on quality, speed and tokens.

**Phase:** MVP  
**Status:** Task set, checks, runner and report done. Next: the baseline run.

## How it works

For each task and each mode (`baseline` = Claude Code alone, `toolset` = Claude Code with msc-core over MCP) the runner:

1. Builds a fresh workspace from the task's fixtures (same for both modes; toolset mode also gets msc-agent-paper in `server/plugins`).
2. For world tasks, starts the Paper server and runs the task's setup commands over RCON.
3. Runs the agent headless (`claude -p ... --output-format json`) in the workspace, with a time limit.
4. Runs the task's checks: files, build commands, PNGs, zips, and the live world and plugins over RCON.
5. Stops the server and records time, turns, tokens and cost (from Claude Code's JSON result) plus tool-call totals, including image tokens from rendered views (from msc-core's tool log, [ADR 0004](../msc-docs/docs/adr/0004-bench.md)).

Attempts are interleaved (task 1 baseline, task 1 toolset, task 2 ...) so drift in model speed hits both modes alike. Results go to `runs/<timestamp>/results.jsonl` and `report.md`; each attempt keeps its workspace, prompt, agent output and server log under `attempts/`.

## Tasks

| Task | Area | Checks |
| --- | --- | --- |
| `code-greet-command` | Plugin code | Builds; `/greet Steve` and `/greet` replies over RCON |
| `code-config-motd` | Plugin code + config | Builds; default reply, `set`, new reply, saved `config.yml` |
| `code-fix-startup` | Debugging | Builds; `/stats` replies `Loaded worlds: 3` (two planted bugs) |
| `build-hut` | Building | 196 block positions: walls, door, windows, roof, empty inside |
| `build-mirror-copy` | Building + seeing | Mirrored copy incl. stair facing; original untouched |
| `world-find-block` | Seeing the world | `answer.txt` has the one gold block among decoys; world unchanged |
| `texture-ruby-item` | Textures | 16x16, at most 8 colours, transparent background, mostly red |
| `texture-resource-pack` | Textures | Zip with `pack.mcmeta` and a ruby `diamond.png` |

A task is a folder in `tasks/` with `task.json` (category, title, fixtures, server, checks, timeout) and `prompt.md`; an optional `fixture/` folder is copied over the shared fixtures in `tasks/_fixtures/`. `tasks/_preamble.md` is put in front of every prompt. Placeholders such as `{workspace}`, `{rconPort}` and `{minecraftVersion}` are filled per run.

Check types: `file` (exists, optional `contains` regex), `command` (exit code, optional `outputMatches`; `{gradlew}` is the workspace wrapper), `png` (`width`, `height`, `maxColors`, `minOpaqueFraction`, `maxOpaqueFraction`, `hue`), `zip` (`entries`, `textEntries`, `pngEntries`), `rcon` (`command`, `expect` regex), `blocks` (`blocks` and `regions`, checked with `execute if block`; partial states such as `minecraft:oak_door[half=lower]` match any other properties).

## Running

```
./gradlew :msc-bench:run --args="list"
./gradlew :msc-bench:run --args="run --modes baseline"                 # the baseline
./gradlew :msc-bench:run --args="run --tasks build-hut --repeat 3"
./gradlew :msc-bench:run --args="check build-hut runs/<ts>/attempts/build-hut/baseline-1/workspace"
./gradlew :msc-bench:run --args="report runs/<ts>"
```

Before the first run:

- Paper: nothing to set. The runner uses the newest `paper-26.3-<build>.jar` in msc-bench's `.cache/` or msc-core's cache (`../.msc/cache`), and downloads the newest stable build into `.cache/` if there is none. `server.paperJar` overrides this.
- Server files: the first server that starts saves Paper's downloaded files to `.cache/paper-server-<version>/`, and later workspaces start from that copy.
- EULA: the runner never accepts the Minecraft EULA for you. Tasks with a server fail with a clear message until `server.acceptEula` is `true` in `bench.json`, which you set only if you accept the [Minecraft EULA](https://aka.ms/MinecraftEULA).
- For `toolset` mode: run `./gradlew :msc-core:installDist :msc-agent-paper:shadowJar` first. Core starts from `paths.coreLib`, and the agent from `paths.agentJar` is put in `server/plugins` as `msc-agent-paper.jar` (the name core uses). Toolset workspaces also get the Paper jar in `.msc/cache`, so core does not download it during the timed run.

The agent command runs Claude Code with `--setting-sources project,local`, so your own plugins, skills and memory stay out of the runs, and with `--dangerously-skip-permissions`, because nobody is there to answer prompts. Each attempt works in its own folder under `runs/`, but the agent can still run any command on the machine, so run the bench on a machine (or VM) where that is acceptable. Ports used: game 25566, RCON 25576, core 25590.

## Depends on

- `msc-core`'s tool log, read at runtime only (no compile dependency).
- Claude Code's `--output-format json` result for tokens, turns and cost.

## Stack

Java 25, Gradle Kotlin DSL, Gson.

## More

See `PLAN.md` for this module's plan, and the project plan in `msc-docs` or [the shared plan doc](https://claude.ai/artifact/DLkdACibUcy9G29V9cjmUe).
