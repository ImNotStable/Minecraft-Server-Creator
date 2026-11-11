# msc-renderer plan

Phase: Post-MVP. Steps are in order; each ends with passing tests.

- [x] Pick the rendering approach (software ray caster, no GPU): [ADR 0008](../msc-docs/docs/adr/0008-renderer.md).
- [x] Top-down view, plus north/south/east/west elevations, with coordinate rulers.
- [x] Isometric view from any corner.
- [x] First-person camera.
- [x] Resource pack textures, with a built-in colour table as fallback.
- [ ] Expose as an `msc-core` tool (`world_render`). Owned by the msc-core thread; the request is in ADR 0008.
- [ ] Check on a real Paper server with a vanilla client jar as texture source.

Later, if renders turn out to mislead:

- [ ] Blockstate and model JSON, for exact shapes and texture names.
- [ ] Entity markers and sign text.
- [ ] Coordinate labels on isometric views.
