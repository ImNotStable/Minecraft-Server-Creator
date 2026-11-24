# ADR 0074: Texture shapes are examples, not starts

Status: accepted, 2026-11-22. Decided by Claude, on Jeremiah's ask. Supersedes decision 2 of
[ADR 0072](0072-shaded-presets-to-customise.md). Superseded by [ADR 0106](0106-from-scratch.md): shapes and patterns are gone; every texture is drawn.

## Context

ADR 0072 told agents to take a `kind=` item shape (gem, ingot, orb, sword, pickaxe, food) and customise a few of its
rows, because drawing 256 pixels from scratch made texture tasks 2-3 times slower. Jeremiah: the template shapes are
meant to be examples and inspiration, not recommended for use. Every server that asks for a gem gets the same gem,
and an edited preset still reads as the preset, the same reason ADR 0073 removed sample code from the templates.

## Decision

1. **The shapes stay**, with their 7-shade ramp (ADR 0072 decision 1), as examples of good item shading.
2. **Nothing recommends shipping them.** The `texture` result note on a shape calls it an example to learn from (outline,
   light from the top left, tones, glint), not to ship, and says to draw your own item with `rows=`. The texture guide
   and the MCP instructions say the same, and `kind`'s description calls them example shapes.
3. **Block patterns are unchanged.** `kind=noise`, `ore`, `planks` and `bricks` are procedural bases for tiling blocks,
   seeded and recoloured, not fixed shapes, and the guide still starts blocks from them.

## Consequences

- Texture tasks will likely take longer again (ADR 0072 measured 88 s against 210 s for a ruby). That is the price of
  original items; the next bench run of `texture-ruby-item` shows how much.
- Not done: hiding the shapes from `kind` or making them a separate lookup. One example in context is useful, and the
  wording now carries the intent.
