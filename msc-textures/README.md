# msc-textures

Pixel-art textures without an image model: colour ramps, procedural block patterns, shaded item shapes, recolouring, animation, block and item models, a deterministic pack writer and pack-wide checks. `msc-core` exposes it as tools.

**Phase:** Post-MVP  
**Status:** Library done and tested. `msc-core` exposes it as the `texture`, `texture_view`, `model_create` and `pack` tools (ADR 0023).

## What it does

| Class | Use |
| --- | --- |
| `Ramp` | 2 to 9 shades of one colour, hue-shifted, with a contrast setting |
| `Patterns` | Tileable, seeded block textures: `noise`, `ore`, `planks`, `bricks` (16, 32 or 64 px) |
| `Shapes` | Shaded 16x16 item sprites: `gem`, `ingot`, `orb`, `sword`, `pickaxe`, `food` |
| `Edits` | `recolor` onto a ramp, `outline`, `quantize` to a colour limit |
| `Animation` | Frame strips and `.png.mcmeta`; `scroll` and `pulse` generators |
| `Models` | Block and item models on vanilla parents, item definitions for `item_model` |
| `PackSource` | Writes textures, animations, models and `pack.mcmeta`; zips deterministically (`msc-core`'s pack builds with it) |
| `PackLint` | Checks a pack folder or zip for broken references, sizes, palettes and metadata |
| `PaletteText`, `Stats` | The text format shared with `msc-core`'s `texture` tool, and a one-line texture summary |

```java
Ramp ruby = Ramp.from(Colors.parse("#d4263a"), 5);
Texture gem = Shapes.draw("gem", ruby, null);
Texture ore = Patterns.ore(16, Ramp.from(0xFF7F7F7F, 4, 0.6), ruby, 3);
PackSource pack = new PackSource(Path.of(".msc/pack"));
pack.writeTexture(ResourceId.parse("item/ruby"), gem);
pack.writeModel(ResourceId.parse("item/ruby"), Models.Kind.ITEM, Map.of("layer0", ResourceId.parse("item/ruby")), false);
System.out.println(PackLint.checkDirectory(pack.root()).text(20));
```

## Depends on

Gson only. No Minecraft, Paper or MCP dependency.

## Stack

Java 25, Gradle Kotlin DSL. `./gradlew :msc-textures:test`.

## More

Design decisions are in [ADR 0007](../msc-docs/docs/adr/0007-textures.md). See `PLAN.md` for what is left.
