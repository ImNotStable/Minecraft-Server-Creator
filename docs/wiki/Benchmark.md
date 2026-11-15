# Benchmark

`msc-bench` measures whether MSC helps. It gives Claude Code the same tasks twice: once alone (`baseline`) and once with MSC (`toolset`). Then it checks the results and compares time, turns, tokens and cost.

**Warning:** the benchmark runs Claude Code with `--dangerously-skip-permissions`, because nobody is there to answer prompts. Run it on a machine or VM where that is fine.

## The tasks

| Task | Tests |
| --- | --- |
| `code-greet-command` | Writing a plugin command |
| `code-config-motd` | Commands plus a config file |
| `code-fix-startup` | Finding two planted bugs |
| `build-hut` | Building a hut block by block |
| `build-mirror-copy` | Mirroring a build |
| `world-find-block` | Finding one gold block among decoys |
| `texture-ruby-item` | Drawing an item texture |
| `texture-resource-pack` | Making a resource pack |
| `game-sky-sprint` | Making a whole minigame on Minestom |

## Run it

1. Build MSC for toolset mode:

   ```
   ./gradlew :msc-core:installDist :msc-agent-paper:shadowJar
   ```

2. Accept the EULA, if you agree. Read the [Minecraft EULA](https://aka.ms/MinecraftEULA). The benchmark never accepts it for you, and no config file can: it reads the acceptance msc keeps in `~/.msc/eula.json`, which only you write: run the line in `~/.msc/core/eula-accept.txt` in a terminal and type `yes`, install with `./gradlew :msc-core:installMsc -Pmsc.acceptEula=true`, or tell Claude yes so it runs `server_setup` or `server_start` with `acceptEula=true`. Without it, tasks that run a Paper server are skipped with a message and the rest run. Minestom tasks never need it.

3. List the tasks:

   ```
   ./gradlew :msc-bench:run --args="list"
   ```

4. Run everything, or just some:

   ```
   ./gradlew :msc-bench:run --args="run"
   ./gradlew :msc-bench:run --args="run --modes baseline"
   ./gradlew :msc-bench:run --args="run --tasks build-hut --repeat 3"
   ```

   For a quick, cheap check that the bench works on a machine, run one task once on a smaller model:

   ```
   ./gradlew :msc-bench:run --args="run --tasks game-sky-sprint --repeat 1 --model claude-haiku-4-5"
   ```

5. Read the report in `~/.msc/bench-runs/<time>/report.md` (the full path is printed after `Report:`). Each attempt keeps its workspace, prompt, output and server log under `attempts/`. Runs must stay outside any repository or folder with a `CLAUDE.md`, so Claude Code does not load this project into the attempts: `--out DIR` picks another such folder, and when your home folder is itself a repository the default moves to `msc-bench-runs` in the system's temporary folder.

## On Linux, without a desktop

The bench runs headless on Linux: on a box set up with `scripts/setup-ubuntu.sh`, or in a Claude Code cloud session.

1. Sign Claude Code in once: run `claude` and use `/login` (a cloud session already is).
2. Build MSC as in step 1 above.
3. Start the run in `tmux` (or with `nohup`) so it keeps going after you log out, and add `--isolate`: each agent then runs in its own process namespace, so an agent that cleans up with `killall java` cannot kill the bench or anything else on the machine. Minestom tasks need nothing else; Paper tasks need your EULA acceptance on that machine.

   ```
   ./gradlew :msc-bench:run --args="run --tasks game-sky-sprint --repeat 1 --isolate"
   ```

Even when the bench runs inside a Claude Code session, each attempt's agent runs like Claude Code started from a plain shell. If that session's own MSC holds port 25590, toolset attempts stop and say so: set `corePort` to a free port in a copy of `bench.json` and pass `--config`.

## Good to know

- Paper is downloaded once and cached in `msc-bench/.cache/`.
- Ports used: game 25566, RCON 25576, MSC 25590.
- Your own Claude Code plugins, skills and memory are kept out of runs.
