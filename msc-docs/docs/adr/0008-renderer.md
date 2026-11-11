# ADR 0008: msc-renderer design

Date: 2026-11-11. Status: accepted. Decided by Claude under Jeremiah's instruction to build autonomously.

## Context

The AI can already read a region as text (`world_slice`, `world_heightmap`, `world_summary`). Text is exact but says little about how a build looks: proportions, silhouettes, whether a roof overhangs, whether a window lines up. `msc-renderer` adds pictures. They have to be cheap in tokens, work on any machine the core runs on, and never need game assets we may not ship.

## Decisions

1. **Software ray caster, not OpenGL.** Each pixel walks the block grid (voxel traversal) and tests the boxes of the blocks it passes. Offscreen OpenGL needs a GPU or Mesa, native libraries per OS and a display context, and fails in exactly the places this runs (headless Windows services, CI, cloud containers). A rasteriser would be faster but needs meshing, clipping and a depth buffer. Ray casting gives all three camera types, exact occlusion, transparency and shadows from one loop of about 300 lines. Measured in the cloud container on 4 cores: a 13x8x13 hut and a 256x64x256 terrain each render at 512 px in well under half a second.
2. **A library with no dependencies beyond `msc-protocol`.** Input is a `BlockGrid`, output is PNG bytes plus a caption. `msc-core` depends on the renderer and owns the tool, the world reads and the files, matching the plan's architecture. PNG encoding uses `javax.imageio`; ruler labels use a built-in 3x5 pixel font because AWT fonts can be missing on headless servers.
3. **Views.** `top`, four elevations, isometric from four corners (30 degrees down, orthographic) and a first-person camera with Minecraft yaw and pitch. A side name is where the camera stands. One text form (`View.parse`) so the core tool takes a single string argument.
4. **Token budget.** Vision cost is about width x height / 750 tokens. Default longest side is 512 px (at most about 350 tokens), allowed range 64 to 1568 (larger images are downscaled by the model anyway). The result carries its estimated token cost so the core can log it. Top and side views use whole pixels per block from 4 px up, capped at 32 px per block, so a small build does not fill the budget for nothing.
5. **Drawn for a model, not for beauty.** Thin dark lines on block edges make blocks countable. Sun shadows from the south-east and fixed per-face light show height and overhangs. Top and side views get rulers with world coordinates. Every image comes with a caption: the pixel-to-coordinate mapping and the share of each visible block. Unknown blocks are named in the caption instead of silently drawn wrong.
6. **Own colour table first, textures when available.** The module ships `block-colors.txt`, written for this project, plus naming rules (oak_slab looks like oak_planks; 16 dye colours; word fallbacks; a stable made-up colour for unknown names). No Mojang asset is bundled. `PackTextures` reads `assets/<ns>/textures/block/*.png` from folders and zip or jar files, so the project's generated pack works at once and a vanilla client jar works if the user has one. Textures are matched by name with `_top`, `_side`, `_bottom` variants, with fixed grass, foliage and water tints.
7. **Approximate shapes, no model JSON.** About twenty shape rules cover slabs, stairs, fences, walls, panes, doors, trapdoors, thin and small blocks. Parsing blockstate and model JSON would be exact but is several times the code and needs the client jar. It is listed in PLAN.md for later.
8. **Structured views stay in the core.** Rooms, walls, paths and snapshot diffs (post-MVP item 4) work on block data, not pixels, and belong with the text views in `msc-core`. This module only renders.

## Requests to other owners

**msc-core** (tool owner): add `world_render`.

- `msc-core/build.gradle.kts`: `implementation(project(":msc-renderer"))`.
- Arguments: `region` (required, same form as `world_slice`), `view` (string for `View.parse`, default `iso`), `size` (integer, default 512), `world`. Errors from `View.parse` and `RenderOptions` are already one line that says what to use.
- Read the region with `world.read`, stitching calls above 1,048,576 blocks into one `BlockGrid` (the constructor has no size limit). Suggested cap: 16 million blocks per render.
- Keep one `WorldRenderer` per core. Texture roots: `<workspace>/.msc/pack` first, then an optional client jar from an environment variable such as `MSC_CLIENT_JAR`.
- Return the PNG as MCP image content with `RenderResult.caption()` as text. This needs `McpBridge` to carry image content next to text. If that is too large a change, write the PNG to `<workspace>/.msc/renders/` and return the path with the caption.
- Log `RenderResult.estimatedTokens()` in the tool log so `msc-bench` counts image cost.

**Foundation**: none required. The module needs no catalog entry and is already in `settings.gradle.kts`. A row for this ADR in the plan's Decisions table would be welcome.

## Not verified

- No run against a real Paper server yet: the module was tested on built grids only. The data path is the same `BlockGrid` that `world.read` returns.
- Vanilla textures were not tried (no client jar in the cloud container). Texture loading is tested with generated PNGs in a folder and in a zip.
- ponytail and graphify were not reachable from the cloud container this was built in.

## Consequences

- Renders work with zero setup and no assets, and improve when a pack is present.
- Images are approximations. For exact block data the AI should still use `world_slice`; the caption's block list helps it decide when to.
- A new odd-shaped block needs one line in `Shapes`; a new colour needs one line in `block-colors.txt`.
