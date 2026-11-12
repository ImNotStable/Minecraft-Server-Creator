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

## Run it

1. Build MSC for toolset mode:

   ```
   ./gradlew :msc-core:installDist :msc-agent-paper:shadowJar
   ```

2. Accept the EULA, if you agree. Read the [Minecraft EULA](https://aka.ms/MinecraftEULA). Server tasks fail until you set `"acceptEula": true` under `server` in `msc-bench/bench.json`. The benchmark never does this for you.

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

5. Read the report in `msc-bench/runs/<time>/report.md`. Each attempt keeps its workspace, prompt, output and server log under `attempts/`.

## Good to know

- Paper is downloaded once and cached in `msc-bench/.cache/`.
- Ports used: game 25566, RCON 25576, MSC 25590.
- Your own Claude Code plugins, skills and memory are kept out of runs.
