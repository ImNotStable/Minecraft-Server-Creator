# msc-bench

Benchmark tasks and a runner that measure whether the toolset makes AI server development better on quality, speed and tokens.

**Phase:** MVP  
**Status:** Task set, checks, runner and report done; Minestom tasks run without the EULA. First full run done and targets set (2026-11-12, ADR 0004).

## How it works

For each task and each mode (`baseline` = Claude Code alone, `toolset` = Claude Code with msc-core over MCP) the runner:

1. Builds a fresh workspace: the task's scaffolded template (if any), then its fixtures (same for both modes; Paper tasks in toolset mode also get msc-agent-paper in `server/plugins`).
2. For world tasks, starts the Paper server and runs the task's setup commands over RCON.
3. Runs the agent headless (`claude -p ... --output-format stream-json --verbose`) in the workspace, with a time limit.
4. Puts back what the task's `resetBeforeChecks` names (see below), then runs the task's checks: files, build commands, PNGs, zips, and the live world and plugins over RCON.
5. Stops the server and records time, turns, tokens and cost (from Claude Code's JSON result) plus tool-call totals, including image tokens from rendered views (from msc-core's tool log, [ADR 0004](../msc-docs/docs/adr/0004-bench.md)).

In toolset mode the runner checks Claude Code's init event: when the `msc` server was not connected at the start (Claude Code `-p` sometimes starts before its MCP servers connect), and no msc tool was called either (the init event can come before the connection), the attempt is retried, up to 3 tries, instead of being scored as a toolset run without tools; the failure names the status the init event gave `msc` (`failed`, `pending`), or says the output named no MCP server at all (an agent command without `stream-json --verbose`, whose `json` output has no init event in some Claude Code versions). Before a toolset attempt the core port must be free: cores of earlier bench attempts holding it are stopped, and any other program holding it (such as the msc plugin of the Claude Code session the bench runs in) fails the attempt at once, naming its msc-core pids. After the attempt the runner stops the msc-core the agent started, found by the `-Dmsc.bench.attempt=<attempt folder>` the MCP config template puts on its command line, since Claude Code does not always stop it (ADR 0044). Attempts are interleaved (task 1 baseline, task 1 toolset, task 2 ...) so drift in model speed hits both modes alike. When an agent run ends on an account usage limit (API error 429), the runner stops there: that attempt is not recorded, and every later one would fail in a second. Results go to `~/.msc/bench-runs/<timestamp>/results.jsonl` (`--out` picks another folder; `run` prints the full path after `Report:`) and `report.md` (pass rate, time, turns and tokens per mode and task, plus a tool cost table: calls, tokens and time per msc-core tool); each attempt keeps its workspace, prompt, agent output and server log under `attempts/`.

## Tasks

| Task | Area | Checks |
| --- | --- | --- |
| `code-greet-command` | Plugin code | Builds; `/greet Steve` and `/greet` replies over RCON |
| `code-config-motd` | Plugin code + config | Builds; default reply (from a fresh server, after the saved `config.yml` is reset), `set`, new reply, saved `config.yml` |
| `code-fix-startup` | Debugging | Builds; `/stats` replies `Loaded worlds: 3` (two planted bugs) |
| `build-hut` | Building | 245 block positions: walls, door, windows, roof, empty inside |
| `build-mirror-copy` | Building + seeing | Mirrored copy incl. stair facing; original untouched |
| `world-find-block` | Seeing the world | `answer.txt` has the one gold block among decoys; world unchanged |
| `texture-ruby-item` | Textures | 16x16, at most 8 colours, transparent background, mostly red |
| `texture-resource-pack` | Textures | Zip with `pack.mcmeta` and a ruby `diamond.png` |
| `game-sky-sprint` | Whole game (Minestom) | Builds with its own tests; hidden `@EnvTest` rules tests; a test player races the course and the console reports the best time |

A task is a folder in `tasks/` with `task.json` (category, title, platform, scaffold, fixtures, server, checks, resetBeforeChecks, timeout) and `prompt.md`; an optional `fixture/` folder is copied over the shared fixtures in `tasks/_fixtures/`. `platform` is `paper` (default) or `minestom`; `scaffold` (`template`, `name`, `package`) writes an msc-templates project into the workspace first, at version `{scaffoldVersion}` (1.0.0) and on the bench's game port. `tasks/_preamble.md` is put in front of Paper prompts, `tasks/_preamble-no-server.md` in front of Paper tasks that need no server (no `server/` folder, such as the texture tasks), and `tasks/_preamble-minestom.md` in front of Minestom ones. Other folders in a task, such as `hidden/`, stay out of the workspace. Placeholders such as `{workspace}`, `{rconPort}`, `{minecraftVersion}` and `{minestomMinecraftVersion}` are filled per run, and `check` fills the same ones from its `bench.json` (the defaults without one), so a check reads them as the run did.

Check types: `file` (the file exists and, with `contains`, the regex matches it; without `contains`, existence alone is the check, as a `png` check without rules passes any readable PNG and a `zip` without entries any readable zip), `command` (exit code, optional `outputMatches`; `{gradlew}` is the workspace wrapper), `png` (`width`, `height`, `maxColors`, `minOpaqueFraction`, `maxOpaqueFraction`, `hue`), `zip` (`entries`, `textEntries`, `pngEntries`), `rcon` (`command`, `expect` regex), `blocks` (`blocks` and `regions`, checked with `execute if block`; partial states such as `minecraft:oak_door[half=lower]` match any other properties). Numbers in `task.json` are read exactly as written and refused with the file and key path (such as `task.json checks[2].timeoutSeconds`) when they are not numbers or out of range: `timeoutSeconds` (of the task and of `command` and `player` checks) is a whole number above 0, `expectExit` a whole number, `width`, `height` and `maxColors` whole numbers above 0, the fractions and `hue.minSaturation` from 0 to 1, `hue.from` and `hue.to` from 0 to 360, and block positions whole numbers inside a Minecraft world (x and z from -30000000 to 29999999, y from -2032 to 2031). Every other key is read by its type as well, a string, `true` or `false`, a list or an object, and a value of another type is refused naming the file, the key and the type it takes: `"server": true` gives `Task t1: task.json server: true is not an object`, and `"startBefore": "yes"` is refused (`is not true or false`) instead of read as false. A `server.setup` without `"startBefore": true` is refused too, since the runner sends the setup commands only to the server it starts before the agent. A string a key cannot use is refused the same way, in one line: a regex key (`contains`, `outputMatches`, `expect`, `textEntries[].contains`, the `player` check's `expect` entries and `ready`) that is no regex gives `Task t1: task.json checks[0].contains: "(abc" is not a regex: Unclosed group at index 4`, and a path that is no path on the system (a NUL character) gives `is not a path`. Every object in `task.json` is closed, at every depth: a key the bench does not know there (a misspelt `resetBeforeChecks`, `"contain"` for `"contains"` in a `file` check, `startbefore` in `server`) is refused instead of left out, in one line naming the file, the path to the key and the keys that object takes (`Task t1: task.json checks[0].contain: unknown key; the keys are [type, name, path, contains]`). Each check type takes its own keys, and a check whose `type` is missing or not a check type is refused naming the types. `bench.json` is read the same way. `results.jsonl` keeps its own rules (below), since it must stay readable across bench versions: a key a record from an older bench lacks takes its empty value. A `command` check with `addFiles` copies that task folder into the workspace first: tests the agent never saw. `player` starts the server `jar` the workspace built in a fresh folder, waits for `ready` (default `Done \(`), lets msc-bot's test player join as `player` and play `script`, sends `console` commands on standard input, stops the server, and passes when every `expect` regex is found in what the player saw plus the console. The player walks on a flat world (ground at y -61), and speaks the server's version when it is the current or previous one, so it works on Minestom, which lags Paper.

A check judges the agent's work, not what the agent's own testing left behind. `resetBeforeChecks` (`{"delete": ["server/plugins/BenchPlugin/config.yml"]}`) lists workspace paths the bench deletes after the agent finishes and before the first check: a file, or a folder with everything in it; a missing one is fine. Each path is relative to the workspace, and one that is blank, absolute (`/x`, `C:/x`) or leads out of it (`../x`, `a/../..`, `.` itself) is refused when the task loads; a path that leads out through a link the agent made is refused before anything is deleted, and a link is deleted, never followed. For a task with a Paper server, the server is stopped first, the bench's own or one the agent left running, since a plugin keeps what the reset deletes in memory; the checks then start the workspace's server, which loads the reset files. `check` does the same on the workspace it is given, so it scores a kept attempt as the run did. It also stops a server answering on the bench's ports for such a task (the other RCON tasks still use and keep it).

Of the shipped tasks only `code-config-motd` needs a reset (#495). Its checks ask for the shipped default `Welcome to MSC`, but Paper's `saveDefaultConfig` never overwrites a `config.yml` that exists, so a correct plugin failed when the agent tried `benchmotd set` while testing and left the value saved; the prompt asks for no restore. Runs from before this change and after it are not strictly comparable for that task: an attempt that failed only `default message` may have been correct work. The other tasks hold no such state: `code-greet-command` and `code-fix-startup` read stateless replies (the three worlds `stats` counts are the ones any start creates); `build-hut`, `build-mirror-copy` and `world-find-block` read blocks the task is about (a block the agent placed while testing is its work, and the prompt says to leave the original or the world as it is) and the forceloads the setup adds, which only an agent removing them would change; the texture tasks read only the files the agent was asked for; and `game-sky-sprint`'s player check runs the server in a fresh folder, so saved progress from the agent's play does not reach it, while the hidden tests build their own.

Text the agent or a child process wrote is read the same way by every check (a `file` check's file, a `zip` text entry, a `command`'s output, a `player` check's console) and by the runner (the agent's output, msc-core's tool log): as UTF-8 where a byte that is not UTF-8 (a Cp1252 `é` from a Windows tool, a character cut off by a kill) becomes U+FFFD instead of failing the check with a read error, and a byte-order mark at the start of the text or of a line (Notepad, Windows PowerShell 5.1's `Set-Content -Encoding utf8`) is dropped, so `13 -57 22` written with one still matches `\A\s*13\s+-57\s+22\s*\z`. Line endings are left as written, so a regex should allow `\r\n` (`\s*` at a line end does). A tool log line that is not a whole call (cut mid-write, `"tool":null`) is left out of the tool totals with one `warning:` line; it never ends the run. The bench's own files (`task.json`, `prompt.md`, the preambles, `bench.json`, a mode's MCP config template, `results.jsonl`) stay strict and are refused when they are not UTF-8, naming the file and the offset of the first byte that is not.

## Running

```
./gradlew :msc-bench:run --args="list"
./gradlew :msc-bench:run --args="run --modes baseline"                 # the baseline
./gradlew :msc-bench:run --args="run --tasks build-hut --repeat 3"
./gradlew :msc-bench:run --args="check build-hut ~/.msc/bench-runs/<ts>/attempts/build-hut/baseline-1/workspace"
./gradlew :msc-bench:run --args="report ~/.msc/bench-runs/<ts>"
./gradlew :msc-bench:run --args="run --tasks game-sky-sprint --repeat 1 --model claude-haiku-4-5 --isolate"   # a smoke run on Linux
```

`--model` replaces the model `agent.command` pins (or adds `--model`), for a cheap smoke run; results from different models do not compare. The smallest useful selection is one task and one repeat: `game-sky-sprint` (Minestom: no EULA, the whole game, a Gradle build) or `texture-ruby-item` (no server and no build).

### On Linux, headless

The bench needs no desktop: a Linux box set up with `scripts/setup-ubuntu.sh` (JDK 25, Claude Code, the repository), or a Claude Code cloud container, runs it as on Windows.

1. Sign Claude Code in once (`claude`, then `/login`); in a cloud container it already is.
2. `./gradlew :msc-core:installDist :msc-agent-paper:shadowJar`
3. `./gradlew :msc-bench:run --args="run --tasks game-sky-sprint --repeat 1 --isolate"`, in `tmux` or with `nohup` on a box you log out of. Paper tasks also need the owner's EULA acceptance on that machine; Minestom tasks never do.

`--isolate` (Linux) starts each agent in its own PID namespace (`unshare --pid --fork --mount-proc --kill-child`, with `--user --map-current-user` when not root), checked once before the run. Use it whenever anything else runs on the machine: an agent cleaning up with `killall -9 java` (a real attempt did) otherwise kills the bench itself, Gradle daemons and other sessions' msc-core. Inside, the agent sees only its own processes, and when it exits the kernel ends what it left, msc-core included. On Ubuntu 24.04, if unprivileged user namespaces are off (`kernel.apparmor_restrict_unprivileged_userns=1`), `run` refuses `--isolate` saying so.

The agent runs as Claude Code started from a plain shell even when the bench itself runs inside a Claude Code session (Remote Control, a cloud session): the runner drops that session's `CLAUDECODE`, `CLAUDE_*` and `CCR_*` variables from the agent's environment, keeping the ones that pick the account or provider (`CLAUDE_CODE_OAUTH_TOKEN`, `CLAUDE_CONFIG_DIR`, `CLAUDE_SESSION_INGRESS_TOKEN_FILE`, `CLAUDE_CODE_USE_*`, `CLAUDE_CODE_SKIP_*`); a mode's `env` can set any back. If that session's msc plugin holds the core port (25590), the toolset attempt says so: set `corePort` in a copy of `bench.json` and pass it with `--config`.

A leading `~` in a path argument (`check`'s workspace, `report`'s run folder, `--out`, `--config`, `--tasks-dir`) is your home folder: the bench expands it itself, because neither Gradle's `--args` nor a quoted shell argument does. The path `run` prints after `Report:` works too.

`--repeat` is a whole number from 1. Before it creates anything, `run` resolves and checks what it will run: a tasks folder with no task in it (an empty folder, or `--tasks-dir` naming another folder such as `msc-bench` itself), a `--tasks` or `--modes` list that names nothing or has an empty name (`--tasks ,`, `--modes a,,b`), a name that is no task or mode, a name listed twice and a `--config` file that does not exist are refused with exit code 2, without a run folder or report. `check` refuses a task list that is not exactly one task, a workspace folder that does not exist and a `--config` file that does not exist (without `--config`, it runs without `bench.json` when there is none, and then its RCON checks fail). When every selected task was skipped (Paper tasks without the EULA), `run` says that nothing ran and writes an empty report.

| Exit code | Meaning |
| --- | --- |
| 0 | `run` or `check`: every attempt or check passed (or every task was skipped); `list` and `report`: done |
| 1 | no command given: the usage is printed |
| 2 | a bad argument or input file, refused with one `msc-bench:` line naming it, before anything is created |
| 3 | `run` or `check`: an attempt or check failed |
| 4 | `run` stopped early on a usage limit |
| 5 | `run` stopped early: an attempt could not be written to `results.jsonl` |
| 70 | an error the bench did not expect: one `msc-bench: internal error` line; please report it |

No exit prints a stack trace.

What an attempt records can never stop a run: check details are cut to a few hundred characters (the end of a command's output, of what a test player saw) on whole characters, so a cut never splits an emoji, and any character UTF-8 cannot hold that is still left in a record (a lone UTF-16 surrogate) is written as U+FFFD. When the file system refuses to write an attempt to `results.jsonl` (a full disk, a file another program holds), the run stops there, since every later attempt would be lost the same way: it says why, writes `report.md` from the attempts the file holds, and exits with code 5. That attempt is not recorded, and none of it stays in the file: a write the disk cut short is cut back, so `results.jsonl` still ends on a whole line (if even that fails, the stop message says so, and `report` leaves the half line out with a warning). Run that attempt and the rest into a new folder and join the files as after a usage-limit stop.

The end of a run cannot lose its exit code. The stop message, if any, comes first; then `report.md` is written whole or not at all, through a temp file moved into place. When the disk refuses the report too, `run` prints one `warning:` line saying that `report <run folder>` writes it once space is freed, leaves no empty or half `report.md`, and keeps its exit code: 5 stays 5, and a run that recorded every attempt keeps 0, 3 or 4, since those speak for the attempts and every one of them is in `results.jsonl`.

`report` reads `results.jsonl` strictly and refuses, with the file, the line number and what is wrong (exit 2), every line it cannot take as a run: text that is not UTF-8, a line that is not one JSON object (`null`, a list, a truncated line), an unknown key, a missing `task`, `category`, `mode`, `repeat` or `passed`, a value of the wrong type, a list entry that is `null`, and a number out of range (counts and tokens are whole numbers from 0; `repeat` from 1). Keys a record from an older bench lacks take their empty value. A file holding no run is refused too. One line is not refused: a half-written last line, as a run killed while writing it leaves (no line break after it, and no complete JSON value or a character cut off at its end). `report` leaves it out, prints one `warning:` line naming the file and line, and reports the runs before it; the same line anywhere else, or as the file's only line, is refused, with one exception: joined with `cat`, such a line runs straight into the next file's first run on one line, and `report` leaves the half out with a `warning:` and reads the run after it. That is no guess: a line that is no JSON can end in a complete JSON object in at most one place, so the split is certain or there is none (two whole runs on one line are still refused). A half line cut inside a character's bytes is still refused when joined (its file is not UTF-8 there); run `report` on that folder alone, which leaves it out, or delete the half line before joining. A byte-order mark is accepted: UTF-8 with one, and UTF-16 with one, which Windows PowerShell 5.1's `>` writes; so is one at the start of each joined file. To put the files of two runs together after a usage-limit stop, use `cat a/results.jsonl b/results.jsonl > results.jsonl`, or in Windows PowerShell `Get-Content -Encoding UTF8 a\results.jsonl, b\results.jsonl | Set-Content -Encoding UTF8 results.jsonl` (without `-Encoding UTF8`, Windows PowerShell 5.1 reads and writes the legacy code page and garbles non-ASCII text).

`check` runs one task's checks on a workspace, after the task's `resetBeforeChecks` as in a run. Its RCON checks use a server already answering on the RCON port and leave it running (a task with a reset stops it first); when none does and the EULA is accepted on the machine, it starts the workspace's own `server/` for the checks and stops it after them.

Before the first run:

- Paper: nothing to set. The runner uses the newest `paper-26.3-<build>.jar` in msc-bench's `.cache/` or msc-core's cache (`../.msc/cache`), and downloads the newest stable build into `.cache/` if there is none. `server.paperJar` overrides this.
- Server files: the first server that starts saves Paper's downloaded files to `.cache/paper-server-<version>/`, and later workspaces start from that copy.
- EULA: the runner never accepts the Minecraft EULA for you, and no config file can. It reads the machine's own acceptance that msc keeps in `~/.msc/eula.json` (written only by its owner: the line in `~/.msc/core/eula-accept.txt` run in a terminal with a typed `yes`, `installMsc -Pmsc.acceptEula=true`, or their yes to a tool). Without it, tasks that run a Paper server are skipped with a message, and the rest run. Minestom tasks never need it, so they run anywhere, CI included. `bench.local.json` is no longer read.
- Minestom tasks: Java 25 and network access for the project's Gradle build. The test player's previous-version protocol jar is staged by `:msc-bench:run` itself.
- For `toolset` mode: run `./gradlew :msc-core:installDist :msc-agent-paper:shadowJar` first. Core starts from `paths.coreLib`, and the agent from `paths.agentJar` is put in `server/plugins` as `msc-agent-paper.jar` (the name core uses). Toolset workspaces also get the Paper jar in `.msc/cache`, so core does not download it during the timed run. `run` refuses a toolset run, before it starts anything, while either is missing.

The agent command runs Claude Code with `--setting-sources project,local`, so your own plugins, skills and memory stay out of the runs, with a pinned `--model` so runs compare like with like, and with `--dangerously-skip-permissions`, because nobody is there to answer prompts. Each attempt works in its own folder under the run folder, `~/.msc/bench-runs` by default. That folder must be outside any project: Claude Code loads `CLAUDE.md`, `CLAUDE.local.md` and `.claude/CLAUDE.md` from the working folder and every folder above it, and takes the enclosing git repository as the project, so attempts inside this repository would give both modes its instructions and the toolset's sources and the baseline would no longer be Claude Code alone. `run` refuses an `--out` folder with such a file or a `.git` in it or above it, after resolving symbolic links and `..` the way the system does, and writes to the folder it checked. When your home folder is itself inside a project (a dotfiles repository at `~`, or a `~/CLAUDE.md`), the default moves to `msc-bench-runs` in the system's temporary folder, and `run` says so: pass `--out` to keep the results somewhere the system does not clear. The agent can still run any command on the machine, though, so run the bench on a machine (or VM) where that is acceptable. Ports used: game 25566, RCON 25576, core 25590. A server already answering on those ports when a server task starts is a leftover from another run: the runner stops it before the setup, and the attempt fails if the ports stay taken. Stopping a run (Ctrl-C) kills the agent with its children (msc-core), the server and any check process, and sends `stop` over RCON to a server the agent started outside its own process tree.

## Config

`bench.json` (`--config` picks another file). Relative paths resolve against the config file's folder, and a missing key gets its default. A value that can never work is refused when the config loads, before any server or agent starts, with each key and the reason, so a bad config fails at once instead of after a server's start timeout. A number key takes a JSON number exactly as written: a decimal, a number past the key's range, a string or `null` is refused and shown as written, never cut or wrapped into another number. Every other key takes its own JSON type too, as `task.json` keys do: a string, `true` or `false`, a list (of strings) or an object; a value of another type, such as `"acceptEula": "yes"` or `"command": "claude"`, is refused naming the key and the type it takes (`server.acceptEula: "yes" is not true or false`), never converted. A key `bench.json` does not know, at any depth, is refused as in `task.json` (`agent.timeoutSecond: unknown key; the keys are [command, timeoutSeconds]`); the names under `modes`, `paths`, `env` and `serverEnv` are your own. A config is refused in one line; one with several problems lists them all on that line, separated by `; `.

| Key | Default | Refused |
| --- | --- | --- |
| `agent.command` | `claude -p {prompt} --output-format stream-json --verbose` | empty list, empty entries, no `{prompt}` here or in each mode's `extraArgs` or `env` |
| `agent.timeoutSeconds` | 1800 | 0 or less, a decimal |
| `modes` | `{"baseline": {}}` | empty, a `null` mode, a name other than letters, digits, `.`, `_`, `-` |
| `modes.<name>.extraArgs` | none | `null` entries |
| `modes.<name>.env` | none | names with `=` or NUL, empty names, `null` values, NUL in values |
| `modes.<name>.mcpConfigTemplate` | none | blank, no such file |
| `modes.<name>.serverEnv` | none | as `env` |
| `modes.<name>.serverPlugins` | none | blank entries, a `{placeholder}` that is neither a `paths` key nor one the runner fills |
| `modes.<name>.paperCacheSeed` | none | blank, absolute, or outside the workspace (`..`) |
| `modes.<name>.requireMcp` | none | blank |
| `server.command` | `java -Xmx2G -jar paper.jar --nogui` | empty list, blank entries |
| `server.paperJar` | newest cached jar, else downloaded | blank, no such file |
| `server.paperCacheDirs` | `.cache`, `../.msc/cache` | empty list, blank entries |
| `server.downloadsUrl` | `https://fill.papermc.io` | not an http or https URL with a host |
| `server.acceptEula` | from `~/.msc/eula.json` | never read from the file |
| `server.serverPort` | 25566 | outside 1 to 65535, the same as `server.rconPort` or `corePort` |
| `server.rconPort` | 25576 | outside 1 to 65535, the same as `server.serverPort` or `corePort` |
| `server.rconPassword` | `msc-bench` | empty or blank (the server turns RCON off), over 1446 bytes in UTF-8 (the server drops the login), a NUL or a lone surrogate (the server would read another password) |
| `server.startTimeoutSeconds` | 180 | 0 or less, a decimal |
| `gradleWrapperFrom` | none | blank, a folder without `gradlew` |
| `paths` | none | blank or `null` paths, names other than letters, digits, `.`, `_`, `-`, names the runner fills itself (`workspace`, `rconPort`, `prompt`...); at `run`, before the run folder, a path that a selected mode's `mcpConfigTemplate` or `serverPlugins` uses and that does not exist (`list`, `check` and `report` take it unbuilt) |
| `corePort` | 25590 | outside 1 to 65535, the same as a server port |
| `minecraftVersion` | 26.3 | blank, characters other than letters, digits, `.`, `_`, `-` |

## Depends on

- `msc-core`'s tool log and `~/.msc/eula.json`, read at runtime only (no compile dependency).
- `msc-templates` to scaffold Minestom tasks, and `msc-bot` for the `player` check.
- Claude Code's `--output-format stream-json --verbose` events (or `json`) for tokens, turns, cost and MCP status.

## Stack

Java 25, Gradle Kotlin DSL, Gson.

## More

See `PLAN.md` for this module's plan, and the project plan in `msc-docs` or [the shared plan doc](https://claude.ai/artifact/DLkdACibUcy9G29V9cjmUe).
