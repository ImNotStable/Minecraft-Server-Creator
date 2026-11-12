# Building and viewing the world

The server must be running (`server_start`) for these tools. They work the same on Paper, Fabric and Minestom.

## How to point at blocks

- A position: `10,64,10`
- A region (two corners, any order): `0,64,0 15,70,15`
- An area (for height maps): `0,0 63,63`
- Block names can skip `minecraft:`. States use game syntax: `oak_stairs[facing=north]`.
- `world` is optional. It defaults to the main world.

## Looking at the world

Go from cheap to detailed:

1. **`world_summary`**: "What is in 0,60,0 to 31,80,31?" Gives block counts and bounds in about six lines.
2. **`world_heightmap`**: the shape of the ground, one character per column.
3. **`world_slice`**: the exact blocks, as text layers. Example:

   ```
   legend: .=air #=stone_bricks g=glass
   y=64
   #####
   #...#
   #####
   y=65 same as y=64
   y=66 all #
   ```

   Rows go north to south (low Z at the top), columns west to east (low X on the left).

4. **`world_render`**: a picture. Views: `iso` (default), `top`, `north`, `south`, `east`, `west`, `iso-ne`/`nw`/`sw`/`se`, or a camera such as `eye 0,80,0 look 10,64,10`. A 512 px picture costs about 330 tokens. A copy is saved in `<workspace>/.msc/renders/`.

   Pictures use flat colours by default. Set `MSC_CLIENT_JAR` to a Minecraft client jar you own to get real textures. Your own pack's textures are always used.

## Building

1. **Plan** with `world_slice` of the spot, so the AI knows what is there.
2. **Build** with one of:
   - `build_fill`: walls, floors, clearing space (`air`).
   - `build_structure`: anything with detail. Same text format as `world_slice`, so the AI can copy a slice, edit it and write it back. `_` means "leave this block alone".
   - `build_set`: a few single blocks, such as doors or torches.
   - `build_copy`: repeat or move a build.
3. **Check** with `world_render` or `world_slice`.
4. **Undo** if needed: every build answers `edit eN: <count> blocks changed`. `build_undo` undoes the last edit, or pass `edit=eN`. Undo history lasts until MSC restarts.

## Saving builds

- `structure_save` writes a region to a `.schem` file (Sponge schematic, blocks only).
- `structure_load` pastes a `.schem` file. WorldEdit schematics work.

## Limits

- `world_slice` and `build_structure`: up to about 1 million blocks, 128 wide.
- `build_fill` and `world_summary`: up to about 8 million blocks.
- `world_render`: up to 16 million blocks.
- Pictures are close, not perfect: no stair corners, no entities, signs have no text.
