# msc-textures plan

Phase: Post-MVP. Steps are in order; each ends with passing tests.

- [x] Decide on image-model use in an ADR (ADR 0007: no image model; ramps and generators instead).
- [x] Ramps, block patterns, item shapes, recolour, outline, quantize.
- [x] Block and item model generation.
- [x] Animated textures.
- [x] Pack-wide consistency checks.
- [ ] Expose as `msc-core` tools (requested from the `msc-core` thread: `texture_generate`, `texture_recolor`, `texture_animate`, `model_create`, `pack_check`).
- [ ] More item shapes: tools, food, armour, dust, nugget.
- [ ] Check client-side rendering with a player on a real server.
