# MCP tool catalogue (msc-core 0.1)

The AI talks to `msc-core` over MCP (stdio). This page lists every tool, its arguments and the size budget of its result. Source of truth is the code in `msc-core` (`io.github.imnotstable.msc.core.tools`); design reasons are in [ADR 0005](adr/0005-core-tools.md).

## Rules every tool follows

- Results are plain text, no Markdown and no JSON, shaped to be read by a model.
- Every result has a size budget. One token is counted as four characters. The default cap is 6,000 characters (about 1,500 tokens); tools with a `maxChars` argument accept 200 to 24,000. A cut result ends with `[truncated: N more lines, M chars. <what to do>]`.
- Errors come back as `error: <what is wrong and what to do>` with MCP `isError` set. They never contain a Java stack trace.
- Coordinates are strings: a position is `"x,y,z"`, a region is `"x1,y1,z1 x2,y2,z2"` (two corners, inclusive, any order), an area is `"x1,z1 x2,z2"`.
- Block names may omit `minecraft:`. Properties use vanilla syntax: `oak_stairs[facing=north]`.
- `world` is optional everywhere and defaults to the server's first world.
- Every call is logged (see "Tool log").
- World and build tools fail at once with `error: the server is not running...` when the core has no server running and none was attached with `MSC_CORE_ADDRESS`.
- The whole tool list (30 tools) costs about 3,900 tokens per conversation. A test fails if it grows past 16,000 characters ([ADR 0019](adr/0019-smaller-tool-surface.md)).
- Results are text. The one exception is `world_render`, which adds one PNG image next to its text.

## Typical loop

`project_scaffold` once, `server_setup` once, then edit code and call `dev_cycle` until it reports `no errors or warnings`. Look names up with `api_search` instead of guessing. Look at the world with `world_summary`, then `world_slice`; build with `build_batch`, a whole build per call.

## Status

| Tool | Arguments | Result | Budget |
| --- | --- | --- | --- |
| `msc_status` | `stats`? (false) | Core version, workspace, platform, server state, agent, API index, then any setup warnings (workspace is the MSC repo; install older than its checkout). The same warnings go in front of the session's first tool result | 6 lines (+ warnings) |
| `msc_workspace` | `path`? (absolute, or relative to the current workspace), `create`? (false) | Switches every tool to another folder without restarting the MCP server: closes the old workspace like a session end (its dev server stops unless detached) and opens the new one. The server folder and tool log move along when they were the defaults. No `path`: shows the current workspace | 3 lines |

## Project and server lifecycle

| Tool | Arguments | Result | Budget |
| --- | --- | --- | --- |
| `project_scaffold` | `name`, `dir`?, `template`? (`paper-plugin` default, `paper-game`, `fabric-mod`, `minestom-server`), `package`? (`com.example.<name>`), `acceptEula`? (false) | Where the project was created, its `projectId`, `packageName` and `version`, and the next step (EULA and `server_setup` first when the Paper or Fabric server is not set up). Remembers the template's platform for the workspace | 5 lines |
| `server_setup` | `version`? (default 26.3), `platform`? (`paper`, `fabric`, `minestom`; default the project's), `mode`? (`build` or `game`), `acceptEula`? (false) | `mode=game` sets `gamemode=survival`, `difficulty=normal` and `force-gamemode=true` in `server.properties` (`mode=build`: creative, peaceful); without `mode` a new server gets build defaults and an existing one keeps its settings. Paper: build used. Fabric: launcher and Fabric API versions. Both: whether dev defaults and the agent were installed and whether `eula.txt` says `eula=true`. Minestom: one line saying nothing is needed | 5 lines |
| `server_start` | `timeoutSeconds`? (180) | `server ready in 8.2s, agent connected`, then `world world: ground is y=-61 (most common top block around 0,0; build from y=-60)` (dev worlds are superflat, far below y=64), then `mode: creative, peaceful difficulty (server_setup mode=game for survival, normal: ...)` from `server.properties`, plus startup warnings and errors only. Fails at once with a one-line error if `eula.txt` lacks `eula=true`. Replaces an installed agent jar that differs from the one in `.msc/agent` (or `MSC_AGENT_JAR`) first, and says `agent jar updated` | 6,000 chars |
| `server_detach` | none | One line: the running server, and the pack host if a pack was built, keep running after this core exits (the pack host moves to a standalone process on the same port, pid in `.msc/pack-host.pid`). When a pack was built, also writes `resource-pack`, `resource-pack-sha1`, `resource-pack-id` and `require-resource-pack` to `server.properties`, so a restart outside MSC still sends the pack. The next `server_start`, `dev_cycle` or `server_stop` stops both, the server over RCON; the next start serves the pack again on the same port and updates `server.properties` (or clears it if the pack is gone) | 3 lines |
| `server_stop` | none | One line. Also stops a server an earlier core left running (pid and start time in `.msc/server.pid`), as `server_start` and `dev_cycle` do first: over RCON so it saves, killed only if that fails | 1 line |
| `dev_cycle` | `project`? (workspace), `restart`? (true), `maxChars`? | Build, deploy, restart, report in one call. On a failed build: compiler errors and each failed test with its project frames. Otherwise: startup warnings and errors, or `no errors or warnings` | `maxChars` |
| `server_logs` | `mode`? (`errors`, `tail`, `grep`), `pattern`?, `lines`? (30), `all`? (false), `maxChars`? | `errors`: warnings and errors, stack traces cut to project frames, repeats collapsed to `(xN)` | `maxChars` |
| `server_command` | `command` | The command's output; a command with bad syntax fails with the parser's message (`command failed`, then where it stopped). Lines the server console printed while the command ran (broadcasts such as `/say`, which never reach the sender) follow under `console during the command:` | 6,000 chars |
| `server_wait` | `until` (regex, case-insensitive), `timeoutSeconds`? (60, at most 600) | `matched after 4.1s: <line>` for the first matching log or chat line, or `no line matched in 60s` (or `the server stopped after ...`) with the last 5 lines. Looks at lines since the last `server_command`, `server_wait` match or start, so a line printed between two calls is not missed and one line never matches twice. Not an error on timeout. Only for a server this core started | 1 line, or 6 on timeout |

How logs are condensed: the dev server's fixed banners (running as root or administrator, offline mode) and the Windows system-report noise (`[oshi.*]` messages, `Failed to get system info for ...`) are dropped. Otherwise only WARN and ERROR entries and anything with a stack trace are kept. In a trace, the exception lines, the first frame and all frames outside framework packages (`java.`, `net.minecraft.`, `org.bukkit.`, `io.papermc.` and similar) are kept; runs of framework frames become `... N framework frames`. Identical entries are shown once with a count. By default `server_logs` looks only at lines since the last start.

### Platforms

The workspace has one platform: `paper` (default), `fabric` or `minestom`. `project_scaffold` remembers the template's platform in `<workspace>/.msc/platform`, `server_setup platform=...` sets it, and without either the core looks at the project files (`fabric.mod.json`, a Minestom dependency in the build files).

| Platform | `server_setup` installs | Server starts as | `dev_cycle` deploys to | EULA step |
|---|---|---|---|---|
| `paper` | `paper.jar`, agent in `plugins/` | `java -Xmx2G -jar paper.jar --nogui` | `plugins/<jar name without version>.jar` | yes |
| `fabric` | `fabric-server-launch.jar` (fetches the Minecraft server on first start), `mods/fabric-api.jar`, agent in `mods/` | `java -Xmx2G -jar fabric-server-launch.jar nogui` | `mods/<jar name without version>.jar` | yes |
| `minestom` | nothing (the agent is built into the project) | `java -Xmx1G -jar server.jar` | `server.jar` in the server directory | no |

EULA step: when the owner has accepted the Minecraft EULA once for the machine (`Main eula accept` in their own terminal, `~/.msc/eula.json`, [ADR 0020](adr/0020-machine-eula.md)), `server_setup` and `server_start` write `eula=true` into the server's `eula.txt` and say so; no tool argument creates that file. Otherwise `acceptEula=true` after the owner's own yes, as before.

## API index

Built on first use from the platform's jars in the Gradle cache (or `MSC_API_JARS`), by reading class files directly: Paper API and Adventure for Paper, the Minecraft server jar from Loom's cache plus Fabric API and loader for Fabric, Minestom and Adventure for Minestom. No network, no Javadoc. Parameter names appear only when the jar records them; the published paper-api jar does not, so signatures show types only.

| Tool | Arguments | Result | Budget |
| --- | --- | --- | --- |
| `api_search` | `query`, `kind`? (`class`, `interface`, `enum`, `record`, `annotation`, `event`), `limit`? (15) | One line per match: `interface org.bukkit.entity.Player` or `Player#void sendMessage(String)` | `limit` lines |
| `api_class` | `name`, `filter`?, `inherited`? (false), `limit`? (40) | Header with supertypes, enum constants, then one member signature per line | `limit` lines |

## Seeing the world

| Tool | Arguments | Result | Budget |
| --- | --- | --- | --- |
| `world_summary` | `region` (up to 8,388,608 blocks) | Size, air share, top 15 block counts, bounds of non-air blocks, non-air count per layer, entity counts | about 6 lines |
| `world_slice` | `region` (up to 128 wide, 1,048,576 blocks), `states`? (false), `maxChars`? | The region in the text structure format | `maxChars` |
| `world_entities` | `region` (up to 2,048 x 2,048 wide, any height), `type`? (`zombie`), `limit`? (50), `ids`? (false) | A count line with totals per type, then one line per entity sorted by type and position: `zombie "Brute" 2.5,-60,3 hp 7/20 tags boss,wave`. The name is a player name, custom name or (Paper, Minestom) a text display's text; `ids=true` adds the UUID for commands. Loaded chunks only: with no player online, run `forceload add x1 z1 x2 z2` first (an empty result says so, with the region's corners). Minestom has no scoreboard tags | 6,000 chars |
| `world_heightmap` | `area` (up to 256 x 256), `maxChars`? | Min, max, mean, then one character per column (`height - min`, 0-9 then a-z); when the range is over 35, numbers per column with runs as `v*n` and repeated rows as `(xN)` | `maxChars` |

| `world_render` | `region` (up to 16,000,000 blocks; read in protocol-sized pieces and stitched), `view`? (`iso`; also `top`, `north`, `south`, `east`, `west`, `iso-ne`/`nw`/`sw`/`se`, `eye x,y,z look x,y,z`, `eye x,y,z yaw N pitch N`), `size`? (512, 64 to 1568), `entities`? (true) | A PNG as MCP image content, drawn by `msc-renderer` with the pack's textures, plus a caption: view, pixels per block, image size and token estimate, visible blocks, numbered markers for entities coloured by kind (player, hostile, creature, object), hologram text drawn in the image (text displays, named armor stands), markers behind blocks drawn hollow, with positions; isometric views label their corners with block coordinates, and the path of the copy saved under `<workspace>/.msc/renders/` | image about 330 tokens at 512 px, caption 2 to 3 lines |

A 16 x 16 slice costs about 70 tokens per layer; identical and uniform layers cost one line each.

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

`build_batch` answers `edit eN: <count> blocks changed, region <box>`, plus a `blocks:` line when it placed more than one kind. The id undoes the whole edit, even when the core split it into several agent calls.

| Tool | Arguments | Notes |
| --- | --- | --- |
| `build_undo` | `edit`? (default: most recent) | Edits are remembered for the life of the core process |
| `build_batch` | `steps` (one per line), `dryRun`? (false), `physics`? (false) | The one build tool: `fill` (solid, `hollow`, `outline`, `walls`), `set`, `cylinder`, `ring`, `disc`, `sphere`, `dome`, `line`, `bands`, stamped `structure`s (inline text or `file=` `.schem`/text), `copy` (reads the world before the call, so source and target may overlap); weighted mixes such as `grass_block:78,moss_block:10`; checks every name first; one undo id; up to 8,388,608 blocks. See [ADR 0015](adr/0015-build-batch.md) and [ADR 0019](adr/0019-smaller-tool-surface.md) |
| `structure_save` | `region`, `file` | Sponge schematic v2 (`.schem`), blocks only |

## Play-testing

| Tool | Arguments | Notes |
| --- | --- | --- |
| `player_test` | `actions` (list, one per entry: `goto x,y,z [sprint] [jump]`, `forward ticks [sprint] [jump]`, `jump`, `look yaw pitch`, `lookat x,y,z`, `wait ticks`, `use`, `use x,y,z`, `attack`, `slot 1-9`, `say text`, `/command`, `tp x,y,z`, `entities`), `name`? (MscTester), `gamemode`? (survival), `leave`? (false) | A headless client joins the running dev server (offline mode), is made op and put in the game mode, then runs the actions. Each action gets a result line; chat, titles, damage, deaths, respawns, server corrections and the pack offer are listed under it. Ends with position, health and food. Stays joined between calls. Movement copies vanilla (walk 4.3, sprint 5.6 blocks/s, jump 1.25); see [ADR 0016](adr/0016-test-player.md) for what is not simulated |

## Textures and resource pack

Pixel art is drawn as text; no image model is involved. The pack source lives in `<workspace>/.msc/pack`.

| Tool | Arguments | Result |
| --- | --- | --- |
| `texture_create` | `name` (`item/ruby` or `ns:block/ruby_ore`; default namespace `msc`), `palette` (list of `symbol=#rrggbb` or `#rrggbbaa`), `rows` (list of strings), `model`? (`item`, `handheld` for tools and swords, `block`, `none`) | Writes the PNG, plus a model and item definition so the texture is usable through the `item_model` component. Warns about odd sizes and large palettes |
| `texture_generate` | `name`, `kind` (`noise`, `ore`, `planks`, `bricks`, or 16 x 16 shapes `gem`, `ingot`, `orb`), `color`, `accent`? (ore or mortar color), `size`? (16, 32, 64), `seed`? (1), `contrast`? (0.2 to 1.5; 1 for shapes, 0.6 for patterns), `model`? (item for shapes, block for patterns) | Shades the color into a 5-step ramp, draws the pattern or shape with `msc-textures`, writes it like `texture_create`. Returns a stats line (size, colors, opaque share, main hue) and the palette text |
| `texture_recolor` | `from`, `name`, `color`, `model`? (item) | Maps the source's colors, darkest to lightest, onto a ramp of `color`, keeping the shading |
| `texture_animate` | `name`, `mode` (`scroll` or `pulse`), `frames`, `frametime` (ticks), `dx`? (0), `dy`? (1) | Replaces the still texture with a frame strip and writes its `.png.mcmeta`. `pulse` waves the texture's own shades. Writing a still texture over it later removes the `.mcmeta` |
| `model_create` | `name` (model, such as `block/ruby_log`), `kind` (`cube_all`, `cube_column`, `cube_bottom_top`, `orientable`, `cross`, `item`, `handheld`), `textures` (list of `slot=texture`; one bare name fills every slot), `item`? (true) | Writes the model and, unless `item=false`, an item definition named after it |
| `texture_view` | `name` | The texture as a palette line and pixel rows (about 80 tokens for 16 x 16) |
| `pack_check` | `limit`? (30) | `msc-textures` `PackLint` on the pack sources: a summary line (files, errors, warnings, notes), then findings such as a model pointing at a missing texture. A missing `pack.mcmeta` is not reported before the first `pack_build`, which writes it |
| `pack_build` | `description`?, `apply`? (true), `required`? (false) | Zips the pack (same content gives the same SHA-1), serves it over HTTP and sends it through the agent. The agent also sends it to players who join later, and the next `server_start` or `dev_cycle` in the same session serves and sends it again (`resource pack sent again`), because a restarted server has forgotten it. When `server.properties` already offers an MSC pack (after `server_detach`), it is pointed at the new build. Ends with the `pack_check` summary line |

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

## Not in 0.1

Vanilla structure `.nbt` files, block entities and entities in schematics, rendered views, image-model textures, RCON, and Fabric or Minestom agents.
