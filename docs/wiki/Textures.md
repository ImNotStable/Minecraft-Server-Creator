# Textures

The AI draws pixel art as text, so no image model is needed. Everything goes into a resource pack in `<workspace>/.msc/pack`.

## Make a custom item in 4 steps

1. **Make the texture.** Pick one:
   - Generate it: "make a ruby gem texture, color #d4263a." Shapes: `gem`, `ingot`, `orb`, `sword`, `pickaxe`, `food`. Patterns for blocks: `noise`, `ore`, `planks`, `bricks`.
   - Draw it: the `texture` tool with a palette such as `r=#d4263a` and 16 rows of 16 characters.
   - Recolour one: "copy item/ruby as a blue sapphire."
2. **Look at it.** `texture_view` shows it back as text, about 80 tokens.
3. **Build and send the pack.** `pack` zips it, serves it from your machine, sends it to players on the server and lists missing textures, bad sizes and broken models. `pack check=true` only checks.
4. **Use it in your plugin.** Set the item's `item_model` component to the id the tool printed: the texture's namespace and file name without the folder, so `item/ruby` becomes `msc:ruby` (in Paper: `meta.setItemModel(new NamespacedKey("msc", "ruby"))`).

## Other things you can do

- **Animate:** `texture` with `animate=scroll` (like flowing lava) or `animate=pulse` (glowing), in the same call that makes the texture or later with only its name.
- **Block models:** `model_create` with kinds such as `cube_all`, `cube_column` (logs), `orientable` (furnaces), `cross` (plants).
- **Sizes:** 16, 32 or 64 px. 16 looks like vanilla.

## Names

- `item/ruby` means `msc:item/ruby`. Use `yourname:block/ruby_ore` for your own namespace.
- The `item_model` id drops the folder: texture `yourname:item/ruby` is item model `yourname:ruby`. Every texture tool prints it.
- Recolouring (`copy`) works on textures already in your pack, not on vanilla ones such as `item/diamond`.
- Generated swords and pickaxes are held like tools. For a drawn tool or staff, add `model=handheld`.
- Writing a still texture over an animated one removes the animation.

## Not tested yet

Seeing the pack in a real game client and `item_model` in game have not been checked with a player yet. Pictures from `world_render` do use your pack's textures.
