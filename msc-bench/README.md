# msc-bench

Benchmark tasks and a runner that measure whether the toolset makes AI server development better on quality, speed and tokens.

**Phase:** MVP  
**Status:** Task set, checks, runner and report done; Minestom tasks run without the EULA. First full run done and targets set (2026-11-12, ADR 0004).

## How it works

For each task and each mode (`baseline` = Claude Code alone, `toolset` = Claude Code with msc-core over MCP) the runner:

1. Builds a fresh workspace: the task's scaffolded template (if any), then its fixtures (same for both modes; Paper tasks in toolset mode also get msc-agent-paper in `server/plugins`).
2. For world tasks, starts the Paper server and runs the task's setup commands over RCON.
3. Runs the agent headless (`claude -p ... --output-format json`) in the workspace, with a time limit.
4. Runs the task's checks: files, build commands, PNGs, zips, and the live world and plugins over RCON.
5. Stops the server and records time, turns, tokens and cost (from Claude Code's JSON result) plus tool-call totals, including image tokens from rendered views (from msc-core's tool log, [ADR 0004](../msc-docs/docs/adr/0004-bench.md)).

In toolset mode the runner checks Claude Code's init event: when the `msc` server was not connected at the start (Claude Code `-p` sometimes starts before its MCP servers connect), and no msc tool was called either (the init event can come before the connection), the attempt is retried, up to 3 tries, instead of being scored as a toolset run without tools. Attempts are interleaved (task 1 baseline, task 1 toolset, task 2 ...) so drift in model speed hits both modes alike. When an agent run ends on an account usage limit (API error 429), the runner stops there: that attempt is not recorded, and every later one would fail in a second. Results go to `~/.msc/bench-runs/<timestamp>/results.jsonl` (`--out` picks another folder; `run` prints the full path after `Report:`) and `report.md` (pass rate, time, turns and tokens per mode and task, plus a tool cost table: calls, tokens and time per msc-core tool); each attempt keeps its workspace, prompt, agent output and server log under `attempts/`.

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
| `game-sky-sprint` | Whole game (Minestom) | Builds with its own tests; hidden `@EnvTest` rules tests; a test player races the course and the console reports the best time |

A task is a folder in `tasks/` with `task.json` (category, title, platform, scaffold, fixtures, server, checks, timeout) and `prompt.md`; an optional `fixture/` folder is copied over the shared fixtures in `tasks/_fixtures/`. `platform` is `paper` (default) or `minestom`; `scaffold` (`template`, `name`, `package`) writes an msc-templates project into the workspace first, at version `{scaffoldVersion}` (1.0.0) and on the bench's game port. `tasks/_preamble.md` is put in front of Paper prompts, `tasks/_preamble-no-server.md` in front of Paper tasks that need no server (no `server/` folder, such as the texture tasks), and `tasks/_preamble-minestom.md` in front of Minestom ones. Other folders in a task, such as `hidden/`, stay out of the workspace. Placeholders such as `{workspace}`, `{rconPort}`, `{minecraftVersion}` and `{minestomMinecraftVersion}` are filled per run.

Check types: `file` (exists, optional `contains` regex), `command` (exit code, optional `outputMatches`; `{gradlew}` is the workspace wrapper), `png` (`width`, `height`, `maxColors`, `minOpaqueFraction`, `maxOpaqueFraction`, `hue`), `zip` (`entries`, `textEntries`, `pngEntries`), `rcon` (`command`, `expect` regex), `blocks` (`blocks` and `regions`, checked with `execute if block`; partial states such as `minecraft:oak_door[half=lower]` match any other properties). A `command` check with `addFiles` copies that task folder into the workspace first: tests the agent never saw. `player` starts the server `jar` the workspace built in a fresh folder, waits for `ready` (default `Done \(`), lets msc-bot's test player join as `player` and play `script`, sends `console` commands on standard input, stops the server, and passes when every `expect` regex is found in what the player saw plus the console. The player walks on a flat world (ground at y -61), and speaks the server's version when it is the current or previous one, so it works on Minestom, which lags Paper.

## Running

```
./gradlew :msc-bench:run --args="list"
./gradlew :msc-bench:run --args="run --modes baseline"                 # the baseline
./gradlew :msc-bench:run --args="run --tasks build-hut --repeat 3"
./gradlew :msc-bench:run --args="check build-hut ~/.msc/bench-runs/<ts>/attempts/build-hut/baseline-1/workspace"
./gradlew :msc-bench:run --args="report ~/.msc/bench-runs/<ts>"
```

A leading `~` in a path argument (`check`'s workspace, `report`'s run folder, `--out`, `--config`, `--tasks-dir`) is your home folder: the bench expands it itself, because neither Gradle's `--args` nor a quoted shell argument does. The path `run` prints after `Report:` works too.

`check` runs one task's checks on a workspace. Its RCON checks use a server already answering on the RCON port and leave it running; when none does and the EULA is accepted on the machine, it starts the workspace's own `server/` for the checks and stops it after them.

Before the first run:

- Paper: nothing to set. The runner uses the newest `paper-26.3-<build>.jar` in msc-bench's `.cache/` or msc-core's cache (`../.msc/cache`), and downloads the newest stable build into `.cache/` if there is none. `server.paperJar` overrides this.
- Server files: the first server that starts saves Paper's downloaded files to `.cache/paper-server-<version>/`, and later workspaces start from that copy.
- EULA: the runner never accepts the Minecraft EULA for you, and no config file can. It reads the machine's own acceptance that msc keeps in `~/.msc/eula.json` (written only by its owner: `msc eula accept`, `installMsc -Pmsc.acceptEula=true`, or their yes to a tool). Without it, tasks that run a Paper server are skipped with a message, and the rest run. Minestom tasks never need it, so they run anywhere, CI included. `bench.local.json` is no longer read.
- Minestom tasks: Java 25 and network access for the project's Gradle build. The test player's previous-version protocol jar is staged by `:msc-bench:run` itself.
- For `toolset` mode: run `./gradlew :msc-core:installDist :msc-agent-paper:shadowJar` first. Core starts from `paths.coreLib`, and the agent from `paths.agentJar` is put in `server/plugins` as `msc-agent-paper.jar` (the name core uses). Toolset workspaces also get the Paper jar in `.msc/cache`, so core does not download it during the timed run.

The agent command runs Claude Code with `--setting-sources project,local`, so your own plugins, skills and memory stay out of the runs, with a pinned `--model` so runs compare like with like, and with `--dangerously-skip-permissions`, because nobody is there to answer prompts. Each attempt works in its own folder under the run folder, `~/.msc/bench-runs` by default. That folder must be outside any project: Claude Code loads `CLAUDE.md`, `CLAUDE.local.md` and `.claude/CLAUDE.md` from the working folder and every folder above it, and takes the enclosing git repository as the project, so attempts inside this repository would give both modes its instructions and the toolset's sources and the baseline would no longer be Claude Code alone. `run` refuses an `--out` folder with such a file or a `.git` in it or above it, after resolving symbolic links and `..` the way the system does, and writes to the folder it checked. When your home folder is itself inside a project (a dotfiles repository at `~`, or a `~/CLAUDE.md`), the default moves to `msc-bench-runs` in the system's temporary folder, and `run` says so: pass `--out` to keep the results somewhere the system does not clear. The agent can still run any command on the machine, though, so run the bench on a machine (or VM) where that is acceptable. Ports used: game 25566, RCON 25576, core 25590. A server already answering on those ports when a server task starts is a leftover from another run: the runner stops it before the setup, and the attempt fails if the ports stay taken. Stopping a run (Ctrl-C) kills the agent with its children (msc-core), the server and any check process, and sends `stop` over RCON to a server the agent started outside its own process tree.

## Depends on

- `msc-core`'s tool log and `~/.msc/eula.json`, read at runtime only (no compile dependency).
- `msc-templates` to scaffold Minestom tasks, and `msc-bot` for the `player` check.
- Claude Code's `--output-format json` result for tokens, turns and cost.

## Stack

Java 25, Gradle Kotlin DSL, Gson.

## More

See `PLAN.md` for this module's plan, and the project plan in `msc-docs` or [the shared plan doc](https://claude.ai/artifact/DLkdACibUcy9G29V9cjmUe).
