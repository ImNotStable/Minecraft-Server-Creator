TEXTURE GUIDE: pixel art that looks made for this server, not generated.

THE RULE
- kind= presets (gem, ingot, orb, sword, pickaxe, food) are shaded starts in your colours, shared by everyone. Make each one yours by editing a few of the rows it returns (a facet, a notch, an accent colour, a second material) and sending them back with rows=: far cheaper than drawing 256 pixels. Draw from scratch only when no preset or vanilla texture (texture_view) fits the shape.

DRAWING A 16x16 ITEM
1. Silhouette first: one readable shape that fills most of the 16x16, centred, 1-pixel dark outline (not pure black: a dark shade of the main colour). Tools and weapons run diagonally, bottom-left to top-right.
2. Light from the top left. Each material gets a ramp of 3-4 tones: outline, shadow, base, highlight. Shift hue as well as brightness: shadows cooler (towards blue or purple), highlights warmer (towards yellow).
3. Materials read by their ramps: metal has high contrast and a sharp 1-2 pixel highlight; wood has grain lines; gems have facets (a light and a dark triangle) and a white glint; cloth and leather are low contrast.
4. Detail last: rivets, a gem in a hilt, glowing runes (an accent colour used in 2-6 pixels only), wear marks.
5. Count every row: each must have exactly 16 symbols. Check the result with texture_view and fix what reads wrong.
- 6-12 colours is typical. The texture tool warns when a drawing has too few tones or one colour floods it.

BLOCKS
- Blocks tile: edges must meet their neighbours. Start from kind=noise, bricks or planks for the base, or copy= a vanilla block recoloured, then add the signature in rows (cracks, runes, inlays, ore flecks).
- Keep contrast lower than items (a wall of high-contrast blocks is noise). Make variants (cracked, mossy, carved) so builds can mix them.
- animate=pulse for things that glow, scroll for fluids and energy.

A SET LOOKS LIKE A SET
- Pick one palette per faction or region (5-8 colours) and reuse it across that set's items, blocks and GUI icons. Same outline colour, same light direction, same pixel density.
- Name by theme (shattered_meridian:item/meridian_shard), give every custom item a model and an item definition (model_create) and use it from code (ItemBuilder.model(key) in the standard library).

CHECK BEFORE PACKING
- texture_view each new texture; world_render shows placed blocks with the pack's textures.
- pack sends the pack to the running server; look in game: dark textures vanish in shadow, tiny highlights vanish at distance.
