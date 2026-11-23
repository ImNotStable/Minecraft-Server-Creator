# Building plugin and mod survey (2026-11-23)

Which existing Minecraft plugins, mods and libraries MSC's AI agents could drive to make builds look
human-made, and how to wire in the ones worth it. Current target is Minecraft 26.3 (Paper build 257,
Fabric, NeoForge, Forge, Minestom).

How to read the verdicts:

- **Bridge**: call the plugin's Java API from an agent, headlessly, when the user has it installed.
- **Native**: reimplement the idea (not the code) as a `build_batch` step in msc-core, so every
  platform gets it, Minestom included.
- **Data**: no code dependency; MSC writes files the game or another tool reads (datapacks, config
  packs, SNBT).
- **Skip**: not usable by an AI, paid and closed, or redundant.

Licence rule used throughout: MSC never bundles a GPL, closed or paid jar. GPL plugins are installed
at setup time with the existing `plugins install=` tool (Modrinth, Hangar, SpigotMC) and called through
their API at runtime, which is the normal plugin relationship. MIT or Apache libraries may be put in
`gradle/libs.versions.toml` and shaded.

Facts marked *(verify)* came from search listings only, not from the project's own page or source,
and should be checked when the integration is built.

## Recommendation, ranked by impact on a human-made look

| # | What | How | Why it ranks here |
|---|------|-----|-------------------|
| 1 | Vanilla `/place feature`, datapack configured features, `/fillbiome` | Data + new `feature` and `biome` build_batch steps | Real trees, flower and grass patches, boulders, geodes, huge mushrooms, coral, on every vanilla-based platform, free, no licence question. Trees are the single biggest "AI build" tell today. |
| 2 | goPaint / goBrush / VoxelSniper / FAWE brush ideas | Native steps: `paint`, `stamp`, `erode`, `blend`, `splatter` | These plugins need a player clicking, so the AI can't use them anyway. Their algorithms (angle-aware surface mixing, grayscale heightmap stamps, melt/fill erosion) are small and well known. Native means Fabric, Forge and Minestom get them too. |
| 3 | WorldEdit (all loaders) and FastAsyncWorldEdit (Paper) | Optional bridge in msc-agent-paper, msc-agent-fabric, msc-agent-neoforge | Headless `EditSession` API with masks and patterns, `smooth`, `naturalize`, `forest`, biome set, and every schematic format. Worth it for heavy jobs and as a reference implementation. Lower than 1 and 2 only because most of its value can be native. |
| 4 | Entities and display-entity props, NPCs | Native `entity` step (summon with SNBT), then FancyNpcs or Citizens bridge | Interiors and towns look empty without furniture, item frames, armour stands, display blocks and villagers. Vanilla summon covers 80% on every platform. |
| 5 | Decorative heads | Data: curated head catalogue + `player_head{profile:...}` SNBT (already supported by `B{SNBT}`), optional MineSkin for generated heads | Heads are the cheapest way to add small objects (books, jars, food, lamps). No plugin needed; HeadDatabase is paid and its data is licensed. |
| 6 | Schematic formats beyond `.schem` | Library or small readers in msc-core: `.litematic`, vanilla structure `.nbt`, legacy `.schematic` | Opens the big public build libraries and lets users hand the AI their own Litematica work. |
| 7 | Image to build | Native `image` step using msc-textures block colours | Pixel art, map art, banners, mosaic floors, and grayscale heightmaps feeding `stamp`. |
| 8 | Terra config packs | Data + `plugins install`, Terra runs on Paper, Fabric and Minestom | Whole-world generation the AI can author as YAML. Biggest win for "make me a world", smaller for hand-built areas. |
| 9 | BetterModel for animated Blockbench models | Bridge in msc-agent-paper, optional | Animated props and creatures with no client mod. Free; ModelEngine is the paid equivalent. |
| 10 | Axiom (Paper plugin) | Install only, no bridge | A human tool. Installing its server plugin lets Jeremiah fix AI builds fast in the same world. |

Not recommended: Arceon (paid, player-driven), Iris (heavy, GPL, paid binary), WorldPainter (desktop
app, separate pipeline), ItemsAdder / Nexo / Oraxen / ModelEngine / HeadDatabase as dependencies (paid,
closed). Support them only as "if the user already owns it" detections later.

## Proposed build_batch additions

These keep to ADR 0019 (one build tool, new capabilities as steps) and the tool-list size guard.

```
feature X,Y,Z ID [count=N spread=R on=B]      # /place feature minecraft:fancy_oak, or a datapack id
biome X,Y,Z X,Y,Z ID [replace=ID]              # /fillbiome, also used to tint grass and water
paint X,Y,Z r=R B [surface=top|walls|all angle=MIN-MAX falloff=N noise=N]   # goPaint style
stamp X,Y,Z file=PATH.png [r=R h=H B rotate=N] # goBrush style grayscale height stamp, raise or dig
erode X,Y,Z r=R [preset=melt|fill|smooth|lift|float iterations=N]           # VoxelSniper presets
blend X,Y,Z r=R                                # FAWE blendball: majority-vote smoothing of mixed blocks
splatter X,Y,Z r=R B [seed=N density=N]        # FAWE / VoxelSniper splatter, organic patches
image X,Y,Z file=PATH.png [axis=x|y|z palette=wool|concrete|all dither=true]
entity X,Y,Z TYPE[{SNBT}] [facing=DIR]          # summon: armour stands, frames, displays, villagers
```

`feature` and `biome` need an agent op per platform (Paper: `World.generateTree` is limited, so run
the vanilla `place feature` and `fillbiome` commands through the agent's command path or NMS;
Fabric/NeoForge/Forge: `ConfiguredFeature.place` in msc-agent-vanilla; Minestom: no vanilla features,
so fall back to MSC's own tree shapes). `entity` already has a read side (`world_entities`), so the
write side fits the protocol. All the others are pure msc-core block maths over `world.write`.

## Findings by area

### World editing

| Project | What it does | Platforms, versions | Licence, bundling | Maintained | Headless API |
|---|---|---|---|---|---|
| [WorldEdit](https://dev.curseforge.com/projects/worldedit/files/8037361) (EngineHub) | Selections, patterns, masks, `smooth`, `naturalize`, `forest`, `deform`, expressions, biome set, schematics, CraftScripts | Bukkit/Paper, Fabric, NeoForge. 7.4.3 lists 1.21.4 to 26.1.2 | GPL-3.0. Download only | Yes, active | **Yes.** `WorldEdit.getInstance().newEditSession(world)`, `Operations.complete`, `ClipboardFormats`; no player needed |
| [FastAsyncWorldEdit](https://github.com/IntellectualSites/FastAsyncWorldEdit) | WorldEdit fork: async and much faster, plus brushes (scatter, splatter, erode, blendball, layer, heightmap, spline, sweep, catenary, populate-schematic), richer patterns (`#simplex`, `#linear`, angle and colour patterns) and masks (`#surface`, angle, adjacency) | Paper only. [26.3 support PR #3661](https://github.com/IntellectualSites/FastAsyncWorldEdit/pull/3661) *(verify merged/released)* | GPL-3.0. Download only | Yes, very active | **Partly.** Whole WorldEdit API works headlessly. Brushes are tools bound to a player's item; the brush classes can be called with an `EditSession` and a position, but that is internal API that changes between versions |
| [FastAsyncVoxelSniper](https://hangar.papermc.io/IntellectualSites/FastAsyncVoxelSniper) | VoxelSniper on FAWE: ~100 brushes (erode presets, blob, splatter, overlay, canyon, heightmap, tree, snow cone, jagged line) | Paper, needs FAWE | GPL-3.0 *(verify)*. Download only | Yes | **No.** Every brush runs from a `Snipe` bound to a player's arrow or gunpowder click and their target block |
| [VoxelSniper Reimagined](https://github.com/kevindagame/voxelsniper-reimagined) | Another VoxelSniper fork | Spigot/Paper, with a [CurseForge mod listing](https://www.curseforge.com/minecraft/mc-mods/voxelsniper-reimagined) *(verify Fabric status)* | GPL *(verify)* | Slower than FAVS *(verify)* | **No**, same player model |
| goBrush (Arcaniax) | Sculpt terrain with grayscale heightmap brushes, fractal and erosion-like stamps | Paper, needs FAWE | GPL-3.0 *(verify)*. Download only | Low activity *(verify)* | **No**, player-held brush |
| goPaint and forks [BetterGoPaint](https://github.com/OneLiteFeatherNET/BetterGoPaint), [goPaintAdvanced](https://hangar.papermc.io/TheNextLvl/goPaintAdvanced) | Paint surfaces with block mixes using angle, falloff, splatter, fractures, overlay; the main "organic texture" tool builders use | Paper, needs FAWE | GPL-3.0 (forks inherit). Download only | Forks active, original less so | **No**, player-held brush |
| [Arceon](https://arceon.gitbook.io/arceon-wiki/tools/terragen) | Premium terraforming suite: TerraGen, metabrushes, cliff tools | Paper with FAWE | Paid, closed. Cannot bundle | Yes | **No**, GUI and player tools |
| [Axiom](https://modrinth.com/mod/axiom) + [Axiom Paper Plugin](https://modrinth.com/plugin/axiom-paper-plugin/) | Client-side builder's editor (sculpt, paint, gizmos, blueprints) | Client mod on Fabric; server plugin for Paper | Client closed source; Paper plugin MIT ([per a Folia fork](https://forgejo.ekaii.fr/exo/axiom-paper-folia)). Plugin may be installed, never needed by the AI | Yes | **No.** All tools live in the player's client |
| [SchematicBrush Reborn](https://www.curseforge.com/minecraft/bukkit-plugins/schematicbrush) | Paste random schematics from a set with random rotation and flip (used for rock and tree scattering) | Paper, needs WorldEdit/FAWE | Open source *(verify licence)* | Yes | Partly; MSC's `scatter file=PATH,PATH` already covers this idea |
| [Terraformer](https://modrinth.com/mod/terraformer) | Terraforming brushes, schematic brush | Paper | *(verify)* | Recent | Player tools |

Takeaway: the "pretty terrain" plugins all need a player. Only WorldEdit's core API is truly headless.
So the brushes' ideas go native (row 2), and WorldEdit/FAWE becomes an optional accelerator (row 3).

### Generators, trees, vegetation, biomes

| Project | What it does | Platforms | Licence | Headless |
|---|---|---|---|---|
| Vanilla `/place feature`, `/place structure`, `/place jigsaw`, `/place template`, `/fillbiome` | Every vanilla tree, vegetation patch, boulder, geode, fossil, village piece; biome painting | All vanilla-based servers (Paper, Purpur, Fabric, Quilt, NeoForge, Forge). Not Minestom | Part of the game | **Yes**, commands or `ConfiguredFeature.place` |
| Datapack worldgen JSON | Custom trees (trunk and foliage placers, decorators like vines, beehives, leaf litter), custom patches, custom structures and jigsaw pools | Same | The AI writes the files | **Yes**, then `/place` |
| [Terra](https://www.github.com/PolyhedralDev/Terra) | Data-driven world generator with YAML [config packs](https://github.com/PolyhedralDev/TerraWiki/wiki/Config-Packs), custom trees and structures | Paper, Fabric, Minestom (and others) | MIT. Could even be a library | Generation is headless by nature |
| [Iris](https://github.com/VolmitSoftware/Iris) | Very detailed dimension engine with its own object format | Paper | GPL-3.0 source; prebuilt jar sold on [BuiltByBit](https://builtbybit.com/resources/iris-dimension-engine.56258/updates) | Headless, but heavy and hard for an AI to author |
| TerraformGenerator | Opinionated generator, nice trees and biomes | Paper | *(verify)* | Not configurable enough for authored worlds |
| [TreeGenerator](https://hangar.papermc.io/Icenore/TreeGenerator), [Leaf TreeGen](https://modrinth.com/mod/leaf-treegen), [BoostedTrees](https://builtbybit.com/resources/boostedtrees.76405/updates) | Procedural tree plugins | Paper / Fabric | Mixed; BoostedTrees paid | Mostly command or item driven *(verify)*; datapack trees and native shapes are a better path |
| [WorldPainter](https://github.com/Captain-Chaos/WorldPainter/wiki/Scripting-API) | Desktop map painter with a JavaScript [scripting API](https://worldpainter.net/trac/wiki/Scripting) and command-line export | Writes Java Edition worlds offline | GPL-3.0 | Headless via `wpscript`, but it is a separate offline pipeline and must keep up with each new world format |

### Schematics and structure formats

MSC already writes and reads Sponge `.schem` and its own text format.

| Library | Formats | Licence | Fit |
|---|---|---|---|
| [schematic4j](https://central.sonatype.com/artifact/net.sandrohc/schematic4j) | Read `.schem` v1-3, legacy `.schematic`, `.litematic` | MIT *(verify)* | Good for msc-core if licence confirmed; otherwise MSC's own NBT reader is enough for `.litematic` (it is a packed-long palette format) |
| [hollow-cube/schem](https://github.com/hollow-cube/schem) | `.schem` for Minestom | MIT *(verify)* | Not needed; MSC places schematics through its own ops |
| [hollow-cube/polar](https://www.github.com/hollow-cube/polar) | Fast Minestom world format | MIT *(verify)* | Useful if Minestom dev worlds need saving; not a building tool |
| WorldEdit `ClipboardFormats` | `.schem`, `.schematic`, some others | GPL-3.0, only via bridge | Use when the bridge exists |
| [litemapy](https://github.com/SmylerMC/litemapy) (Python), [rustmatica](https://docs.rs/crate/rustmatica/0.3.1) (Rust) | `.litematic` | Reference only | Good specs to test against |

Add vanilla structure `.nbt` too (what structure blocks and datapacks use), since it lets the AI save
pieces for `/place template` and jigsaw pools.

### Decoration and content plugins (heads, custom items, models)

| Project | What it does | Platforms | Licence, cost | Headless |
|---|---|---|---|---|
| [HeadDatabase](https://www.github.com/Arcaniax-Development/HeadDatabase-API) (Arcaniax) | 60k+ decorative heads with categories and a GUI | Paper | Paid plugin; free API jar. Data comes from minecraft-heads.com | API gives `ItemStack`s by id; useful only if the user owns it |
| [minecraft-heads.com API](https://minecraft-heads.com/wiki/heads/minecraft-heads-api) | The head catalogue itself, REST API v2 | Any | Needs an API licence/key for use in products *(verify terms)* | Yes, REST |
| [HeadDB](https://modrinth.com/plugin/headdb) (free) | Free head browser using the same public data | Paper | Free | GUI mostly |
| Vanilla `player_head` with a `profile` texture | Any head as a block, placed by SNBT | All platforms (Minestom too) | Free; the skin image belongs to its author | **Yes**, `build_batch` `B{SNBT}` already does it |
| [MineSkin](https://mineskin.org) API | Turns a PNG into a Mojang-signed texture value | Any | Free tier with key *(verify)* | **Yes**. With msc-textures this lets the AI paint its own head objects |
| ItemsAdder | Custom items, blocks, furniture, HUD with auto resource pack | Paper | Paid, closed | Has an API, but paid and conflicts with MSC's own pack serving |
| Nexo / Oraxen | Same space as ItemsAdder | Paper | Paid (Oraxen source-available history) | Same |
| [CraftEngine](https://modrinth.com/plugin/tRX6FMfQ) | Custom blocks (real block states), furniture, items | Paper | Free community edition plus paid premium on [BuiltByBit](https://builtbybit.com/resources/craftengine.82674/updates) *(verify licence)* | API exists; overlaps MSC's `pack` and `model_create` |
| ModelEngine | Animated Blockbench models on entities | Paper | Paid, closed | API, paid |
| [BetterModel](https://modrinth.com/plugin/BetterModel) | ModelEngine alternative on display entities, no client mod | Paper | Free and open source *(verify licence)* | API to spawn a model at a location, no player needed |
| Vanilla display entities (`block_display`, `item_display`, `text_display`) | Scaled, rotated blocks and items: furniture, small props, signs | All vanilla platforms; Minestom supports them too | Free | **Yes**, via summon SNBT |

Takeaway: MSC already serves its own resource pack and makes models and textures, so it does not need
ItemsAdder-style plugins. Heads and display entities give most of the "objects" look for free.

### NPCs

| Project | Platforms | Licence | Headless |
|---|---|---|---|
| [Citizens2](https://citizensnpcs.co) | Paper | OSL-3.0 *(verify)*; free build on their CI, paid on Spigot | **Yes**, `CitizensAPI.getNPCRegistry().createNPC(...)`, traits, skins, waypoints |
| [FancyNpcs](https://fancymcplugins.github.io/docs/fn-api.html) | Paper | MIT *(verify)* | **Yes**, packet NPCs through its API; light |
| Taterzens | Fabric | *(verify)* | Commands |
| Minestom | built in | Apache-2.0 | Fake-player entities are a few lines in the game project; no plugin needed |

Recommendation: vanilla villagers and mannequins (armour stands, `mannequin` entities where present)
through the `entity` step first; a FancyNpcs bridge for named, skinned, clickable NPCs on Paper.

## Integration plan

1. **msc-core + agents: `feature`, `biome`, `entity` steps.** New protocol op for features and biomes
   (platform-specific placement), entity summon over the existing write path. ADR needed. This is the
   biggest quality jump for the smallest cost.
2. **msc-core: native organic steps** (`paint`, `stamp`, `erode`, `blend`, `splatter`, `image`).
   Pure block maths, tested on `BlockGrid`, run on all seven platforms. Credit goPaint, goBrush,
   VoxelSniper and FAWE in the ADR as the source of the ideas; copy no GPL code.
3. **msc-stdlib / guides: head catalogue.** A small MSC-made set of head texture values (generated
   via msc-textures + MineSkin, so there is no third-party licence question), and `guide=build`
   examples using `player_head{profile:...}` and display entities for furniture.
4. **msc-core: `.litematic` and vanilla `.nbt` reading** for `build_batch file=` and `world_summary diff=`.
5. **Optional bridges, only when installed:** WorldEdit/FAWE (`EditSession` jobs, faster big fills,
   extra schematic formats), FancyNpcs, BetterModel. Each lives in the agent that owns the platform,
   is loaded reflectively or with a soft dependency, and is listed in `msc_status` so the AI knows
   it is there.
6. **Docs only:** how to install Axiom and Terra with `plugins install=` for people who want them.

## Gaps this does not close

- Rivers and caves: `path depth=` and `terrain` cover small ones. A proper river needs a spline
  carve with banks and bed mixing; worth a native `river` step after `erode` exists.
- Interiors: no plugin solves this. Display entities, heads and a furniture section in the build
  guide are the realistic path.
- Minestom has no vanilla features, so its trees stay on MSC's own shapes.

## Tool notes for this survey

graphify was read from the PC checkout (`graphify-out/GRAPH_REPORT.md`); the graphify CLI is not on
the cloud VM path, so no live queries were run. ponytail was not reachable from this cloud thread.
Facts about third-party projects come from web search listings on 2026-11-23; items marked
*(verify)* need a check against the project's own page before any integration work.
