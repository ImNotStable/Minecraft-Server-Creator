# Building and viewing the world

The server must be running (`server_start`) for these tools. They work the same on every platform.

## How to point at blocks

- A position: `10,-60,10`
- A region (two corners, any order): `0,-60,0 15,-50,15`
- An area (for height maps): `0,0 63,63`
- Block names can skip `minecraft:`. States use game syntax: `oak_stairs[facing=north]`.
- `world` is optional. It defaults to the main world.
- The dev world is flat with the ground at y = -61, so most builds start at y = -60.

## Looking at the world

Go from cheap to detailed:

1. **`world_summary`**: "What is in 0,-64,0 to 31,-40,31?" Gives block counts and bounds in about six lines. Kinds with three blocks or fewer are listed with their positions (`gold_block 1 at 13,-57,22`), so finding a block takes one call.
2. **`world_heightmap`**: the shape of the ground, one character per column. When heights span more than 35 blocks it prints runs instead, such as `-61*16 100*7` (16 columns at -61, then 7 at 100), and `(x16)` for a row repeated 16 times.
3. **`world_slice`**: the exact blocks, as text layers. Example:

   ```
   legend: .=air #=stone_bricks g=glass@2,66,0
   y=64
   #####
   #...# (x3)
   #####
   y=65..66 same as y=64
   y=67 all #
   ```

   Rows go north to south (low Z at the top), columns west to east (low X on the left). `(x3)` repeats a row, `y=65..66` covers a run of layers, and a block that appears once shows where it is (`@2,66,0`).

4. **`world_render`**: a picture. Views: `iso` (default), `top`, `north`, `south`, `east`, `west`, `iso-ne`/`nw`/`sw`/`se`, or a camera such as `eye 0,-40,0 look 10,-60,10`. A 512 px picture costs about 330 tokens. A copy is saved in `<workspace>/.msc/renders/`.

   Pictures use flat colours by default. Set `MSC_CLIENT_JAR` to a Minecraft client jar you own to get real textures. Your own pack's textures are always used.

## Building

1. **Plan** with `world_slice` of the spot, so the AI knows what is there.
2. **Build** with `build_batch`, the one build tool. It takes one step per line and runs them all as one edit with one undo:
   - `fill`: floors, clearing space (`air`), and a whole room in one line: `hollow` (walls, floor and roof, empty inside), `outline` (the same, keeping what is inside) or `walls` (four walls only).
   - `set`: single blocks, such as doors or torches.
   - `cylinder`, `ring`, `disc`, `sphere`, `dome`, `line` and `bands` (an island's land, beach and water).
   - `structure`: anything with detail, in the same text format as `world_slice`, so the AI can copy a slice, edit it and write it back. `_` means "leave this block alone". With `file=` it places a `.schem` (WorldEdit schematics work) or text file. A structure can be placed at several spots, turned or repeated.
   - `copy`: repeat or move a part of the world, with the same turn and repeat options.

   Blocks can be mixes such as `grass_block:78,moss_block:10`. It checks every block name before building and can do a dry run.
3. **Check** with `world_render` or `world_slice`.
4. **Undo** if needed: every build answers `edit eN: <count> blocks changed`. `build_undo` undoes the last edit, or pass `edit=eN`. Undo history lasts until MSC restarts.

## Saving builds

- `structure_save` writes a region to a `.schem` file (Sponge schematic, blocks only).
- `build_batch` with `structure X,Y,Z file=PATH` pastes a `.schem` file.

## Limits

- `world_slice`: up to about 1 million blocks, 128 wide.
- `build_batch` and `world_summary`: up to about 8 million blocks.
- `world_render`: up to 16 million blocks.
- Pictures are close, not perfect: no stair corners; entities are numbered markers, and sign and hologram text is drawn as labels, not on the block (`entities=false` hides them).
