# ADR 0042: Block entity data in world reads, builds, copies and schematics

Status: accepted, 2026-11-14, decided by Claude.

## Context

NBT audit (main before this change). What the AI could read and write, and through what:

| Data | Read | Write | Paper, Purpur | Fabric, Quilt, NeoForge, Forge | Minestom |
| --- | --- | --- | --- | --- | --- |
| Block entities (chest items, signs, spawners, command blocks) | sign text only: `world_render` draws it; nothing else | nothing through MSC tools; `build_batch` refused `chest{...}`. An undo put old data back | `server_command`: `data get/merge block`, `setblock ...{...}` (not undoable with `build_undo`) | same as Paper | nothing at all: Minestom has no vanilla commands, and the agent never read or wrote `Block.nbt()` except sign text |
| Block entities in `copy`, `structure_save`, `.schem` load | dropped: `copy` placed empty chests, schematics wrote `BlockEntities: []` and ignored them on load | | dropped | dropped | dropped |
| Entities (named villager with trades, armor stand pose, mob equipment) | `world_entities`: type, UUID (`ids=true`), position, name, scoreboard tags, health | none through MSC tools | `server_command`: `data get entity <uuid>`, `data merge entity`, `summon ... {...}` | same as Paper | none, and no NBT to have: Minestom entities are Java objects the project's code builds, with no NBT form to read or merge |
| Items (components in inventories, given items) | `player_test inventory`: name, count, custom name, item model, lore | none through MSC tools | `server_command`: `give @p diamond_sword[enchantments={...}]`, `item replace`, `data get entity @p Inventory` | same as Paper | none: no vanilla commands, and items are the project's code |

So vanilla's own commands already cover entities and items on six of the seven platforms, cheaply enough (one
`data get entity <uuid>` per entity, UUIDs from `world_entities ids=true`). Duplicating them in MSC would add tool text
and code for nothing the AI cannot already do. The real gaps were all block entities:

1. Minestom: no way to see or set block entity data at all, while the other platforms have `/data`.
2. Every platform: `copy`, `structure_save` and a `.schem` structure step lost chest contents, sign text and spawner
   mobs; a WorldEdit schematic's chests arrived empty.
3. Every platform: data set through `server_command` is not part of a `build_batch` edit, so `build_undo` cannot take it
   back, and a build with a few filled chests needs a second tool and one command per chest.

The tool list is capped (no new tools; under 16,000 characters, about 15,850 before this), and `msc-protocol` is shared
and must stay backwards compatible.

## Decision

1. **Data rides on the palette entry**, in `/setblock`'s own syntax: `minecraft:chest[facing=north]{Items:[...]}`.
   `world.write` takes such entries; `world.read` gives them only with a new optional `includeBlockData` flag. That is
   the whole protocol change: one nullable field, left out of the frame when off, so an older frame is the same bytes
   (`ParamsWireTest`), and a palette entry without `{` is a plain state as before.
2. **Write semantics are `/data merge block`'s**, on every platform: the data is merged into the block entity after the
   block is placed, also where the state is already there (then only the data changes, and it counts as changed). The
   undo record keeps the old block entity, so `build_undo` puts the old data back. A block that keeps no block entity
   data is refused before anything changes.
3. **Per platform**, behind three `AgentWorld` methods (`holdsData`, `blockData`, `mergeBlockData`) and
   `AgentWorlds.parseData`: the mod loaders use `saveWithoutMetadata`, `TagParser` and `CompoundTag.merge` as `/data`
   does; Minestom reads and merges `Block.nbt()` (deeply, like vanilla) with Adventure's `TagStringIO`; Paper, whose API
   has no block entity NBT, runs `execute in <dimension> run data get|merge block ...` on the main thread and reads the
   answer.
4. **The core keeps one spelling**: `Snbt` parses every agent's SNBT (vanilla's, Paper's spaced and with id and
   position, Minestom's) and writes it compactly without `id`, `x`, `y`, `z` and top-level empty lists and compounds
   (an empty chest's `Items:[]`). `BlockNames.full` does this for every name, so a bad SNBT in a step is refused by the
   core with its position before the agent sees it.
5. **Where the AI meets it**, with no new tool: `B{SNBT}` on any block of any `build_batch` step; `world_slice
   data=true` adds `data X,Y,Z {SNBT}` lines, which the structure text reads back (a pasted slice lands its data on the
   same blocks, and `rotate` turns the block, not the data); `copy` reads with data; `structure_save` writes Sponge
   `BlockEntities` (the block's id as `Id`) and a `.schem` structure step reads them back, version 2 and 3. The new
   text (about 100 characters) is paid for by trimming the world tools' wording without changing its meaning ("Default:
   main", "Corners, inclusive", shorter `world_render`, `world_heightmap` and `world_summary` lines, no example path on
   `structure_save`): the tool list is about 85 characters shorter than before (15,852 to 15,768).
6. **Entities and items stay with `server_command`** on the vanilla platforms (documented in `tools.md`), and Minestom
   has none to give: its entities and items are the project's own code. That difference is the platform's nature, not
   an MSC gap.

## Consequences

- A build with filled chests, signs with text or set spawners is one `build_batch` call with one undo id, on all seven
  platforms; copies and schematics keep the contents.
- `world.read` with data is bounded by `Limits.MAX_BLOCK_DATA_CHARS` (4 Mi characters): data is never cut, since cut
  SNBT would place wrong data; a larger read fails with `too_large`.
- Paper reads data with one `/data get` per block entity in the region; a region of thousands of chests reads slower
  there than on the other platforms. It also depends on the English feedback of `/data merge` ("Modified block data",
  "Nothing changed"), which the server always gives in English to the console sender it uses.
- `world_summary diff=` compares blocks only, as before: a snapshot now holds data, the world read it is compared with
  does not.
- Needs a live check (no EULA in CI): Paper's `/data` round trip through `execute in`, and the mod loaders' merge of item
  components (unit tests cover signs, since items need data packs a unit test does not load).
