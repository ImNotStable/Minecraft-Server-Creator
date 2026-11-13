# Campaign platform matrix: baseline

Run on the owner's Windows 11 PC, 2026-11-12 23:30Z to 2026-11-13, from main `cb9dd53` (= `650cb4d` plus the campaign
plan). Core built with `installMsc` into a temp folder and driven over MCP stdio by a script, one fresh workspace and
one core process per cell, one cell at a time. Minecraft 26.3 everywhere (the default). Part of the
[perfection campaign](campaign-2026-11.md), check 2.

Each cell: `project_scaffold`, `server_setup`, `dev_cycle` (`server_start` for paper-server, which has no Gradle
project), agent connected, `build_batch` (fill, set, line, helix), `world_slice`, `world_summary`, `world_render`,
`structure_save` and a paste (`build_batch structure ... file=`), `build_undo` of the paste (checked with a slice),
`player_test` (tp, walk, use, say, inventory), `world_entities` finds the test player, `texture` + `pack`, the pack
reaches the test player (`hud` shows `pack: loaded (HTTP 200, ..., sha1 matches)`), `server_stop`, no new java
process left (Gradle daemons excluded), the core exits 0 when stdin closes.

Cells show pass/fail and seconds (blank seconds: under 0.05 s).

| Platform | Template | server_setup | dev_cycle | agent_connected | build_batch | world_slice | world_summary | world_render | structure_save | structure_paste | undo | player_test | world_entities | pack | pack_reached | server_stop | no_java_left | core_exit |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| minestom | minestom-server | ok | ok 10.5 | ok | ok 0.1 | ok | ok | ok 0.1 | ok | ok | ok | partial 4.6 (#166) | ok | ok | ok 3.0 | ok 0.5 | ok | ok |
| minestom | minestom-game | ok | ok 8.9 | ok | ok 0.1 | ok | ok | ok 0.2 | ok | ok | ok | partial 4.5 (#166) | ok | ok 0.1 | ok 3.0 | ok 0.5 | ok | ok |
| paper | paper-plugin | ok 2.2 | ok 26.2 | ok | ok 0.4 | ok | ok 0.1 | ok 0.2 | ok | ok 0.2 | ok | ok 6.4 | ok | ok | ok 3.0 | ok 0.6 | ok | ok |
| paper | paper-game | ok 6.0 | ok 24.5 | ok | ok 0.3 | ok | ok 0.1 | ok 0.2 | ok | ok 0.1 | ok 0.1 | ok 6.3 | ok | ok | ok 3.0 | ok 0.6 | ok | ok |
| paper | paper-server | ok 2.4 | ok 28.2 | ok | ok 0.3 | ok | ok 0.1 | ok 0.2 | ok | ok 0.2 | ok 0.1 | ok 6.4 | ok 0.1 | ok 0.1 | ok 3.0 | ok 0.6 | ok | ok |
| purpur | paper-plugin | ok 2.1 | ok 23.8 | ok | ok 0.3 | ok | ok 0.1 | ok 0.2 | ok | ok 0.2 | ok 0.1 | ok 6.3 | ok | ok | ok 3.0 | ok 0.6 | ok | ok |
| purpur | paper-game | ok 5.1 | ok 25.6 | ok | ok 0.3 | ok | ok 0.1 | ok 0.2 | ok | ok 0.1 | ok 0.1 | ok 6.4 | ok | ok 0.1 | ok 3.0 | ok 0.6 | ok | ok |
| purpur | paper-server | ok 3.3 | ok 24.1 | ok | ok 0.3 | ok | ok 0.1 | ok 0.2 | ok 0.1 | ok 0.2 | ok 0.1 | ok 6.4 | ok | ok 0.1 | ok 3.0 | ok 0.6 | ok | ok |
| fabric | fabric-mod | ok 1.3 | ok 25.8 | ok | ok 0.2 | ok | ok 0.1 | ok 0.2 | ok | ok 0.1 | ok 0.1 | ok 6.2 | ok | ok 0.1 | ok 3.0 | ok 0.8 | ok | ok |
| fabric | fabric-game | ok 0.8 | ok 35.0 | ok | ok 0.2 | ok | ok 0.1 | ok 0.2 | ok | ok 0.1 | ok 0.1 | ok 6.3 | ok 0.1 | ok | ok 3.0 | ok 0.8 | ok | ok |
| quilt | fabric-mod | ok 7.8 | ok 18.7 | ok | ok 0.2 | ok | ok 0.1 | ok 0.2 | ok | ok 0.2 | ok 0.1 | ok 6.2 | ok | ok 0.1 | ok 3.0 | ok 0.7 | ok | ok |
| quilt | fabric-game | ok 6.6 | ok 18.5 | ok | ok 0.2 | ok | ok 0.1 | ok 0.2 | ok | ok 0.1 | ok 0.1 | ok 6.3 | ok 0.1 | ok 0.1 | ok 3.0 | ok 0.8 | ok | ok |
| neoforge | neoforge-mod | ok 11.1 | ok 19.7 | ok | ok 0.2 | ok | ok 0.1 | ok 0.2 | ok | ok 0.2 | ok 0.1 | ok 6.3 | ok | ok | ok 3.0 | ok 0.7 | ok | ok |
| forge | forge-mod | ok 22.4 | ok 11.6 | ok | ok 0.2 | ok | ok 0.1 | ok 0.2 | ok | ok 0.1 | ok 0.1 | ok 6.1 | ok 0.1 | ok | ok 3.0 | ok 1.3 | ok | ok |

## Notes and differences between platforms

- **All 14 cells pass** after reruns. Three failed on the first run for reasons outside the product: paper-server and
  purpur-paper-server (the script called `dev_cycle` on a template with no Gradle project; `server_start` passes),
  fabric-game (the Fabric launcher's download of the Minecraft server from launchermeta.mojang.com timed out), and
  quilt + fabric-mod (`server_setup` failed with "the Quilt installer finished but wrote no quilt-server-launch.jar"
  after 22.7 s, then passed in 7.8 s; the error hid the cause: #168).
- **Minestom, player_test (#166, parity):** `tp` is sent as the `/tp` chat command, which Minestom has no handler
  for; the result says `sent` and the player does not move, so `walk` and `use` fail. Every other platform moves
  the player. Marked "partial".
- **Minestom, chat (#169, parity):** the player's own message reads `chat.type.text (MscMatrix, matrix hello)`;
  elsewhere `<MscMatrix> matrix hello`.
- **Startup noise counted as errors (#167, parity):** a clean start reports `1 errors` on NeoForge and Forge (netty
  kqueue/epoll probing; Forge prints about 10k chars and is truncated, plus 18 config-correction warnings on first
  start) and on Fabric and Quilt (JNA Perflib registry message on Windows). Paper, Purpur and Minestom report `no
  errors or warnings`.
- Start times (dev_cycle "server ready"): Minestom about 4.5 s, Forge 9 s, NeoForge 11 s, Quilt 15 s, Fabric 21 s,
  Paper and Purpur 22 to 28 s. Tool calls on a running server are 0.0 to 0.4 s on every platform; player_test about
  6.3 s on the Paper and mod platforms and 4.5 s on Minestom (shorter because its walk fails early).
- `server_stop` takes 0.5 to 1.3 s and left no java process in any cell; the core exited 0 in every cell.
- Paper-game moves the player to its own spawn on join; the other templates leave the vanilla spawn. Template choice,
  not a defect.
