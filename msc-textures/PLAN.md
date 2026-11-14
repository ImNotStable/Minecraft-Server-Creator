# msc-textures plan

Phase: Post-MVP. Steps are in order; each ends with passing tests.

- [x] Decide on image-model use in an ADR (ADR 0007: no image model; ramps and generators instead).
- [x] Ramps, block patterns, item shapes, recolour, outline, quantize.
- [x] Block and item model generation.
- [x] Animated textures.
- [x] Pack-wide consistency checks.
- [x] Expose as `msc-core` tools (ADR 0023): `texture` (draw, generate, recolor, animate), `texture_view`, `model_create`, `pack` (`check=true` lints).
- [x] Item shapes for tools and food: `sword`, `pickaxe`, `food`.
- [ ] More item shapes: armour, dust, nugget.
- [ ] Check client-side rendering with a player on a real server.
