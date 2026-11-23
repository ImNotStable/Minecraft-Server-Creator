# ADR 0079: A tool, a check and a guide for every resource pack file

Status: accepted, 2026-11-23. Decided by Claude, on Jeremiah's ask for proper MSC tools for every resource pack asset
type, comprehensive rather than minimal, targeting the current pack format, with no decision cards.

## Context

The pack tools covered textures only: `texture` draws PNGs (with `scroll` and `pulse` animations), `model_create`
writes seven parent-based model kinds and their item definitions, `pack` zips and sends. Everything else a server's
pack needs had no tool and no check: fonts (HUD icons and negative-space tricks in chat, titles, action bars and boss
bars), sounds.json and .ogg files, lang files, models with their own elements (Blockbench style), item definitions
beyond a plain model, blockstates, equipment (worn armour, wings, saddles), atlases, GUI sprite scaling (nine-slice),
animations with frame lists, shaders, post effects, particles, waypoint styles and texts. An agent could only write
those files by hand into `.msc/pack`, unchecked, and `PackLint` judged textures and `.mcmeta` files only.

## Decision

1. **One tool, `pack_asset`, for every other file**: `type=` (font, glyph, sound, lang, model, item, blockstate,
   equipment, atlas, mcmeta, shader, post_effect, particle, waypoint_style, text, file), `name=`, and the content as
   `json=` (an object), `text=` (shader or text source) or `from=` (a workspace file: .ogg, .ttf, .otf, unihex .zip,
   .png). One tool, not one per type: the tool list is paid for in every conversation (ADR 0019), and the formats are
   the game's own JSON, which agents read and write well once shown. Helpers only where JSON alone falls short:
   - `glyph` adds one bitmap glyph to a font and picks the next free private-use char (U+E000 up), returning the
     component that shows it;
   - `sound` copies the .ogg to `sounds/<event as folders>.ogg` (`_2`, `_3` for variants) and adds it to the event;
   - `lang`, `sound`, `atlas`, `glyph` and `mcmeta` merge into the file already there (keys, events, sources, providers,
     sections); every other type replaces its file.
   The code is `PackAssets` in msc-textures.
2. **Checked before anything is written**, against the whole pack: `PackLint` (now with `AssetLint`) checks every
   file type with the field names and allowed values of the Minecraft 26.3 client's own parsers, read from the client
   jar (provider and source types, display contexts, item model types and properties, tint types, equipment layers,
   gui scaling and its nine-slice rule, animation frames against the image, sound fields and Ogg Vorbis headers, stereo
   sounds, `#include` instead of `#moj_import`, the vanilla core shader and include files, post effect targets and
   samplers, waypoint distances). A finding that would stop the file loading refuses the call; a reference to a file the
   pack lacks yet (`missing()`, "is not in the pack") is only reported, so files can be written in any order.
3. **Template models are not faulted for open slots.** A face's `#slot` must resolve only in models a blockstate or item
   definition shows, through the model and its pack parents; a chain that reaches a vanilla parent is not judged.
   Running the new lint over the whole vanilla 26.3 asset tree found 1570 false errors this way before the rule, none
   after (the remaining warnings are the existing texture style checks).
4. **A texture's `.png.mcmeta` keeps its other sections.** Redrawing a texture drops only its `animation`; animating it
   replaces only that section, so a GUI sprite's nine-slice scaling survives both.
5. **Guides come in pages.** `msc_status guide=pack` holds every format with 26.3 examples. A guide longer than a
   result's 6000-character cap is cut at its section headings into pages, each ending with the call for the next
   (`page=2`). This also fixes the build guide, which had outgrown the cap and was silently cut short since ADR 0077.
6. The tool list grows by about 900 characters (19150 cap), for a capability no tool had.

## Verified

- Files written by `PackAssets`, from the guide's examples, were parsed by the 26.3 client's own codecs
  (`FontDefinitionFile`, `SoundEventRegistrationSerializer`, `CuboidModel`, `ClientItem`, `BlockStateModelDispatcher`,
  `EquipmentClientInfo`, `SpriteSources`, `AnimationMetadataSection`, `GuiMetadataSection`, `PostChainConfig`,
  `ParticleDescription`, `WaypointStyle`): 15 of 15 accepted.
- Not verified on screen: this machine has no Minecraft client to render a pack, so how a glyph, sound or shader looks
  and sounds in game is for the next playtest. The lint checks a shader's structure and includes, not that its GLSL
  compiles.

## Not done

- pack.mcmeta overlays and custom language definitions: a 26.3 server does not need older clients' formats.
- No generator for .ogg audio or TrueType fonts: they are copied in from files the agent or the user supplies.
- The standard library has no helper yet for a component in a custom font; the guide gives the raw component.
