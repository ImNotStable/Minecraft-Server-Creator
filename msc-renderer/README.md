# msc-renderer

A headless renderer that turns a region of blocks into a small PNG so the AI can check how a build looks.

**Phase:** Post-MVP  
**Status:** Library done and tested. Not yet exposed as an `msc-core` tool (see PLAN.md).

## What it does

- Views: `top` (map, north up), `north` / `south` / `east` / `west` (elevations), `iso` (three-quarter view from any corner) and a first-person camera.
- Pure Java ray caster. No GPU, no window, no native library, no AWT fonts: it runs on a headless server, in CI and on Windows alike.
- Shapes that change how a build reads are drawn as shapes: slabs, stairs, fences, walls, panes, doors, trapdoors, carpets, snow layers, torches, plants. Everything else is a full cube.
- Colours come from the module's own table (`block-colors.txt`) plus naming rules, so it works with no game assets. Give it resource packs (`PackTextures`) and it uses real textures instead, including the project's generated ones.
- Built for a model to read: block-edge outlines so blocks can be counted, sun shadows for height, coordinate rulers on top and side views, and a caption that says how pixels map to coordinates and which blocks are visible.
- Image size budget: 512 px on the longest side by default (about 350 tokens at most), 64 to 1568 allowed. The result reports its estimated token cost.

## Use

```java
WorldRenderer renderer = new WorldRenderer(new PackTextures(List.of(workspace.resolve(".msc/pack"))));
RenderResult image = renderer.render(grid, View.parse("iso"), RenderOptions.defaults());
// image.png(), image.caption(), image.width(), image.height(), image.estimatedTokens()
```

`grid` is a `BlockGrid` from `msc-protocol`, as returned by `world.read`. It may be bigger than one protocol read if the caller stitches reads together.

View text forms (`View.parse`): `top`, `north`, `south`, `east`, `west`, `iso`, `iso-ne`, `iso-nw`, `iso-sw`, `iso-se`, `eye x,y,z look x,y,z [fov N]`, `eye x,y,z yaw N pitch N [fov N]`. A side name is where the camera stands: `south` looks north at the south face. Angles are Minecraft's (yaw 0 = south, pitch 90 = down).

Example caption:

```
Top view, north is up: x 0..12 left to right, z 0..12 top to bottom, 23 px per block, coordinates on the rulers. 309x307 px, about 127 tokens.
Visible: oak_slab 48%, oak_planks 24%, grass_block 24%, water 3%, oak_fence 1%, red_wool 1%.
```

## Limits

- Shapes are approximations, not vanilla models: no stair corners, no rotated rods, no block entities (chests are boxes, signs have no text), no entities.
- Textures are found by name (`block/<name>`, `_top`, `_side`, `_bottom`), not through blockstate and model JSON. Blocks whose textures are named differently fall back to the colour table.
- Only blocks inside the grid are drawn. To see inside a building from above, render a region that stops below the roof.
- The module ships no Mojang assets. Vanilla textures appear only if the caller points `PackTextures` at a client jar it already has.

## Depends on

- `msc-protocol` for `BlockGrid` and `Region`. `msc-core` will depend on this module, not the other way round.

## Stack

Java 25, Gradle Kotlin DSL. No third-party libraries.

## More

Design reasons are in [ADR 0008](../msc-docs/docs/adr/0008-renderer.md). See `PLAN.md` for this module's plan, and the project plan in `msc-docs` or [the shared plan doc](https://claude.ai/artifact/DLkdACibUcy9G29V9cjmUe).
