# Tools reference

All 34 tools, one line each. You rarely call them by name: ask Claude in plain words and it picks the tool. Full arguments are in [tools.md](https://github.com/ImNotStable/Minecraft-Server-Creator/blob/main/msc-docs/docs/tools.md).

Coordinates are text: a position is `x,y,z`, a region is `x1,y1,z1 x2,y2,z2`, an area is `x1,z1 x2,z2`.

## Status

| Tool | What it does |
| --- | --- |
| `msc_status` | Shows the MSC version, workspace, platform, server state and agent. |
| `msc_stats` | Shows calls, errors, time and tokens per tool this session. |

## Project and server

| Tool | What it does |
| --- | --- |
| `project_scaffold` | Creates a new project from a template (`paper-plugin`, `paper-game` for minigames, `fabric-mod`, `minestom-server`). |
| `server_setup` | Downloads the server and installs the agent and dev settings. `mode=game` switches to survival and normal difficulty for testing games; `mode=build` back to creative and peaceful. |
| `server_start` | Starts the server and reports only startup warnings and errors. |
| `server_stop` | Stops the server. |
| `server_detach` | Keeps the server and the resource pack running after Claude Code closes, so you can play. The next session stops them. |
| `dev_cycle` | Builds, deploys, restarts and reports errors, all in one call. |
| `server_logs` | Shows errors, the last lines, or lines matching a pattern, with stack traces trimmed. |
| `server_command` | Runs a console command and returns its output. `tick sprint 600` skips 30 game seconds ahead. |
| `server_wait` | Waits until a log or chat line matches a pattern, instead of polling. |

## API lookup

| Tool | What it does |
| --- | --- |
| `api_search` | Finds classes, methods and events in the platform's API by name. |
| `api_class` | Lists one class's members and signatures. |

## Seeing the world

| Tool | What it does |
| --- | --- |
| `world_summary` | Counts blocks and entities in a region, in about six lines. |
| `world_slice` | Shows a region as text, one character per block, layer by layer. |
| `world_entities` | Lists the entities in a region with name, position, health and tags. |
| `world_heightmap` | Shows the ground height of an area, one character per column. |
| `world_render` | Draws a region as a PNG picture from any angle. |

## Building

| Tool | What it does |
| --- | --- |
| `build_fill` | Fills a region with one block, optionally only replacing some blocks. `shape` makes a hollow room, an outline or just walls. |
| `build_set` | Sets a list of single blocks, such as doors and torches. |
| `build_structure` | Builds a structure written in the text format that `world_slice` shows. |
| `build_copy` | Copies a region to another place. |
| `build_undo` | Undoes an edit (the last one by default). |
| `build_batch` | Runs many build steps as one edit: fills and rooms, circles, rings, spheres, domes, lines, terrain bands, and structures repeated or turned. Takes block mixes such as `grass_block:78,moss_block:10`, checks every block name before placing any, and has a `dryRun` option. |
| `structure_save` | Saves a region as a `.schem` file. |
| `structure_load` | Pastes a `.schem` file into the world. |

## Play-testing

| Tool | What it does |
| --- | --- |
| `player_test` | A test player joins your dev server and plays: walks to points, sprints and jumps gaps, presses buttons, attacks, runs commands. It reports what happened, such as damage, deaths, chat, titles and whether the resource pack downloaded. It speaks Minecraft 26.3 and 26.2, so it also joins Minestom servers, which are one version behind. If the server kicks it, the call fails and says why. Movement follows vanilla closely, so a jump it misses is usually hard for people too, but not always. |

## Textures and resource pack

| Tool | What it does |
| --- | --- |
| `texture_create` | Draws a texture from a palette and rows of characters. |
| `texture_generate` | Makes a texture from a pattern (noise, ore, planks, bricks) or shape (gem, ingot, orb). |
| `texture_recolor` | Copies a texture in a new colour, keeping its shading. |
| `texture_animate` | Turns a texture into a scrolling or pulsing animation. |
| `model_create` | Makes a block or item model for textures. |
| `texture_view` | Shows a texture back as text. |
| `pack_check` | Checks the resource pack for broken links and bad sizes. |
| `pack_build` | Zips the pack, serves it and sends it to players. |
