# Campaign platform matrix: second run

Run on the owner's Windows 11 PC, 2026-11-13 09:40Z to 10:30Z, from main `113058b` (36 commits after the baseline,
including the fixes for #166 to #169, #193, #200, #221, #223 and ADR 0030). Same script approach as the
[baseline](campaign-matrix-baseline.md): core built with `installMsc` into a temp folder, driven over MCP stdio, one
fresh workspace and one core process per cell, one cell at a time, Minecraft 26.3. Every cell ran once; nothing was
rerun.

Baseline steps, plus these checks:

- **clean_start** (#167): the `dev_cycle` start report says `no errors or warnings`. The raw `logs/latest.log` of
  every cell is kept with the run's results on the PC, so the noise filter can be checked against real logs.
- **player_test** is stricter (#166, #169): `tp` must move the player, `walk 8,101,8` must be reached, `use` must
  click the block within reach, and the player's own chat line must read `<MscMatrix> matrix hello`.
- **read_64cube** (ADR 0030): `world_summary` of the full 64x64x64 region `0,-64,0 63,-1,63` must count exactly
  bedrock 4096, dirt 8192 and grass_block 4096 (the superflat ground).
- **far_world** (#193, #221): a new join, `tp 3000.5,-60,3000.5`, chat, `hud`. Off Minestom, also
  `/execute in minecraft:the_nether run tp`, `/kill`, wait for the respawn, chat, `hud`. No template has a second
  world or instance, so the nether stands in for one. Minestom has only the far tp.
- **far_reads_x20** (#200, #223): 20 rounds of `world_slice` and `world_entities` over chunks 20,000+ blocks from
  spawn. Every call must answer. No tool reports the loaded chunk count, so the server's heap after a forced GC
  (`jcmd GC.run`, `GC.heap_info`) before and after the rounds stands in for it.

| Platform | Template | server_setup | dev_cycle | clean_start | agent_connected | build_batch | world_slice | world_summary | world_render | structure_save | structure_paste | undo | player_test | world_entities | pack | pack_reached | read_64cube | far_world | far_reads_x20 | server_stop | no_java_left | core_exit |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| minestom | minestom-server | ok | ok 9.8 | ok | ok | ok 0.2 | ok | ok 0.1 | ok 0.2 | ok | ok 0.1 | ok 0.1 | ok 6.7 | ok | ok 0.1 | ok 3.1 | ok 0.1 | ok 3.1 | ok 2.2 | ok 0.5 | ok | ok |
| minestom | minestom-game | ok | ok 18.8 | ok | ok | ok 0.1 | ok | ok 0.1 | ok 0.2 | ok | ok 0.1 | ok 0.1 | ok 6.7 | ok | ok 0.1 | ok 3.1 | ok 0.1 | ok 3.1 | ok 2.2 | ok 0.5 | ok | ok |
| paper | paper-plugin | ok 2.8 | ok 25.0 | ok | ok | ok 0.3 | ok | ok 0.1 | ok 0.2 | ok | ok 0.2 | ok 0.1 | ok 6.1 | ok | ok 0.1 | ok 3.1 | ok 0.1 | partial 9.5 (#253) | ok 2.2 | ok 1.0 | ok | ok |
| paper | paper-game | ok 3.1 | ok 25.5 | ok | ok | ok 0.3 | ok 0.1 | ok 0.1 | ok 0.2 | ok | ok 0.2 | ok 0.1 | ok 7.2 | ok | ok 0.1 | ok 3.1 | ok 0.2 | partial 9.5 (#253) | ok 2.1 | ok 0.9 | ok | ok |
| paper | paper-server | ok 2.9 | ok 23.7 | ok | ok | ok 0.3 | ok 0.1 | ok 0.1 | ok 0.2 | ok | ok 0.2 | ok 0.1 | ok 6.1 | ok | ok 0.1 | ok 3.1 | ok 0.1 | partial 9.6 (#253) | ok 2.0 | ok 0.8 | ok | ok |
| purpur | paper-plugin | ok 5.8 | ok 26.4 | ok | ok | ok 0.3 | ok 0.1 | ok 0.1 | ok 0.2 | ok | ok 0.1 | ok 0.1 | ok 6.1 | ok | ok 0.1 | ok 3.1 | ok 0.1 | partial 9.4 (#253) | ok 2.1 | ok 0.9 | ok | ok |
| purpur | paper-game | ok 6.2 | ok 23.9 | ok | ok | ok 0.3 | ok 0.1 | ok 0.1 | ok 0.2 | ok | ok 0.1 | ok 0.1 | ok 6.1 | ok | ok | ok 3.1 | ok 0.1 | partial 9.5 (#253) | ok 2.1 | ok 0.9 | ok | ok |
| purpur | paper-server | ok 4.3 | ok 24.8 | ok | ok | ok 0.3 | ok 0.1 | ok 0.1 | ok 0.2 | ok | ok 0.1 | ok 0.1 | ok 6.2 | ok | ok 0.1 | ok 3.1 | ok 0.1 | partial 9.5 (#253) | ok 2.1 | ok 0.9 | ok | ok |
| fabric | fabric-mod | ok 1.2 | ok 26.5 | ok | ok | ok 0.2 | ok | ok 0.1 | ok 0.2 | ok 0.1 | ok 0.1 | ok 0.1 | ok 5.8 | ok | ok 0.1 | ok 3.1 | ok 0.2 | ok 9.5 | ok 3.5 | ok 0.5 | ok | ok |
| fabric | fabric-game | ok 1.2 | ok 26.6 | ok | ok | ok 0.2 | ok 0.1 | ok 0.1 | ok 0.2 | ok | ok 0.1 | ok 0.1 | ok 5.8 | ok | ok 0.1 | ok 3.1 | ok 0.1 | ok 9.6 | ok 3.5 | ok 0.5 | ok | ok |
| quilt | fabric-mod | ok 7.5 | ok 18.7 | **FAIL** (#254) | ok | ok 0.1 | ok | ok 0.1 | ok 0.2 | ok | ok 0.1 | ok 0.1 | ok 5.8 | ok | ok 0.1 | ok 3.1 | ok 0.1 | ok 9.9 | ok 3.4 | ok 0.6 | ok | ok |
| quilt | fabric-game | ok 11.4 | ok 19.0 | **FAIL** (#254) | ok | ok 0.2 | ok | ok 0.1 | ok 0.2 | ok | ok 0.2 | ok 0.1 | ok 5.8 | ok | ok 0.1 | ok 3.1 | ok 0.2 | ok 9.4 | ok 3.4 | ok 0.5 | ok | ok |
| neoforge | neoforge-mod | ok 14.3 | ok 12.9 | ok | ok | ok 0.2 | ok | ok 0.1 | ok 0.2 | ok | ok 0.1 | ok 0.1 | ok 6.3 | ok | ok | ok 3.1 | ok 0.1 | ok 9.7 | ok 3.4 | ok 0.5 | ok | ok |
| forge | forge-mod | ok 27.1 | ok 12.1 | **FAIL** (#254) | ok | ok 0.2 | ok | ok 0.1 | ok 0.2 | ok | ok 0.2 | ok 0.1 | ok 5.7 | ok | ok 0.1 | ok 3.1 | ok 0.1 | ok 9.6 | ok 3.5 | ok 1.1 | ok | ok |

## Notes

- **13 of 14 cells pass every baseline step. All 14 pass the new player_test, read_64cube and far_reads checks.**
  The failures are clean_start on Forge and both Quilt cells (#254), plus a partial far_world on Paper and Purpur
  (#253).
- **#166 and #169 fixed:** on Minestom, `tp` now answers `moved to 2.50,101.00,2.50`, and walk, use and chat
  (`<MscMatrix> matrix hello`) behave as they do on the other six platforms.
- **#167 mostly fixed:** NeoForge and Fabric now start with `no errors or warnings`. Forge still reports `0 errors,
  4 warnings` (`Could not find resource pack for mod file ...`, `Missing data pack mod:...`). Quilt still reports
  `0 errors, 3 warnings` (`Not remapping mods because intermediary is missing!`, two `Assets URL ... uses unexpected
  schema`) and prints its loader timing lines. Filed as #254.
- **#253 (new, parity):** after `/kill` in the nether, Paper and Purpur report no death and no respawn, and the
  test player stays in the nether. Fabric, Quilt, NeoForge and Forge report `stopped: died`, `[death]`,
  `[respawned]` and move it back to the overworld. The world change itself is reported on every platform
  (`[changed world] minecraft:the_nether`), and player_test kept working after the far tp and the world change.
- **#200 and #223 (Minestom chunks):** 40 far reads, no errors, 2.2 s in total. Minestom's heap after GC was 37 MB
  before and 37 MB after, so no chunks were leaked. The mod platforms grew 7 to 15 MB, which fits vanilla keeping
  chunks for a short time, and is not filed. On Paper and Purpur the script did not find the server process
  (`paper.jar` did not match its filter), so there is no heap figure for them.
- **ADR 0030:** the 64x64x64 read was exact on every platform and took 0.1 to 0.2 s.
- **#168 fixed in code:** `LoaderInstaller.wroteNothing` now appends the installer's output tail. The installer did
  not fail in this run, so the new message was not seen.
- `server_stop` took 0.5 to 1.1 s, no java process was left in any cell, and the core exited 0 in every cell.

## Differences from the baseline

| | Baseline | Run 2 |
|---|---|---|
| Cells passing (first run) | 10 of 14 (3 environment or script failures, 1 Quilt installer flake) | 14 of 14 baseline steps; 3 clean_start fails (#254) |
| Minestom tp / walk / use / chat | partial (#166, #169) | pass |
| Clean start reports | errors on NeoForge, Forge, Fabric, Quilt | clean except Forge (4 warnings) and Quilt (3 warnings) |
| Start (dev_cycle total), s | Minestom 9 to 11, Forge 12, NeoForge 20, Quilt 19, Fabric 26 to 35, Paper and Purpur 24 to 28 | Minestom 10 to 19, Forge 12, NeoForge 13, Quilt 19, Fabric 27, Paper and Purpur 24 to 26 |
| player_test, s | 4.5 (Minestom, failing early) to 6.4 | 5.7 to 7.2 |

## Build and end-to-end test on Windows

From the worktree at `113058b`:

- `gradlew build --continue`: **BUILD SUCCESSFUL** in 55 s. 42 tasks executed, 47 up to date; all 14 module test
  tasks ran.
- `gradlew :msc-core:e2eTest`: **BUILD SUCCESSFUL** in 34 s.

## Tools

- `graphify update .` in the main checkout worked (4732 nodes, 13366 edges) and changed no tracked file. That
  checkout is at `cb9dd53` and was not pulled, so the graph is of that commit.
- ponytail plugin 5.3.3 is installed. A full audit was not run.

Issues filed in this run: #253, #254.
