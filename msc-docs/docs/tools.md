# MCP tool catalogue

The AI talks to `msc-core` over MCP (stdio). This page lists every tool, its arguments and what its result looks like. Design reasons are in [ADR 0005](adr/0005-core-tools.md).

The tool tables are generated from the tool definitions in `msc-core` (`io.github.imnotstable.msc.core.tools`): the "What it does" column and the argument texts are exactly what the AI sees. Do not edit between the `<!-- tools: ... -->` and `<!-- /tools -->` markers; change the code and run `./gradlew :msc-core:toolDocs`. `./gradlew build` fails while this page or the wiki's tool page is out of date ([ADR 0026](adr/0026-generated-tool-docs.md)). Everything outside the markers, such as the "Results" notes, is written by hand.

## Rules every tool follows

- Results are plain text, no Markdown and no JSON, shaped to be read by a model.
- Every result has a size budget. One token is counted as four characters. The default cap is 6,000 characters (about 1,500 tokens); tools with a `maxChars` argument accept 200 to 24,000. A cut result ends with `[truncated: N more lines, M chars. <what to do>]`, and that line is inside the cap. The tool registry applies the cap to every result, errors included, so no tool can exceed it. A tool spends the cap as one budget: a result of several parts (`dev_cycle`'s failed tests and startup log, `server_start`'s header and startup log, the outputs of several `server_command` commands, a `texture` drawing and the lines around it) keeps its fixed lines and its documented last line, and splits what they leave between its parts, so each part is cut with its own hint. A part whose share is too small to be useful (under 80 characters) is left out and shown as `[cut]`, the larger ones first, so the others show more, and one line before the last line says `[N of M outputs cut to fit. <what to do>]`; a part cut so short that its `[truncated: ...]` line does not fit ends in `[M chars cut]` instead. The registry's cut (`Narrow the call.`) is only the safety net for a result that still came out longer.
- String arguments are one line: a control character (NUL, a line break, a bell) is refused with `error: argument <name> has a control character (U+....)`. The exceptions are `build_batch`'s `steps` and `server_command`'s `command`, which may be several lines (line breaks and tabs only), and `pack`'s `description`, which may have one line break (Minecraft shows a pack description on two lines; a third line, a tab or any other control character is refused).
- Path arguments (`project_scaffold dir`, `dev_cycle project`, `structure_save file`, `world_summary diff`, `build_batch`'s `file=`, `pack file`, a `.png` `texture name`) are relative to the workspace and must stay inside it once `..` is applied; only `msc_workspace`'s `path` may name a folder anywhere. So `dev_cycle` builds only a project inside the workspace: to work on one elsewhere, switch to its folder with `msc_workspace`. A path the file system cannot hold (a control character, a name longer than 255 bytes, a character the platform refuses) is refused in one line naming the argument, before anything is written; a folder that cannot be created is refused in one line naming the folder and the reason. No tool answers `error: internal: ...` for bad input (`BadInputBehaviourTest` calls every tool with every string argument set to such values).
- A number with a stated range (such as `limit` or `timeoutSeconds`) outside it is refused with the range, never clamped silently.
- Errors come back as `error: <what is wrong and what to do>` with MCP `isError` set. They never contain a Java stack trace.
- Coordinates are strings: a position is `"x,y,z"`, a region is `"x1,y1,z1 x2,y2,z2"` (two corners, inclusive, any order), an area is `"x1,z1 x2,z2"`.
- Block names may omit `minecraft:`. Properties use vanilla syntax: `oak_stairs[facing=north]`.
- `world` is optional everywhere and defaults to the server's first world.
- Every call is logged (see "Tool log").
- World and build tools fail at once with `error: the server is not running...` when the core has no server running and none was attached with `MSC_CORE_ADDRESS`.
- <!-- tool-list -->The tool definitions (26 tools: names, descriptions, argument schemas) are 15,685 characters, about 3,922 tokens in every conversation before MCP framing.<!-- /tool-list --> `McpBridgeTest` fails if the tool list as the client receives it grows past 16,000 characters ([ADR 0019](adr/0019-smaller-tool-surface.md), [ADR 0023](adr/0023-texture-tools.md)).
- Results are text. The one exception is `world_render`, which adds one PNG image next to its text.

## Typical loop

`project_scaffold` once, `server_setup` once, then edit code and call `dev_cycle` until it reports `no errors or warnings`. Look names up with `api_search` instead of guessing. Look at the world with `world_summary`, then `world_slice`; build with `build_batch`, a whole build per call.

## Status

<!-- tools: msc_status msc_workspace -->
| Tool | Arguments | What it does |
| --- | --- | --- |
| `msc_status` | `stats`? (boolean): Show call stats. Default false | Where things stand: workspace, platform, server, agent, API index. stats=true: this session's calls, errors, time and result tokens per tool instead. |
| `msc_workspace` | `path`? (string): Folder, absolute or relative<br>`create`? (boolean): Create it if missing | Switch MSC to another project folder without a restart. Stops the old dev server unless detached. No path: show the current one. |
<!-- /tools -->

Results:

- `msc_status`: 6 to 8 lines: core version, workspace, platform, server state (`running, detached`, `running, left by an earlier session` with its pid, or `running under another session that is still running` with the server's and that session's core pid, ADR 0037), agent, API index, then one line per setup warning: the workspace is the MSC repo, and the install is older than its checkout or a newer install sits beside it. The same warnings go in front of the session's first tool result. With `stats=true`, a table of this session's calls per tool instead.
- `msc_workspace`: 1 to 3 lines. Without `path`, or with the current one, 1 line (`workspace: <path>` or `workspace is already <path>`). A switch: `workspace: <path>`, then a line on the old workspace's dev server only when one ran (`stopped the dev server of ...`, or that its detached server keeps running), then `server folder: ...; platform: ...`. Closes the old workspace like a session end and opens the new one; the server folder and tool log move along when they were the defaults. A relative `path` is relative to the current workspace.

## Project and server lifecycle

<!-- tools: project_scaffold server_setup server_start server_detach server_stop dev_cycle server_logs server_command server_wait -->
| Tool | Arguments | What it does |
| --- | --- | --- |
| `project_scaffold` | `name` (string): Project name, for example MyPlugin<br>`dir`? (string): Target directory in the workspace. Default: the workspace itself<br>`template`? (string): paper-plugin (default), fabric-mod (also Quilt), neoforge-mod, forge-mod, minestom-server, or a minigame base (kit, stages, saved progress, /game debug commands): paper-game, fabric-game, minestom-game<br>`package`? (string): Java package. Default com.example.&lt;name&gt;<br>`acceptEula`? (boolean): Only on the user's yes to https://aka.ms/MinecraftEULA; used only by the paper-server template's eula.txt, not kept for this machine | Create a new project from a template (Gradle KTS, tests wired in). Target directory must be empty. |
| `server_setup` | `version`? (string): Minecraft version. Default 26.3<br>`platform`? (string): paper, purpur, fabric, quilt, neoforge, forge or minestom. Default: the project's<br>`mode`? (string): build (creative, peaceful) or game (survival, normal)<br>`acceptEula`? (boolean): Only on the user's yes to https://aka.ms/MinecraftEULA; kept for this machine | Prepare the dev server: download or install it, write dev defaults, install the msc agent. |
| `server_start` | `timeoutSeconds`? (integer): Default 180, 1 to 3600<br>`acceptEula`? (boolean): Only on the user's yes to https://aka.ms/MinecraftEULA; kept for this machine | Start the dev server and wait until it is ready. Returns startup errors only. |
| `server_detach` | none | Keep the dev server and the pack host running after this session ends, so the user can play. Once this session ends, the next session's server_start, dev_cycle or server_stop stops them. |
| `server_stop` | none | Stop the dev server. |
| `dev_cycle` | `project`? (string): Project directory in the workspace. Default: the workspace<br>`restart`? (boolean): Restart the server after deploying. Default true<br>`maxChars`? (integer): Result size cap. Default 6000 | Build, deploy and restart in one call: the edit-test loop. Tests run during the restart; an unchanged jar skips it. Returns build, test and startup errors, and phase times. |
| `server_logs` | `mode`? (string): errors (default), tail or grep<br>`pattern`? (string): Regex for mode=grep<br>`lines`? (integer): Line count for tail and grep. Default 30<br>`all`? (boolean): Look at the whole log, not only since the last start. Default false<br>`maxChars`? (integer): Result size cap. Default 6000 | Read the server log cheaply. mode=errors: warnings and errors with trimmed stack traces, repeats collapsed. mode=tail: last lines. mode=grep: lines matching a regex. |
| `server_command` | `command` (string): Command without the leading slash; several: one per line | Run console commands on the server and return their output. |
| `server_wait` | `until` (string): Regex, case-insensitive<br>`timeoutSeconds`? (integer): Default 60, 1 to 600 | Wait until a log or chat line matches a regex, instead of polling. |
<!-- /tools -->

Results:

- `project_scaffold`: 5 to 7 lines: where the project was created, its `projectId`, `packageName` and `version`, then for NeoForge and Forge a line that the first build started in the background, then the next step: 1 line, or 2 when a server that needs the EULA (any platform but Minestom) is not set up and the EULA is not accepted for this machine (the EULA question, then `On yes: server_setup acceptEula=true, ...`). Remembers the template's platform for the workspace.
- `server_setup`: one line per step that has something to say, in this order: `wrote N default config files` (only the files the server folder lacked); with `mode`, `mode game: survival, normal difficulty from the next start` or `mode build: creative, peaceful difficulty from the next start`; the install (Paper `paper <version> build <n> (downloaded)` or `(cached)`; Purpur `purpur <version> build <n> (installed)` or `(already there)`; NeoForge and Forge `<name> <version> (installed)` or `(already there)`; Fabric `fabric launcher for ...` then `fabric api <version>`; Quilt `quilt loader <version> (installed)` then `fabric api <version>`); `agent installed in mods/` (or `plugins/`), or `warning: msc <agent> agent not found ...`, naming the agent the platform runs and the jar to put in `.msc/agent` (`msc-agent-paper.jar` for Paper and Purpur, `msc-agent-fabric.jar` for Fabric and Quilt); the EULA: `eula.txt has eula=true from the owner's one-time acceptance for this machine (...)`, or `note: eula.txt has eula=true; ...` when the file already says so, or when it is not accepted two lines, `the Minecraft EULA is not accepted, so server_start refuses. Next: ask the user ...` and `On yes: server_setup acceptEula=true ...`; the first `acceptEula=true` adds `Minecraft EULA (...) accepted for this machine ...` before the EULA line; last `<platform> server ready in <folder>; next: server_start or dev_cycle`. Minestom: `minestom needs no server download ...`, after `mode ignored: ...` when `mode` is given. `mode=game` sets `gamemode=survival`, `difficulty=normal` and `force-gamemode=true` in `server.properties` (`mode=build`: creative, peaceful); without `mode` a new server gets build defaults and an existing one keeps its settings.
- `server_start`: up to 6,000 chars: `server ready in 8.2s, agent connected`, then `world world: ground is y=-61 (most common top block around 0,0; build from y=-60)` (dev worlds are superflat, far below y=64), then `mode: creative, peaceful difficulty (server_setup mode=game for survival, normal: ...)` from `server.properties`, plus startup warnings and errors only. Fails at once with a one-line error, before it changes anything, if there is no server to start or `eula.txt` lacks `eula=true` and the EULA is not accepted for this machine (nor with `acceptEula=true` on this call). Replaces an installed agent jar that differs from the one in `.msc/agent` (or `MSC_AGENT_JAR`) first, and says `agent jar updated`. Just before the start, also removes what an interrupted earlier call left in `plugins/`, `mods/` or `libs/` (such as `x.jar.msc-removing` from older cores, which Quilt would load as a second copy of the mod) and says `removed N files an interrupted earlier call left`.
- `server_detach`: 2 or 3 lines: `server detached: ...`, then `server.properties now offers the pack too, ...` only when a built pack's host keeps running and the server folder has a `server.properties`, then that world tools work until the session ends. The pack host, if a pack was built, moves to a standalone process on the same port (pid in `.msc/pack-host.pid`), and `resource-pack`, `resource-pack-sha1`, `resource-pack-id` and `require-resource-pack` go into `server.properties`, so a restart outside MSC still sends the pack. The server keeps this session as its owner: while this session runs, another session's tools refuse to touch it (ADR 0037). Once it ends, the next `server_start`, `dev_cycle` or `server_stop` stops both, the server over RCON; the next start serves the pack again on the same port and updates `server.properties` (or clears it if the pack is gone).
- `server_stop`: 1 line. Also stops a server an earlier core left running (pid and start time in `.msc/server.pid`, with the pid and start time of the core that started it), as `server_start` and `dev_cycle` do first: over RCON so it saves (at `server-ip` and `rcon.port` from `server.properties`, read as the server reads them), killed only if that fails. The pid file stays until that server is gone. A leftover that survives the kill is an error naming its pid, what was tried and how to stop it by hand; while it runs, `server_start` and `dev_cycle` refuse too (a new server could not take its world or port, and its pid would replace the leftover's record), and only kill it again briefly instead of waiting the whole stop out again (`resisting` in the pid file). `server_stop` tries the whole stop again each time. A server whose core still runs (another session open on the same workspace) is no leftover: `server_start`, `server_stop`, `dev_cycle` and `server_setup` then refuse in one line naming that session's core pid (use that session, or end it) and change nothing (ADR 0037). A pid file from cores before that names no owner and is a leftover as before.
- `dev_cycle`: up to `maxChars`. Gradle builds only the deployable jar (`mscJar`, added by `.msc/msc-init.gradle`; on Minestom a thin `server.jar` plus `libs/`), then the tests (`check`) run while the server restarts. A jar identical to the one the running server loaded is not redeployed and the server keeps running. When the server it restarts would refuse to start (the EULA not accepted), it refuses at once with that one-line error, before building or changing anything. On a failed compile: compiler errors. Failed tests come first (`TESTS FAILED`; the new jar is deployed anyway). Then startup warnings and errors, or `no errors or warnings`, and a last line `time: jar, stop, start, agent, tests, total`.
- `server_logs`: up to `maxChars`; see "How logs are condensed" below.
- `server_command`: up to 6,000 chars. A command with bad syntax fails with the parser's message (`command failed`, then where it stopped). Lines the server console printed while the command ran (broadcasts such as `/say`, which never reach the sender) follow under `console during the command:`.
- `server_wait`: 1 to 6 lines: `matched after 4.1s: <line>`, or `no line matched in 60s` (or `the server stopped after ...`) followed by up to the last 5 new lines. Looks at lines since the last `server_command`, `server_wait` match or start, so a line printed between two calls is not missed and one line never matches twice. Not an error on timeout. `timeoutSeconds` is at most 600. Only for a server this core started.

How logs are condensed: the dev server's fixed banners (running as root or administrator, offline mode) and the Windows system-report noise (`[oshi.*]` messages, `Failed to get system info for ...`) are dropped. Otherwise only WARN and ERROR entries and anything with a stack trace are kept. In a trace, the exception lines, the first frame and all frames outside framework packages (`java.`, `net.minecraft.`, `org.bukkit.`, `io.papermc.`, the mod loaders' `net.fabricmc.`, `net.neoforged.`, `net.minecraftforge.`, `cpw.mods.`, Mixin and similar) are kept; runs of framework frames become `... N framework frames`. Identical entries are shown once with a count. By default `server_logs` looks only at lines since the last start.

### Platforms

The workspace has one platform: `paper` (default), `purpur`, `fabric`, `quilt`, `neoforge`, `forge` or `minestom` ([ADR 0027](adr/0027-more-platforms.md)). `project_scaffold` remembers the template's platform in `<workspace>/.msc/platform`, `server_setup platform=...` sets it, and without either the core looks at the project files: `quilt.mod.json` is Quilt, `fabric.mod.json` Fabric, `META-INF/neoforge.mods.toml` NeoForge, `META-INF/mods.toml` Forge, `paper-plugin.yml` or `plugin.yml` Paper, a Minestom dependency in the build files Minestom. Purpur cannot be told from a Paper plugin, so it is only ever the remembered platform; `dev_cycle` keeps a remembered Purpur or Quilt for a Paper or Fabric project.

| Platform | `server_setup` installs | Server starts as | `dev_cycle` deploys to | EULA step |
|---|---|---|---|---|
| `paper` | `paper.jar`, agent in `plugins/` | `java -Xmx2G -jar paper.jar --nogui` | `plugins/<jar name without version>.jar` | yes |
| `purpur` | `purpur.jar` from Purpur's API, the Paper agent in `plugins/` | `java -Xmx2G -jar purpur.jar --nogui` | `plugins/<jar name without version>.jar` | yes |
| `fabric` | `fabric-server-launch.jar` (fetches the Minecraft server on first start), `mods/fabric-api.jar`, agent in `mods/` | `java -Xmx2G -jar fabric-server-launch.jar nogui` | `mods/<jar name without version>.jar` | yes |
| `quilt` | `quilt-server-launch.jar` and the Minecraft server (Quilt's installer), `mods/fabric-api.jar`, the Fabric agent in `mods/` | `java -Xmx2G -jar quilt-server-launch.jar nogui` | `mods/<jar name without version>.jar` | yes |
| `neoforge` | NeoForge's installer run in the server directory (its server starter jar becomes `server.jar`, plus `libraries/`), agent in `mods/` | `java -Xmx2G -jar server.jar nogui` | `mods/<jar name without version>.jar` | yes |
| `forge` | Forge's installer run in the server directory (its runnable shim jar copied to `forge-server.jar`, plus `libraries/`), agent in `mods/` | `java -Xmx2G -jar forge-server.jar nogui` | `mods/<jar name without version>.jar` | yes |
| `minestom` | nothing (the agent is built into the project) | `java -Xmx1G -jar server.jar` | `server.jar` (thin: its manifest points at `libs/`, which `dev_cycle` keeps in step) in the server directory | no |

Installers and downloads are cached in `.msc/cache`; an installer runs only when the server directory lacks that loader version. NeoForge, Forge and Quilt take the versions pinned in this build and `server_setup` refuses a Minecraft version they do not target.

EULA step: installing or using MSC never accepts the Minecraft EULA. `server_setup` and `server_start` write `eula=true` into the server's `eula.txt` only on the owner's own yes: either `acceptEula=true` on that call, passed after the owner said yes, or the owner's one-time acceptance for the machine in `~/.msc/eula.json` ([ADR 0020](adr/0020-machine-eula.md)), and they say so. That file is written only on an explicit opt-in: `Main eula accept` run by the owner in their own terminal (the line in `~/.msc/core/eula-accept.txt`) with a typed `yes`, installing with `installMsc -Pmsc.acceptEula=true`, or the first `acceptEula=true` on `server_setup` or `server_start`; that first time the result says the acceptance now covers the machine. `project_scaffold acceptEula=true` does not write it.


## API index

Built on first use from the platform's jars in the Gradle cache (or `MSC_API_JARS`), by reading class files directly: Paper API and Adventure for Paper and Purpur, the Minecraft server jar from Loom's cache plus Fabric API and loader for Fabric and Quilt, NeoForge and its event bus for NeoForge, Forge with its event bus and FML core for Forge, Minestom and Adventure for Minestom. No network, no Javadoc. Parameter names appear only when the jar records them; the published paper-api jar does not, so signatures show types only.
<!-- tools: api_search api_class -->
| Tool | Arguments | What it does |
| --- | --- | --- |
| `api_search` | `query` (string): Words that must all match, for example "player teleport"<br>`kind`? (string): class, interface, enum, record, annotation or event<br>`limit`? (integer): Default 15, at most 100<br>`maxChars`? (integer): Result size cap. Default 6000 | Search the platform API (Paper, Fabric, NeoForge, Forge or Minestom) by class, method, field or constant name; one line per match with full signature. |
| `api_class` | `name` (string): Simple or full class name, for example Player or org.bukkit.entity.Player<br>`filter`? (string): Only members whose name contains this<br>`inherited`? (boolean): Include members of supertypes<br>`limit`? (integer): Default 40, at most 300<br>`maxChars`? (integer): Result size cap. Default 6000 | Show one API class: supertypes, constants (with inherited) and public members with signatures. |
<!-- /tools -->

Results: `api_search` gives one line per match, such as `interface org.bukkit.entity.Player` or `Player#void sendMessage(String)`, at most `limit` lines (at most 100); a cut list ends `[N more; add words or raise limit (at most 100)]`, or at 100 `[N more; add words to narrow the search]`. `api_class` gives a header with supertypes, enum constants, then one member signature per line, at most `limit` (at most 300; the cut line drops `raise limit` at 300 the same way); constants inherited from interfaces and superclasses are always listed (`<- Owner`).

## Seeing the world

<!-- tools: world_summary world_slice world_entities world_heightmap world_render -->
| Tool | Arguments | What it does |
| --- | --- | --- |
| `world_summary` | `region` (string): Two corners, inclusive: "x1,y1,z1 x2,y2,z2"<br>`world`? (string): Default: main world<br>`path`? (string): "x,y,z x,y,z": can a player walk/jump from A to B; else the wall or gap<br>`diff`? (string): structure_save file of this region: what changed since | Summarize a region in a few lines: block counts (with where rare ones are), non-air bounds, fill per layer, entities. Use before world_slice on unknown areas and to find a block. |
| `world_slice` | `region` (string): Two corners, inclusive: "x1,y1,z1 x2,y2,z2"<br>`world`? (string): Default: main world<br>`states`? (boolean): Show block properties such as facing<br>`maxChars`? (integer): Max result chars. Default 6000 | See blocks as text: one grid per Y layer with a legend, in the structure text build_batch takes. Best up to about 32x32 per layer. |
| `world_entities` | `region` (string): Two corners, inclusive: "x1,y1,z1 x2,y2,z2"<br>`type`? (string): Only this type, such as zombie<br>`limit`? (integer): Default 50, at most 1000<br>`ids`? (boolean): Add UUIDs<br>`world`? (string): Default: main world | List entities in a region: type, name, position, health, tags. Loaded chunks only, except on Minestom. |
| `world_heightmap` | `area` (string): Two corners: "x1,z1 x2,z2"<br>`world`? (string): Default: main world<br>`maxChars`? (integer): Max result chars. Default 6000 | Surface height per column over an area, as a compact grid. |
| `world_render` | `region` (string): Two corners, inclusive: "x1,y1,z1 x2,y2,z2"<br>`view`? (string): Camera. Default iso<br>`size`? (integer): Longest side in pixels, 64 to 1568. Default 512<br>`entities`? (boolean): Mark entities as numbered dots and show hologram and sign text. Default true<br>`world`? (string): Default: main world | See a region as a small PNG image to check how a build looks. Views: iso, top, north, south, east, west, iso-ne/nw/sw/se, "eye x,y,z look x,y,z". |
<!-- /tools -->

Results:

- `world_summary`: for regions up to 8,388,608 blocks, a line with the size and air share, then the top 15 block counts (with where rare ones are), the bounds of non-air blocks and the non-air count per layer (or `empty: only air`), then entity counts when there are entities. With `path`: whether a player can walk and jump from A to B inside the region (walk, step up 1, drop up to 3, jump gaps up to 3 flat, 2 going up, 4 going down, ladders, vines, water; block shapes from the test player's own collision table, so closed doors and gates are in the way), with the route's turning points, or the closest point and the wall, gap, fence, closed door or lava that stops the player. With `diff`: blocks changed since the snapshot, counts per kind and the first 12 positions.
- `world_slice`: the region in the text structure format (below), up to 128 wide and 1,048,576 blocks. A 16 x 16 slice costs about 70 tokens per layer; identical and uniform layers cost one line each.
- `world_entities`: up to 6,000 chars, regions up to 2,048 x 2,048 wide at any height. A count line with totals per type, then one line per entity sorted by type and position: `zombie "Brute" 2.5,-60,3 hp 7/20 tags boss,wave`. The name is a player name, custom name or a text display's text. Loaded chunks only: with no player online, run `forceload add x1 z1 x2 z2` first (an empty result says so, with the region's corners). Minestom lists every entity of the instance, so needs no forceload, and has no scoreboard tags.
- `world_heightmap`: areas up to 256 x 256. Min, max, mean, then one character per column (`height - min`, 0-9 then a-z); when the range is over 35, numbers per column with runs as `v*n` and repeated rows as `(xN)`.
- `world_render`: a PNG as MCP image content (about 330 tokens at 512 px), drawn by `msc-renderer` with the pack's textures, for regions up to 16,000,000 blocks (read in protocol-sized pieces and stitched). `view` also takes `eye x,y,z yaw N pitch N`. The caption gives the view, pixels per block, image size and token estimate, visible blocks, numbered entity markers coloured by kind (player, hostile, creature, object) with positions, hologram text drawn in the image (text displays, named armor stands), markers behind blocks drawn hollow; isometric views label their corners with block coordinates. A copy is saved under `<workspace>/.msc/renders/`.

## Testing a game

- `server_setup mode=game` before testing combat, damage or hunger: the default dev server is creative and peaceful, so mobs despawn and players take no damage.
- `world_entities` checks wave spawns, NPCs and holograms (text displays) without a player.
- `server_wait until="Wave 3"` waits for a log or chat line instead of polling `server_command`. Log what the game does (stage changes, wins, losses) so it can be waited for.
- `server_command "tick sprint 600"` runs 600 ticks (30 game seconds) as fast as the server can, so timers and waves come sooner. `tick rate 40` doubles the speed; `tick rate 20` restores it.
- Chunks with no player nearby are not loaded, so nothing in them moves: mobs spawned there freeze and redstone stops. Keep game areas loaded with a plugin chunk ticket (Paper: `world.addPluginChunkTicket(chunkX, chunkZ, plugin)`) or `forceload add x1 z1 x2 z2`. Vanilla spawner blocks and natural spawning also need a player close by, so spawn waves from the plugin.


## Text structure format

Used by `world_slice` (output) and `build_batch` `structure` steps (input), so a slice can be edited and written back.

```
legend: .=air #=stone_bricks g=glass
y=64
#####
#...#
#####
y=65 same as y=64
y=66 all #
```

- One character per block. Rows run from the lowest Z (top row) to the highest; columns from the lowest X (left) to the highest.
- `.` is air and `_` means "leave unchanged", unless the legend redefines them.
- Layers are listed by Y. In a `structure` step the numbers only give the order: the lowest layer is placed at the step's position.
- `y=N same as y=M` repeats a layer; `y=N all #` fills a layer with one symbol or block.
- Symbols are handed out by frequency. With `states=false` (default) blocks that differ only in properties share a symbol.

## Building

<!-- tools: build_batch build_undo structure_save -->
| Tool | Arguments | What it does |
| --- | --- | --- |
| `build_batch` | `steps` (string): The steps, one per line<br>`world`? (string): Default: main world<br>`physics`? (boolean): Apply block updates<br>`dryRun`? (boolean): Only check names and count blocks | The one build tool. All steps are one edit with one undo id; every block name is checked before any is placed. Put a whole build in one call, not a generator script.<br>One step per line; later steps win. B = a block or a mix like grass_block:78,moss_block:10. Every step takes replace=a,b (change only those; air = empty), seed=N, repeat=N step=DX,DY,DZ.<br>fill X,Y,Z X,Y,Z B [hollow\|outline\|walls]<br>set X,Y,Z B<br>cylinder X,Y,Z r=R h=H B [hollow] [thick=T] [inner=R]   (X,Y,Z = bottom centre)<br>ring X,Y,Z r=R B [h=H] [thick=T]<br>disc X,Y,Z r=R B [inner=R]<br>sphere\|dome X,Y,Z r=R B [hollow] [thick=T]   (dome: X,Y,Z = floor centre)<br>line X,Y,Z X,Y,Z B [w=W]<br>helix X,Y,Z r=R h=H B [turns=T] [w=W] [start=DEG]   (bottom centre; turns&lt;0 anticlockwise; start=0 north)<br>bands X,Y,Z [h=H] R1=B R2=B ... [jitter=J]   (rings to each radius: land, beach, water)<br>structure X,Y,Z [X,Y,Z ...] [rotate=90] [face=X,Z] [anchor=center] [air=false] [file=PATH]<br>  then the text and "end", unless file= (.schem or text). Text: "legend: #=stone g=glass", then per layer "y=N" and rows (z) of symbols (x); "." air, "_" unchanged; "y=N same as y=M", "y=N all #". rotate turns clockwise seen from above; face=X,Z turns each copy's north side (first row) toward X,Z.<br>copy X,Y,Z X,Y,Z to=X,Y,Z [structure options]   (reads the world before this call) |
| `build_undo` | `edit`? (string): Edit id such as e3. Default: the most recent edit | Undo a build_batch edit. |
| `structure_save` | `region` (string): Two corners, inclusive: "x1,y1,z1 x2,y2,z2"<br>`file` (string): Workspace path such as structures/house.schem<br>`world`? (string): Default: main world | Save a region as a Sponge schematic (.schem) file, blocks only. |
<!-- /tools -->

`build_batch` answers `edit eN: <count> blocks changed, region <box>`, plus a `blocks:` line when it placed more than one kind, for up to 8,388,608 blocks. The id undoes the whole edit, even when the core split it into several agent calls; ids are remembered for the life of the core process, but the server keeps only the last 8,388,608 changed blocks across all edits, so the oldest edits can be undone only partly (`undid eN partly: ...` says how many parts were lost). If an undo stops half-way (timeout, no server), the same id undoes the rest. An undo puts back block entity data too (sign text, container items) on every platform. `copy` reads the world before the call, so source and target may overlap. See [ADR 0015](adr/0015-build-batch.md) and [ADR 0019](adr/0019-smaller-tool-surface.md).

## Play-testing

<!-- tools: player_test -->
| Tool | Arguments | What it does |
| --- | --- | --- |
| `player_test` | `actions` (list of string): goto x,y,z\|forward ticks [sprint] [jump], walk x,y,z (path route), jump, look yaw pitch, lookat x,y,z, wait ticks, use [x,y,z], attack\|interact [type], slot 1-9, say text, /command, tp x,y,z, entities, inventory, hud, close<br>`name`? (string): Default MscTester<br>`gamemode`? (string): survival, adventure, creative. Default: the game's<br>`leave`? (boolean): Disconnect after. Default false | Play-test: a headless player joins the dev server, runs actions, reports results, damage, deaths, chat, titles. Stays joined. Vanilla-like moves; a missed jump may suit people. inventory/hud: items, menu, bossbars, sidebar, pack. |
<!-- /tools -->

A headless client joins the running dev server (offline mode, Minecraft 26.3 or 26.2, picked from the server's own answer), is made op and put in the game mode where the server has those commands, then runs the actions. Being kicked, while joining or during the actions, makes the call fail with the reason. Each action gets a result line; chat, titles, damage, deaths, respawns, server corrections and the pack offer are listed under it. `inventory` lists the held item, hotbar, backpack, armour and any menu the server opened (vanilla names plus custom name, `item_model` and lore); `interact` right-clicks the nearest entity (an NPC or villager), `close` closes the menu. `hud` lists boss bars, the sidebar, the last action bar and title, the tab list header and whether the resource pack loaded (downloaded, SHA-1 matches, `pack.mcmeta` at the root). These report only when asked. Ends with position, health and food. Movement copies vanilla (walk 4.3, sprint 5.6 blocks/s, jump 1.25); see [ADR 0016](adr/0016-test-player.md) for what is not simulated.

## Textures and resource pack

Pixel art is drawn as text; no image model is involved. The pack source lives in `<workspace>/.msc/pack`.

<!-- tools: texture texture_view model_create pack -->
| Tool | Arguments | What it does |
| --- | --- | --- |
| `texture` | `name` (string): Texture name such as item/ruby or myplugin:block/ruby_ore, or a .png path for a file outside the pack<br>`palette`? (list of string): Draw: symbol=#rrggbb or #rrggbbaa entries, for example "r=#d4263a". "." is transparent<br>`rows`? (list of string): Draw: pixel rows, top first, all the same length (16 is standard)<br>`kind`? (string): Generate: pattern noise, ore, planks or bricks, or 16x16 shape gem, ingot, orb, sword, pickaxe or food<br>`copy`? (string): Recolor: the pack texture to copy onto shades of color, keeping its shading<br>`color`? (string): Base color #rrggbb, for kind and copy<br>`accent`? (string): kind's second color: ore's ore, bricks' mortar, a tool's handle, food's bone<br>`size`? (integer): Patterns: 16 (default), 32 or 64<br>`seed`? (integer): Pattern seed. Default 1<br>`contrast`? (number): 0.2 to 1.5. Default 1 for shapes, 0.6 for patterns<br>`animate`? (string): scroll (flows down), "scroll dx,dy" or pulse (glows)<br>`frames`? (integer): Animation frames, 2 to 64. Default: one full loop for scroll, 8 for pulse<br>`frametime`? (integer): Ticks per frame. Default 2<br>`model`? (string): Also write a model and item definition: item (flat), handheld (tools), block (cube) or none. Default block for patterns, handheld for sword and pickaxe, none for minecraft:, else item | Add a pixel-art texture to the project's resource pack, in one of three ways: draw it as text (palette, rows), generate it (kind, color) or recolor a pack texture (copy, color). animate makes it an animation; with only name and animate, animates the texture already there. |
| `texture_view` | `name` (string): Texture name such as item/ruby<br>`maxChars`? (integer): Result size cap. Default 6000 | Show a pack texture as a palette and pixel rows, the format texture draws. Animations show frame 1. |
| `model_create` | `name` (string): Model name such as block/ruby_log<br>`kind` (string): cube_all, cube_column, cube_bottom_top, orientable, cross, item or handheld<br>`textures` (list of string): slot=texture, for example side=block/ruby_log; one bare name fills every slot<br>`item`? (boolean): Also write an item definition. Default true | Write a block or item model from pack textures, and optionally an item definition. |
| `pack` | `check`? (boolean): Only check the pack, do not build it. Default false<br>`limit`? (integer): Most problems to list. Default 30 with check, 5 on a build<br>`description`? (string): Pack description, up to two lines<br>`file`? (string): Also save the zip here, such as ruby-pack.zip<br>`apply`? (boolean): Send to the running server. Default true<br>`required`? (boolean): Kick players who decline. Default false | Zip the project's resource pack, serve it over HTTP, send it to the server's players and list its problems. check=true: only list problems. |
<!-- /tools -->

Results:

- `texture`: writes the PNG, plus a model and item definition so the texture is usable through the `item_model` component, and prints that id. The default namespace is `msc`. Generate also returns a stats line (size, colors, opaque share, main hue) and the palette text, unless it is animated in the same call. Draw warns about odd sizes and large palettes. Recolor maps the source's colors, darkest to lightest, onto a ramp of `color`. Animate replaces the still texture with a frame strip and writes its `.png.mcmeta`, starting from frame 1 if it was already animated; writing a still texture over it later removes the `.mcmeta`. Two ways in one call is an error.
- `texture_view`: about 80 tokens for 16 x 16; a large texture is cut at `maxChars` (6,000 by default) like any result. An animation shows `frame 1 of N` and that frame only.
- `model_create`: writes the model and, unless `item=false`, an item definition named after it.
- `pack`: zips the pack (same content gives the same SHA-1). The agent also sends it to players who join later, and the next `server_start` or `dev_cycle` in the same session serves and sends it again (`resource pack sent again`), because a restarted server has forgotten it. When `server.properties` already offers an MSC pack (after `server_detach`), it is pointed at the new build. Ends with the `msc-textures` `PackLint` summary (files, errors, warnings, notes) and the first findings, such as a model pointing at a missing texture. `check=true` only lints the pack sources; a missing `pack.mcmeta` is not reported before the first build, which writes it.

## Tool log

One JSON line per call, appended to `MSC_TOOL_LOG` (default `<workspace>/.msc/tool-calls.jsonl`). Fields: `v` (1), `ts`, `tool`, `ok`, `durationMs`, `argsChars`, `resultChars`, `resultTokens` (`ceil(resultChars / 4)`), `imageTokens` (only on calls that returned an image: the renderer's estimate), and `error` on failures. `msc-bench` reads this file; the contract is recorded in the bench ADR.

## Configuration (environment variables)

| Variable | Meaning | Default |
| --- | --- | --- |
| `MSC_WORKSPACE` | Project directory | current directory |
| `MSC_SERVER_DIR` | Dev server directory | `<workspace>/run` |
| `MSC_CORE_ADDRESS`, `MSC_TOKEN` | Where agents connect and the shared token. Set both to attach a server that is already running | free port, random token |
| `MSC_TOOL_LOG` | Tool log file | `<workspace>/.msc/tool-calls.jsonl` |
| `MSC_AGENT_JAR` | Agent plugin or mod to install into the server | the jar in `<workspace>/.msc/agent` named for the platform (`...paper...`, `...fabric...`), else one with no platform in its name; then the same search in `agents/` of the core's install folder (`installMsc` ships them there) |
| `MSC_API_JARS` | Jars to index, separated by the path separator | the platform's jars from `~/.gradle` |
| `MSC_MINECRAFT` | Default Minecraft version | 26.3 |
| `MSC_CLIENT_JAR` | A Minecraft client jar whose textures `world_render` uses after the project's pack | none (colour table) |
| `MSC_JAVA` | Java used to run the server | the Java running the core |
| `MSC_DOWNLOADS_URL` | Paper downloads service | `https://fill.papermc.io` |
| `MSC_FABRIC_META_URL`, `MSC_FABRIC_MAVEN_URL` | Fabric's meta service (server launcher) and Maven repository (Fabric API) | `https://meta.fabricmc.net`, `https://maven.fabricmc.net` |
| `MSC_PACK_HOST`, `MSC_PACK_FORMAT` | Resource pack server address and pack format number | `127.0.0.1`, 97 (26.3) |
| `MSC_PACK_PORT` | Resource pack server port | the port this workspace used last (`.msc/pack-port`), else a free one |

