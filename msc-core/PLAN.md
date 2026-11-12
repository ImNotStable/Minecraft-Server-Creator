# msc-core plan

Phase: MVP. Steps are in order; each ends with passing tests.

- [x] Gradle KTS app skeleton with an MCP server and one health tool.
- [x] Lifecycle tools and condensed logs. Tested against fake server and build processes.
- [x] API index for Paper.
- [x] World build tools and the text structure format. Tested against a fake agent.
- [x] World view tools with size budgets.
- [x] Palette-grid textures and resource pack serving.
- [x] Token and timing logging feeding `msc-bench`.
- [x] Texture tools on `msc-textures`: `texture_generate`, `texture_recolor`, `texture_animate`, `model_create`, `pack_check` (2026-11-11); folded into `texture`, `texture_view`, `model_create` and `pack` by ADR 0023 (2026-11-12).
- [x] `world_render` on `msc-renderer`: PNG image content, stitched reads up to 16M blocks, image tokens in the tool log (2026-11-11).
- [x] Platform-aware lifecycle tools: Fabric (launcher, Fabric API, agent in `mods/`) and Minestom (project jar is the server), ADR 0011 (2026-11-11). Minestom run end to end.

## Next

- [ ] Start a Fabric server and run `dev_cycle` on a Fabric mod on the PC (needs the accepted `eula.txt`).
- [ ] Re-run the stitched `world_render` on Paper with the msc-protocol connection fix.
- [ ] Vanilla `.nbt` structures; block entities in schematics.
- [ ] Shrink tool descriptions further once the benchmark shows which ones the AI misuses.
