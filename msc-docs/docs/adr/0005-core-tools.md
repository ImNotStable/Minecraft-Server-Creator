# ADR 0005: msc-core tool design

Date: 2026-11-10. Status: accepted. Decided by Claude under Jeremiah's instruction to build autonomously.

## Context

`msc-core` is the only thing the AI talks to. Each tool definition and each tool result costs tokens, so the shape of the tools decides whether the toolset meets its goal. The tool list is in [tools.md](../tools.md).

## Decisions

1. **The MCP SDK stays at the edge.** Tools are plain Java (`Tool`, `ToolRegistry`) that take JSON arguments and return text. Only `McpBridge` imports the SDK. Tests call the registry directly; one test speaks raw JSON-RPC over pipes. An SDK upgrade touches one class.
2. **Text results with a budget, not JSON.** Results are compact text, capped at 6,000 characters by default and 24,000 at most. A cut result says how much is missing and what to ask for instead. Errors are one line that says what to do next.
3. **Few, coarse tools.** 24 tools at first (30 since the addenda below), about 2,500 tokens for the whole list, guarded by a test. `dev_cycle` replaces build, copy, stop, start and read log. Coordinates are strings (`"x1,y1,z1 x2,y2,z2"`) instead of six numeric fields, which keeps schemas and calls short.
4. **One text structure format for seeing and building.** `world_slice` prints what `build_structure` accepts: a legend and one character per block per layer, with `same as` and `all` shortcuts. A model can read, edit and write back without a second format.
5. **Edits are grouped in the core.** The protocol limits one call to 1,048,576 blocks. The core splits larger jobs and hands back one edit id (`e7`) that undoes all the pieces.
6. **API index from class files.** The index is read straight from the Paper API jar in the Gradle cache with a small class-file reader (names, generic signatures, parameter names when the jar has them, deprecation). No class loading, no network, no extra library. Javadoc text is not included; that is acceptable for "what is it called and what does it take".
7. **Logs are filtered, not summarized.** Warnings, errors and stack traces are kept verbatim but trimmed to project frames and deduplicated. Nothing is rewritten, so the AI sees real messages.
8. **Any agent may attach.** The core listens for agents from startup. With `MSC_CORE_ADDRESS` and `MSC_TOKEN` set it uses that port and token, so `msc-bench` can attach a server it started itself. The newest connection wins.
9. **Templates are rendered by `msc-templates`.** The core calls `Templates.scaffold` and never touches template files.
10. **Schematics: Sponge v2, blocks only.** WorldEdit reads it. Vanilla `.nbt` structures, block entities and entities are left for later.
11. **Resource pack.** Sources in `<workspace>/.msc/pack`, zipped deterministically so the SHA-1 changes only with content, served from a loopback HTTP server, applied with `pack.apply`. Textures get a model and an item definition so they work through the `item_model` component.

12. **The core never accepts the Minecraft EULA on its own.** It passes no EULA flag; `eula.txt` in the server directory decides. `project_scaffold` and `server_setup` take an optional `acceptEula` argument (default false) that the AI sets only when the user has agreed to https://aka.ms/MinecraftEULA: it renders `eula=true`, and `server_setup` also rewrites an existing `eula=false` line. `server_setup` reports the state, and `server_start` (and the restart in `dev_cycle`) refuses with a one-line error naming the EULA and `acceptEula` instead of launching a server that would exit. The check is skipped when a custom server command is configured.

## Open points, checked on a real Paper 26.3 server (2026-11-11)

Run end to end on Windows 11 against Paper 26.3 build 248 with `msc-agent-paper`, through MCP calls only:

- Paper downloads API: confirmed as coded. `GET /v3/projects/paper/versions/26.3/builds` returns an array of builds with `id`, `channel` (`STABLE`) and `downloads["server:default"]` with `url` and `checksums.sha256`; the jar is served from `fill-data.papermc.io` and the User-Agent is accepted.
- Resource pack format: 26.3 is format 97.1 (`pack_version.resource_major` 97, `resource_minor` 1 in the server's `version.json`). The default is now 97; `min_format` 97 and `max_format` 9999 are written. `pack.apply` answers; whether a client accepts the pack still needs a player to join.
- Schematic `DataVersion`: 26.3's `world_version` is 5023; the constant is now 5023.
- Start flags: `-Xmx2G -jar paper.jar --nogui` work; readiness ("Done" line or agent hello) is detected in about 7 s on a warm start, and the agent connects.

## Addendum 2026-11-11: texture tools on msc-textures

Five tools from [ADR 0007](0007-textures.md) are added, making 29: `texture_generate`, `texture_recolor`, `texture_animate`, `model_create` and `pack_check`; `pack_build` now ends with the `pack_check` summary line. They are thin wrappers over `msc-textures` (`implementation(project(":msc-textures"))`), and its `TextureException` becomes the usual one-line tool error. They write to the same `<workspace>/.msc/pack` paths and PNG format as `texture_create`, so `texture_view` and `pack_build` work on their output. `PaletteTexture` and `ResourcePack` stay as they were (only a `root()` accessor was added), so existing pack hashes do not change; a still texture written over an animated one now drops its `.png.mcmeta`. The tool list grew from 10,484 to 13,431 characters, and the size test limit from 10,500 to 13,500.

## Addendum 2026-11-11: world_render, the first image result

`world_render` ([ADR 0008](0008-renderer.md)) makes 30 tools. It reads the region with `world.read`, stitching protocol-sized reads into one `BlockGrid` up to 16,000,000 blocks, and draws it with one `WorldRenderer` per core over `PackTextures(<workspace>/.msc/pack, MSC_CLIENT_JAR?)`. Results stay text-first: `Tool.Output` carries an optional PNG, only tools registered with `ToolRegistry.addImage` produce one, and `McpBridge` adds it as MCP image content before the text. Every other tool is unchanged. The PNG is also saved under `<workspace>/.msc/renders/` (the caption names the file) for clients that drop images, and the tool log gains an optional `imageTokens` field so `msc-bench` can count image cost. The tool list grew to 14,025 characters; the size limit is 14,100.

## Consequences

- Adding a tool means one `registry.add(...)` call and a row in `tools.md`; the size test keeps the list honest.
- World tools depend on an agent. Without one they answer with a one-line instruction instead of failing obscurely.
