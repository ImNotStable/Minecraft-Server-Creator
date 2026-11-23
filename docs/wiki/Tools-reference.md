# Tools reference

All 32 tools, one line each. You rarely call them by name: ask Claude in plain words and it picks the tool. Full arguments are in [tools.md](https://github.com/ImNotStable/Minecraft-Server-Creator/blob/main/msc-docs/docs/tools.md).

Coordinates are text: a position is `x,y,z`, a region is `x1,y1,z1 x2,y2,z2`, an area is `x1,z1 x2,z2`.

## Status

| Tool | What it does |
| --- | --- |
| `msc_status` | Shows the MSC version, workspace, platform, server state and agent. With `stats=true`: calls, errors, time and tokens per tool this session. |
| `msc_workspace` | Switches MSC to another project folder without restarting Claude Code. With no folder, shows the current one. |

## Project and server

| Tool | What it does |
| --- | --- |
| `project_scaffold` | Creates a bare project from a template (`paper-plugin` with Minecraft internals (NMS) available, `fabric-mod`, `neoforge-mod`, `forge-mod`, `minestom-server`, `velocity-plugin`): Gradle build, dev server task, empty entry point. |
| `server_setup` | Downloads the server and installs the agent and dev settings. `mode=game` switches to survival and normal difficulty for testing games; `mode=build` back to creative and peaceful. |
| `plugins` | Finds plugins and mods on Modrinth, Hangar and SpigotMC (`search=`), installs them by id with their required dependencies for this server's platform and Minecraft version (`install=modrinth:luckperms`), checksum-checked, or lists the installed ones. Every call checks the folder and says which jar will not load and why: built for another platform, a required dependency missing, or one plugin in two jars. It never suggests what to install. |
| `server_start` | Starts the server and reports only startup warnings and errors. |
| `server_stop` | Stops the server. |
| `server_detach` | Keeps the server and the resource pack running after Claude Code closes, so you can play. The next session stops them. |
| `dev_cycle` | Builds, deploys, restarts and reports errors, all in one call. |
| `server_logs` | Shows errors, the last lines, or lines matching a pattern, with stack traces trimmed. |
| `server_command` | Runs a console command and returns its output; several commands, one per line, run in one call. `tick sprint 600` skips 30 game seconds ahead. |
| `server_wait` | Waits until a log or chat line matches a pattern, instead of polling. |
| `server_export` | Ships the dev server: a Docker Compose project (the server keeps itself and its Modrinth and SpigotMC plugins up to date, and the world is backed up every 6 hours) or a Pterodactyl egg with the server's files. Your world goes too if you ask. |
| `server_profile` | Profiles the running server for a few seconds while it is busy: where the time goes (your code, each plugin or mod, chunk generation, entities...), the hottest methods with their source lines, garbage collection and memory, and which settings to change. |

## API lookup

| Tool | What it does |
| --- | --- |
| `api_search` | Finds classes, methods and events in the platform's API by name. |
| `api_class` | Lists one class's members and signatures, with constants it inherits. |

## Seeing the world

| Tool | What it does |
| --- | --- |
| `world_summary` | Counts blocks and entities in a region, in about six lines, with the positions of kinds that have three blocks or fewer. With `path`, says whether a player can walk and jump from one point to another and names the first wall or gap; with `diff`, lists what changed since a `structure_save` snapshot. |
| `world_slice` | Shows a region as text, one character per block, layer by layer. |
| `world_entities` | Lists the entities in a region with name, position, health and tags. |
| `world_heightmap` | Shows the ground height of an area, one character per column. |
| `world_render` | Draws a region as a PNG picture from any angle. |
| `world_tour` | Walks a build at eye height: views of its doors, rooms and flagged spots, with its review. |

## Building

| Tool | What it does |
| --- | --- |
| `build_batch` | The one build tool. Runs build steps as one edit: fills and rooms, single blocks, circles, rings, spheres, domes, lines, helixes (spiral stairs), terrain bands, noise terrain (hills, ridges, mesas, dunes, lakes), winding paths and streams on the ground, things scattered over the ground (trees, rocks, flowers), structures from text or `.schem` files (repeated or turned), and copies, then the detail a builder adds: roofs (gable, hip, shed, with overhangs), trim (cornices, plinths, belt courses, corner pillars, sills, lintels, shutters, window frames), decorations (props and furniture along walls, hanging lanterns, vines, plants around the base) and weathering (moss, cracks, verdigris, broken edges); any step repeats with `repeat=N step=DX,DY,DZ`. Blocks join as a player's would (fences, panes and walls connect, stair corners turn, doors get their top half). Takes block mixes such as `grass_block:78,moss_block:10` and gradients such as `cobblestone>stone_bricks>andesite`, checks every block name before placing any, and has a `dryRun` option. |
| `build_undo` | Undoes an edit (the last one by default). |
| `world_create` | Adds a generated world beside the flat dev world: the game's own terrain (normal, amplified, large biomes, caves, floating islands, nether, end, flat or void) with one biome or several sharing the land. It loads on the next server start; then build there with `world=msc:<name>`. |
| `structure_save` | Saves a region as a `.schem` file. Place it again with `build_batch` step `structure X,Y,Z file=PATH`. |

## Play-testing

| Tool | What it does |
| --- | --- |
| `player_test` | A test player joins your dev server and plays: walks to points, follows the `world_summary path` route with `walk x,y,z` (saying where it falls off or gets stuck), sprints and jumps gaps, presses buttons, attacks, right-clicks NPCs by name, clicks menu slots and chat buttons, runs commands. It reports what happened, such as damage, deaths, chat, titles and whether the resource pack downloaded. Ask it for `inventory` to see what it holds and any menu the server opened, or `hud` for boss bars, the sidebar, the action bar, whether the resource pack loaded and what is wrong in it, and any sounds or font glyphs the pack is missing. It speaks Minecraft 26.3 and 26.2, so it also joins Minestom servers, which are one version behind. If the server kicks it, the call fails and says why. Movement follows vanilla closely, so a jump it misses is usually hard for people too, but not always. |

## Textures and resource pack

| Tool | What it does |
| --- | --- |
| `texture` | Adds a texture: draws it from a palette and rows of characters, generates it from a pattern (noise, ore, planks, bricks) or shape (gem, ingot, orb, sword, pickaxe, food), or copies one in a new colour. Can also animate it (scroll or pulse). Give a `.png` path as the name to write a plain image file outside the pack. |
| `texture_view` | Shows a texture back as text. |
| `model_create` | Makes a block or item model for textures. |
| `pack_asset` | Writes every other pack file, checked first: fonts and HUD glyphs, sounds, lang, models with elements, item definitions, blockstates, equipment, atlases, animation and GUI sprite metadata, shaders, post effects, particles, waypoint styles, texts and copied files. `msc_status guide=pack` has each format. |
| `pack` | Zips the pack, serves it, sends it to players and lists any problems. `check` only checks it. `file` also saves the zip to a path, such as `ruby-pack.zip`; the result names the pack_format it wrote. |
