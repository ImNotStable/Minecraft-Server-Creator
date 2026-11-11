# ADR 0007: msc-textures, pixel-art generation without an image model

Date: 2026-11-11. Status: accepted. Decided by Claude under Jeremiah's instruction to build autonomously.

## Context

The MVP draws textures as text (`texture_create` in `msc-core`): the AI writes a palette and 256 characters per 16x16 texture. That works, but every texture costs a few hundred output tokens, shading quality depends on the model's taste each time, and nothing checks a whole pack for broken references. The plan's post-MVP item 5 asks for advanced textures: an image-model option, models, animation and pack-wide checks.

## Decisions

1. **No image model.** The product decision for textures is pixel art drawn by the AI. Image models give large, anti-aliased art that needs downscaling and palette clean-up to look like Minecraft, add a paid external dependency and an API key, and cannot run in tests. Instead the module gives the AI generators it can steer with a few words. Revisit only if a benchmark shows pixel-art quality is the bottleneck.
2. **A plain library, no Minecraft or MCP dependency.** `msc-textures` depends only on Gson (already in the catalog) and `java.awt.image`. `msc-core` depends on it and exposes it as tools, not the other way round (the old README said it depended on `msc-core`; that would be a cycle once core uses it). Fabric and Minestom tooling can reuse it as is.
3. **Ramps, not free colours.** Every generator takes a `Ramp`: 2 to 9 shades of one colour, darkest first, with the hue nudged towards blue in shadow and towards yellow in light (at most 10 degrees) and a contrast setting (1 for items, about 0.6 for blocks). This keeps palettes small (4 to 8 colours, as in vanilla) and makes a texture's colours a single argument such as `#d4263a`.
4. **Procedural, seeded block patterns.** `noise` (stone, dirt, sand), `ore`, `planks` and `bricks`, at 16, 32 or 64 pixels. They tile seamlessly, use only their ramps' colours and are deterministic for a seed, so "ruby ore, seed 3" always gives the same PNG and a test can pin it.
5. **Hand-made item templates.** `gem`, `ingot` and `orb` are 16x16 templates with shade levels (outline, deep shadow, shadow, base, light, glint) coloured from a ramp, lit from the top left like vanilla items. Templates beat procedural silhouettes for small sprites; more shapes are added the same way.
6. **Edits that save redrawing.** `recolor` maps a texture's colours by brightness onto a new ramp (diamond to ruby keeps all shading), `outline` adds a one-pixel border, `quantize` merges colours down to a limit.
7. **Animation as strips.** `Animation` holds square frames, writes the vertical strip plus `.png.mcmeta`, and has two generators: `scroll` (liquids, conveyors) and `pulse` (glowing ores and magic items, ramp colours brighten and dim in a wave). At most 64 frames.
8. **Models on vanilla parents only.** `cube_all`, `cube_column`, `cube_bottom_top`, `orientable`, `cross`, `item` (generated) and `handheld`, each with its named texture slots; plus the `items/<name>.json` definition so a model is usable through the `item_model` component. No hand-written element geometry.
9. **One text format with `msc-core`.** `PaletteText` reads and writes exactly the palette-and-rows format of `texture_create` and `texture_view`, and `ResourceId` uses the same naming rules (default namespace `msc`). `PackSource.zip` is deterministic with the same fixed timestamps as `msc-core`'s `ResourcePack`, so core can delegate to it without changing a pack's SHA-1.
10. **Pack-wide checks.** `PackLint` checks a folder or zip and reports, errors first: missing or broken `pack.mcmeta`; file names the game ignores; unreadable PNGs; non-power-of-two widths; stacked frames without `.png.mcmeta` (an error, since the game shows the missing texture); more than 16 colours at 32 pixels or less; semi-transparent block pixels; item textures with no transparent background; mixed resolutions within `block/` or `item/`; model textures and parents, and item-definition models (however nested), that point at missing assets; and unused textures as notes. References into the `minecraft` namespace are treated as vanilla and never reported missing. The report text is capped for tool output.
11. **Self-check without seeing the image.** `Stats` gives a one-line description (size, colours, opaque share, main hue and its share, frames), so an AI can confirm "16x16, 5 colours, 39% opaque, red" instead of reading pixels back.
12. **Held to the benchmark.** Tests copy `msc-bench`'s ruby PNG rules (16x16, at most 8 colours, 20 to 85% opaque, half the opaque pixels red) and run them on the generated gem, a recoloured diamond and a full `ruby-pack.zip`.

## Tools proposed for msc-core

Requested from the `msc-core` owner; the names follow `tools.md`:

- `texture_generate`: `name`, `kind` (a pattern or shape name), `color`, `accent`?, `size`? (16), `seed`?, `contrast`?, `model`? (as in `texture_create`). Writes the PNG (and model) and returns its `Stats` line and the palette text, so the AI can tweak it with `texture_create`.
- `texture_recolor`: `from`, `name`, `color`. Recolours an existing pack texture (or a vanilla one already copied into the pack) onto a ramp.
- `texture_animate`: `name`, `mode` (`scroll` or `pulse`), `frames`, `frametime`, `dx`/`dy`?. Turns a still texture into an animated one.
- `model_create`: `name`, `kind` (`cube_column` and so on), `textures` (slot=texture list), `item`?. Writes the model and, when asked, the item definition.
- `pack_check`: runs `PackLint` on the workspace pack; `pack_build` should also run it and print the summary line.

## Consequences

- A typical item or block texture costs one short tool call instead of 256 drawn characters, and comes out shaded consistently.
- Generated art is recognisably procedural. The AI can still draw by hand with `texture_create`, or start from a generated texture and edit its palette text.
- Shapes beyond gem, ingot and orb (tools, food, armour) are template work for later.
