PACK GUIDE: every resource pack file, as Minecraft 26.3 (pack format 97) reads it.

HOW IT WORKS
- texture draws PNGs; model_create writes simple models; pack_asset writes every other file; pack zips, checks and sends.
- pack_asset type= name= json= (an object) | text= (shader or text source) | from= (a workspace file).
- It checks the file against the whole pack first. Anything that would stop it loading refuses the call and writes
  nothing. A reference the pack lacks yet (a texture you draw next) is only reported: write files in any order.
- Names: myplugin:path. No namespace means msc:. minecraft:... replaces a vanilla file.
- lang, sound, atlas, glyph and mcmeta MERGE into the file that is there; every other type replaces it.
- After writing: pack (builds, lists problems, sends to players). pack check=true lists problems only.

FONTS AND HUD ICONS (type=glyph, type=font)
- Icons in chat, titles, action bar, boss bars, scoreboards, item names: draw the icon with texture name=font/coin (a
  font/ texture may be any size), then
  pack_asset type=glyph name=myplugin:icons json={"file":"myplugin:font/coin.png","height":9,"ascent":8}
  It returns the private-use char it gave (, ...). Show it with the component
  {"text":"","font":"myplugin:icons"}. Default height 8, ascent 7.
  height scales the image to that many pixels tall; ascent is how far above the baseline it sits (ascent <= height).
  A negative ascent pushes a glyph down (HUD bars low on the screen).
- Several icons in one image: give chars yourself, one string per row, one char per cell:
  json={"file":"myplugin:font/icons.png","ascent":7,"height":8,"chars":["",""]}
  The image must divide evenly into those rows and columns. Pad short rows with \u0000.
- Whole font (type=font) json={"providers":[ ... ]}. Provider types:
  bitmap {file, height, ascent, chars}
  space {"type":"space","advances":{" ":4,"":-1,"":-128}}  negative advances move the cursor back:
    the standard trick for placing HUD art at exact offsets.
  ttf {"type":"ttf","file":"myplugin:runes.ttf","size":11,"oversample":2,"shift":[0,0],"skip":""}
    copy the font first: pack_asset type=file name=myplugin:font/runes.ttf from=fonts/runes.ttf
  unihex {"type":"unihex","hex_file":"myplugin:font/glyphs.zip","size_overrides":[]}
  reference {"type":"reference","id":"minecraft:include/default"}
  Any provider may have "filter":{"uniform":false} or {"jp":true}.
- Replace the default font only with care: minecraft:default is everyone's chat. Prefer your own font id.

SOUNDS (type=sound)
- pack_asset type=sound name=myplugin:boss.roar from=sounds/roar.ogg json={"subtitle":"subtitles.myplugin.boss.roar"}
  copies the file to sounds/boss/roar.ogg and adds it to the event in sounds.json. Call again with another from= to add
  a random variant. Play it: /playsound myplugin:boss.roar master @s, or the sound key from code.
- Ogg Vorbis only. Mono for sounds in the world: stereo plays at full volume everywhere, with no direction or falloff
  (fine for music and UI).
- Per-sound fields in json "sounds":[{"name":"myplugin:boss/roar","volume":0.8,"pitch":1.2,"weight":2,"stream":true,
  "attenuation_distance":32,"preload":false,"type":"file"}]. stream=true for long music. type=event plays another event.
- "replace":true in json replaces vanilla's list for a minecraft: event instead of adding to it.
- Give the subtitle a lang entry (type=lang).

LANG (type=lang)
- pack_asset type=lang name=myplugin:en_us json={"item.myplugin.ruby":"Ruby","subtitles.myplugin.boss.roar":"Boss roars"}
  Keys merge into the file. Always write en_us; other languages fall back to it.
- Use translatable components from code so players see their language; vanilla text: name=minecraft:en_us.

MODELS WITH ELEMENTS (type=model): Blockbench-style geometry
- json={"parent":"minecraft:block/block","textures":{"particle":"myplugin:block/lamp","glass":"myplugin:block/lamp"},
  "elements":[{"from":[4,0,4],"to":[12,10,12],"rotation":{"origin":[8,8,8],"axis":"y","angle":22.5},
  "light_emission":12,"faces":{"north":{"uv":[4,6,12,16],"texture":"#glass","cullface":"north"},"up":{"texture":"#glass"}}}],
  "display":{"gui":{"rotation":[30,225,0],"translation":[0,0,0],"scale":[0.625,0.625,0.625]}}}
- Coordinates are pixels of one block, -16 to 32 on each axis. Any angle is allowed; or "rotation":{"x":0,"y":45,"z":0,
  "origin":[8,8,8]}. "rescale":true stretches a 45 degree element back to full size.
- Faces: down, up, north, south, east, west. uv [u1,v1,u2,v2] in 0-16, rotation 0/90/180/270, tintindex, cullface.
- Every #slot a shown model uses must be set by it or a parent; a template (used only as a parent) may leave slots.
- Texture slot values may be {"sprite":"myplugin:block/lamp","force_translucent":true}.
- display contexts: thirdperson_righthand, thirdperson_lefthand, firstperson_righthand, firstperson_lefthand, head, gui,
  ground, fixed, on_shelf. gui_light: front (flat items) or side (blocks). ambientocclusion: false for glowing models.
- Blockbench exports load as they are (their groups and texture size fields are ignored).

ITEM DEFINITIONS (type=item): what an item_model shows
- Plain: {"model":{"type":"minecraft:model","model":"myplugin:item/ruby"}}. model_create writes these for you.
- Tinted: {"type":"minecraft:model","model":"...","tints":[{"type":"minecraft:dye","default":-6265536}]}
  tint types: constant, dye, grass, firework, potion, team, custom_model_data.
- Switch by state (select): {"type":"minecraft:select","property":"minecraft:custom_model_data","index":0,
  "cases":[{"when":"fire","model":{...}},{"when":["ice","frost"],"model":{...}}],"fallback":{...}}
  properties: custom_model_data, main_hand, charge_type, trim_material, block_state, display_context, local_time,
  context_entity_type, context_dimension, component.
- On/off (condition): {"type":"minecraft:condition","property":"minecraft:using_item","on_true":{...},"on_false":{...}}
  properties: using_item, broken, damaged, has_component, selected, carried, extended_view, keybind_down, view_entity,
  custom_model_data, fishing_rod/cast, bundle/has_selected_item, component.
- By number (range_dispatch): {"type":"minecraft:range_dispatch","property":"minecraft:use_duration","scale":0.05,
  "entries":[{"threshold":0.5,"model":{...}},{"threshold":1,"model":{...}}],"fallback":{...}}
  properties: custom_model_data, damage, count, cooldown, time, compass, use_duration, use_cycle, crossbow/pull,
  bundle/fullness.
- composite {"models":[...]} draws several; special {"model":{"type":"minecraft:head"},"base":"..."}; empty draws nothing.
- Top-level options: "oversized_in_gui":true (big models in slots), "hand_animation_on_swap":false.
- From code: an item with item_model myplugin:ruby uses items/ruby.json; custom_model_data drives select/condition.

BLOCKSTATES (type=blockstate)
- Retexture or remodel a vanilla block: name=minecraft:note_block,
  {"variants":{"instrument=banjo,note=0,powered=false":{"model":"myplugin:block/crate"},"":{"model":"minecraft:block/note_block"}}}
  Each variant: model, x/y/z (0, 90, 180, 270), uvlock, weight (random among a list).
- multipart: {"multipart":[{"when":{"north":"true"},"apply":{"model":"..."}},{"apply":{"model":"..."}}]};
  when may be {"OR":[...]} or {"AND":[...]}.
- Custom blocks on a vanilla server are vanilla states repurposed (note_block instruments, mushroom block faces, tripwire):
  every state you claim stops looking like the vanilla block.

EQUIPMENT (type=equipment): armour, elytra, saddles and animal armour as worn
- name=myplugin:ruby, {"layers":{"humanoid":[{"texture":"myplugin:ruby"}],"humanoid_leggings":[{"texture":"myplugin:ruby"}]}}
  Textures live at textures/entity/equipment/<layer>/<name>.png: draw texture name=entity/equipment/humanoid/ruby
  (64x32, the vanilla armour layout; copy= a vanilla one to start).
- Layers: humanoid, humanoid_leggings, humanoid_baby, wings, wolf_body, horse_body, llama_body, happy_ghast_body,
  nautilus_body, and the saddle layers (pig, strider, camel, camel_husk, horse, donkey, mule, zombie_horse,
  skeleton_horse, nautilus). "dyeable":{"color_when_undyed":-6265536} makes it take leather dye.
- From code: the item's equippable component asset_id myplugin:ruby.

TEXTURE METADATA (type=mcmeta): name is the texture, the png must exist
- Custom animation: json={"animation":{"frametime":2,"interpolate":true,"frames":[0,1,2,{"index":3,"time":10},2,1]}}
  Frames are stacked top to bottom; "width"/"height" set a frame size that is not square.
  (texture animate=scroll|pulse makes simple ones and their strip for you.)
- GUI sprites (textures/gui/sprites/..., the gui atlas) scale three ways:
  {"gui":{"scaling":{"type":"nine_slice","width":200,"height":20,"border":4}}}  corners stay, edges and middle stretch
  or tile; border may be {"left":4,"top":4,"right":4,"bottom":6}; "stretch_inner":true stretches the middle.
  {"gui":{"scaling":{"type":"tile","width":16,"height":16}}}  repeats. {"gui":{"scaling":{"type":"stretch"}}}  default.
  Replacing a vanilla sprite (minecraft:gui/sprites/widget/button) reskins every button.
- {"texture":{"blur":false,"clamp":true,"mipmap_strategy":"cutout"}} sampling; {"villager":{"hat":"partial"}}.
- Each json section replaces that section only; the others stay. Redrawing the texture keeps them too.

ATLASES (type=atlas): which textures get stitched
- Textures used by block and item models, gui sprites, particles, paintings and so on must be on the right atlas. The
  vanilla atlases already take whole folders (blocks: block/, items: item/, gui: gui/sprites/). A texture elsewhere
  needs a source: name=minecraft:blocks json={"sources":[{"type":"directory","source":"myplugin_extra","prefix":"myplugin_extra/"}]}
  Sources merge into the atlas. Types: directory {source, prefix}, single {resource, sprite}, filter {pattern},
  unstitch {resource, divisor_x, divisor_y, regions}, paletted_permutations {textures, palette_key, permutations}.
- Atlases: blocks, items, gui, particles, paintings, banner_patterns, shield_patterns, chests, shulker_boxes,
  decorated_pot, map_decorations, celestials. Only minecraft: atlases exist.

SHADERS (type=shader, type=post_effect): advanced, client-wide
- 26.3 shaders are GLSL "#version 330" with #include <minecraft:fog.glsl> (not #moj_import). A core shader only
  replaces the vanilla one of the same name: name=minecraft:core/rendertype_lines.fsh text=...
  Start from the vanilla source (in the client jar) and change little: a broken core shader breaks rendering for every
  player on the server.
- Shared code: name=myplugin:include/wave.glsl, used as #include <myplugin:wave.glsl>.
- post_effect: the game applies only minecraft:blur, creeper, entity_outline, invert and spider. json=
  {"targets":{"swap":{}},"passes":[{"vertex_shader":"minecraft:core/screenquad","fragment_shader":"myplugin:post/tint",
  "inputs":[{"sampler_name":"In","target":"minecraft:main"}],"output":"swap","uniforms":{"TintConfig":[{"name":"Color",
  "type":"vec4","value":[1,0.8,0.8,1]}]}},{"vertex_shader":"minecraft:core/screenquad","fragment_shader":"minecraft:post/blit",
  "inputs":[{"sampler_name":"In","target":"swap"}],"output":"minecraft:main"}]}
- Test shaders in game yourself: the lint checks structure and includes, not that GLSL compiles.

OTHER FILES
- particle: name=minecraft:flame json={"textures":["myplugin:flame_0","myplugin:flame_1"]} (textures/particle/...).
  Only particle types the game has read these.
- waypoint_style: name=myplugin:marker json={"sprites":["myplugin:marker"],"near_distance":64,"far_distance":256}
  sprites in textures/gui/sprites/hud/locator_bar_dot/; set from code as the waypoint's style.
- text: name=minecraft:splashes.txt text=..., also end.txt, postcredits.txt, credits.json.
- file: copy a binary in: name=myplugin:font/x.ttf (.otf, unihex .zip), sounds/x.ogg, textures/x.png.

NOT HERE
- pack.mcmeta is written by pack (format 97 and up). Overlays for older clients and custom languages are not needed for
  a 26.3 server.
