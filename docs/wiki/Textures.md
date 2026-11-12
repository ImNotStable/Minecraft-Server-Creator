# Textures

The AI draws pixel art as text, so no image model is needed. Everything goes into a resource pack in `<workspace>/.msc/pack`.

## Make a custom item in 5 steps

1. **Make the texture.** Pick one:
   - Generate it: "texture_generate a ruby gem, color #d4263a." Shapes: `gem`, `ingot`, `orb`. Patterns for blocks: `noise`, `ore`, `planks`, `bricks`.
   - Draw it: `texture_create` with a palette such as `r=#d4263a` and 16 rows of 16 characters.
   - Recolour one: "texture_recolor item/ruby to a blue sapphire."
2. **Look at it.** `texture_view` shows it back as text, about 80 tokens.
3. **Check the pack.** `pack_check` finds missing textures, bad sizes and broken models.
4. **Build and send the pack.** `pack_build` zips it, serves it from your machine and sends it to players on the server.
5. **Use it in your plugin.** Set the item's `item_model` component to the texture's name, for example `msc:item/ruby`.

## Other things you can do

- **Animate:** `texture_animate` with `scroll` (like flowing lava) or `pulse` (glowing).
- **Block models:** `model_create` with kinds such as `cube_all`, `cube_column` (logs), `orientable` (furnaces), `cross` (plants).
- **Sizes:** 16, 32 or 64 px. 16 looks like vanilla.

## Names

- `item/ruby` means `msc:item/ruby`. Use `yourname:block/ruby_ore` for your own namespace.
- Writing a still texture over an animated one removes the animation.

## Not tested yet

Seeing the pack in a real game client and `item_model` in game have not been checked with a player yet. Pictures from `world_render` do use your pack's textures.
