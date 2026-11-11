# ADR 0005: msc-core tool design

Date: 2026-11-10. Status: accepted. Decided by Claude under Jeremiah's instruction to build autonomously.

## Context

`msc-core` is the only thing the AI talks to. Each tool definition and each tool result costs tokens, so the shape of the tools decides whether the toolset meets its goal. The tool list is in [tools.md](../tools.md).

## Decisions

1. **The MCP SDK stays at the edge.** Tools are plain Java (`Tool`, `ToolRegistry`) that take JSON arguments and return text. Only `McpBridge` imports the SDK. Tests call the registry directly; one test speaks raw JSON-RPC over pipes. An SDK upgrade touches one class.
2. **Text results with a budget, not JSON.** Results are compact text, capped at 6,000 characters by default and 24,000 at most. A cut result says how much is missing and what to ask for instead. Errors are one line that says what to do next.
3. **Few, coarse tools.** 24 tools, about 2,500 tokens for the whole list, guarded by a test. `dev_cycle` replaces build, copy, stop, start and read log. Coordinates are strings (`"x1,y1,z1 x2,y2,z2"`) instead of six numeric fields, which keeps schemas and calls short.
4. **One text structure format for seeing and building.** `world_slice` prints what `build_structure` accepts: a legend and one character per block per layer, with `same as` and `all` shortcuts. A model can read, edit and write back without a second format.
5. **Edits are grouped in the core.** The protocol limits one call to 1,048,576 blocks. The core splits larger jobs and hands back one edit id (`e7`) that undoes all the pieces.
6. **API index from class files.** The index is read straight from the Paper API jar in the Gradle cache with a small class-file reader (names, generic signatures, parameter names, deprecation). No class loading, no network, no extra library. Javadoc text is not included; that is acceptable for "what is it called and what does it take".
7. **Logs are filtered, not summarized.** Warnings, errors and stack traces are kept verbatim but trimmed to project frames and deduplicated. Nothing is rewritten, so the AI sees real messages.
8. **Any agent may attach.** The core listens for agents from startup. With `MSC_CORE_ADDRESS` and `MSC_TOKEN` set it uses that port and token, so `msc-bench` can attach a server it started itself. The newest connection wins.
9. **Templates are rendered by `msc-templates`.** The core calls `Templates.scaffold` and never touches template files.
10. **Schematics: Sponge v2, blocks only.** WorldEdit reads it. Vanilla `.nbt` structures, block entities and entities are left for later.
11. **Resource pack.** Sources in `<workspace>/.msc/pack`, zipped deterministically so the SHA-1 changes only with content, served from a loopback HTTP server, applied with `pack.apply`. Textures get a model and an item definition so they work through the `item_model` component.

12. **The core never accepts the Minecraft EULA.** It passes no EULA flag; `eula.txt` in the server directory decides, and `server_setup` says whether it is accepted.

## Open points, to check on a real Paper 26.3 server

These could not be verified in the cloud sandbox (no access to PaperMC or Mojang hosts, no JDK 25):

- The Paper downloads API shape (`/v3/projects/paper/versions/<v>/builds`, `downloads["server:default"]`). Tested only against a local fake.
- `MSC_PACK_FORMAT` default (75) and the `min_format` / `max_format` fields for 26.3.
- The schematic `DataVersion` constant (4671).
- The server start flags (`-Xmx2G -jar paper.jar --nogui`).

## Consequences

- Adding a tool means one `registry.add(...)` call and a row in `tools.md`; the size test keeps the list honest.
- World tools depend on an agent. Without one they answer with a one-line instruction instead of failing obscurely.
