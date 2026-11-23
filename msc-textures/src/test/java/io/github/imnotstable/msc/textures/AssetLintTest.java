package io.github.imnotstable.msc.textures;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** PackLint's checks of fonts, sounds, lang, models, items, blockstates, equipment, atlases, metadata and shaders. */
class AssetLintTest {
    private final Map<String, byte[]> files = new HashMap<>();

    AssetLintTest() {
        files.put("pack.mcmeta", text("{\"pack\":{\"pack_format\":97,\"description\":\"x\"}}"));
    }

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private AssetLintTest put(String path, String content) {
        files.put(path, text(content));
        return this;
    }

    private AssetLintTest png(String path, int w, int h) {
        Texture t = new Texture(w, h);
        t.set(0, 0, 0xFF112233);
        files.put(path, t.toPng());
        return this;
    }

    private List<String> lint() {
        return PackLint.check(files).findings().stream().filter(f -> f.severity() != PackLint.Severity.INFO)
                .map(PackLint.Finding::toString).toList();
    }

    @Test
    void fonts() {
        png("assets/msc/textures/font/icons.png", 16, 8);
        put("assets/msc/font/hud.json", "{\"providers\":["
                + "{\"type\":\"bitmap\",\"file\":\"msc:font/icons.png\",\"ascent\":7,\"height\":8,\"chars\":[\"\\uE000\\uE001\"]},"
                + "{\"type\":\"bitmap\",\"file\":\"msc:font/icons.png\",\"ascent\":9,\"height\":8,\"chars\":[\"\\uE000\",\"\\uE002\\uE003\"]},"
                + "{\"type\":\"space\",\"advances\":{\"\\uF801\":-1,\"ab\":2}},"
                + "{\"type\":\"ttf\",\"file\":\"msc:gone.ttf\"},"
                + "{\"type\":\"reference\",\"id\":\"minecraft:include/default\"},"
                + "{\"type\":\"legacy_unicode\"}]}");
        put("assets/msc/font/odd.ttf", "not a font");
        assertThat(lint()).containsExactlyInAnyOrder(
                "ERROR assets/msc/font/hud.json: bitmap msc:font/icons.png: ascent is higher than height 8",
                "ERROR assets/msc/font/hud.json: bitmap msc:font/icons.png: chars rows differ in length (1 and 2); pad with \\u0000",
                "ERROR assets/msc/font/hud.json: space advance key \"ab\" is not one character",
                "ERROR assets/msc/font/hud.json: font file msc:gone.ttf is not in the pack",
                "ERROR assets/msc/font/hud.json: provider type legacy_unicode is not one of bitmap, reference, space, ttf, unihex",
                "WARNING assets/msc/font/hud.json: char \\uE000 is mapped twice in this font; the first provider wins",
                "ERROR assets/msc/font/odd.ttf: not a TrueType or OpenType font");
    }

    @Test
    void bitmapGridMustDivideTheImage() {
        png("assets/msc/textures/font/icons.png", 15, 8);
        put("assets/msc/font/hud.json", "{\"providers\":[{\"type\":\"bitmap\",\"file\":\"msc:font/icons.png\",\"ascent\":7,"
                + "\"chars\":[\"\\uE000\\uE001\"]}]}");
        assertThat(lint()).contains(
                "ERROR assets/msc/font/hud.json: bitmap msc:font/icons.png is 15x8, not a grid of 2 by 1 equal cells");
    }

    @Test
    void soundsAndOgg() {
        files.put("assets/msc/sounds/roar.ogg", PackAssetsTest.ogg(2));
        files.put("assets/msc/sounds/opus.ogg", text("OggS" + "x".repeat(60)));
        put("assets/msc/sounds.json", "{\"roar\":{\"sounds\":[\"msc:roar\",{\"name\":\"gone\",\"pitch\":0,\"volume\":2},"
                + "{\"name\":\"minecraft:entity.zombie.ambient\",\"type\":\"event\"}],\"subtitle\":\"s\",\"loud\":true},"
                + "\"Bad\":{\"sounds\":[]}}");
        assertThat(lint()).containsExactlyInAnyOrder(
                "WARNING assets/msc/sounds/roar.ogg: 2 channels: a stereo sound plays at full volume everywhere, with no "
                        + "direction or distance falloff; use mono for sounds in the world (stereo is fine for music)",
                "ERROR assets/msc/sounds/opus.ogg: an Ogg file but not Vorbis (Opus or FLAC?); the game plays Ogg Vorbis only",
                "WARNING assets/msc/sounds.json: event roar: unknown field loud (sounds, replace, subtitle)",
                "ERROR assets/msc/sounds.json: event roar: pitch must be above 0",
                "WARNING assets/msc/sounds.json: event roar volume 2.0 is outside 0.0 to 1.0",
                "ERROR assets/msc/sounds.json: event roar: sound msc:gone is not in the pack",
                "ERROR assets/msc/sounds.json: event Bad: event names are lowercase a-z, 0-9, _ . - only",
                "WARNING assets/msc/sounds.json: event Bad has no sounds; it plays nothing");
    }

    @Test
    void langAndUnknownFolders() {
        put("assets/msc/lang/de_de.json", "{\"a\":\"A\",\"b\":1}");
        put("assets/msc/sound/x.ogg", "");
        put("assets/msc/sound.json", "{}");
        assertThat(lint()).containsExactlyInAnyOrder(
                "ERROR assets/msc/lang/de_de.json: key b: a translation must be a string",
                "WARNING assets/msc/lang/de_de.json: no en_us.json beside it; players on other languages fall back to "
                        + "en_us and see raw keys",
                "WARNING assets/msc/sound/x.ogg: the game reads no sound folder; folders are atlases, blockstates, "
                        + "equipment, font, items, lang, models, particles, post_effect, shaders, sounds, texts, textures, waypoint_style",
                "WARNING assets/msc/sound.json: the game reads no file sound.json here; did you mean sounds.json?");
    }

    @Test
    void elementModelsAndTheSlotsShownModelsUse() {
        png("assets/msc/textures/block/lamp.png", 16, 16);
        put("assets/msc/models/block/template.json", "{\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],"
                + "\"faces\":{\"up\":{\"texture\":\"#top\"},\"down\":{\"texture\":\"#side\"}}}]}");
        put("assets/msc/models/block/lamp.json", "{\"parent\":\"msc:block/template\",\"textures\":{\"top\":\"msc:block/lamp\"}}");
        put("assets/msc/models/block/odd.json", "{\"textures\":{\"all\":{\"sprite\":\"msc:block/lamp\",\"force_translucent\":true}},"
                + "\"gui_light\":\"top\",\"display\":{\"hand\":{},\"gui\":{\"scale\":[1,1]}},"
                + "\"elements\":[{\"from\":[0,0,-17],\"to\":[16,16,16],\"light_emission\":20,\"rotation\":{\"angle\":67.5,\"axis\":\"w\","
                + "\"origin\":[8,8,8]},\"faces\":{\"top\":{\"texture\":\"#all\"},\"up\":{\"texture\":\"#all\",\"uv\":[0,0,16],"
                + "\"rotation\":45,\"cullface\":\"sky\"}}}]}");
        put("assets/msc/blockstates/lamp.json", "{\"variants\":{\"\":[{\"model\":\"msc:block/lamp\",\"y\":45,\"weight\":0}]}}");
        put("assets/msc/blockstates/both.json", "{\"variants\":{},\"multipart\":[]}");
        put("assets/msc/blockstates/parts.json", "{\"multipart\":[{\"when\":[],\"apply\":{\"model\":\"msc:block/odd\"}},{}]}");
        assertThat(lint()).containsExactlyInAnyOrder(
                "ERROR assets/msc/models/block/lamp.json: texture #side is set by neither this model nor its parents; "
                        + "those faces show the missing texture (shown by assets/msc/blockstates/lamp.json)",
                "ERROR assets/msc/models/block/odd.json: gui_light must be front or side",
                "ERROR assets/msc/models/block/odd.json: display hand is not one of firstperson_lefthand, "
                        + "firstperson_righthand, fixed, ground, gui, head, on_shelf, thirdperson_lefthand, thirdperson_righthand",
                "ERROR assets/msc/models/block/odd.json: display gui scale must be [x, y, z]",
                "ERROR assets/msc/models/block/odd.json: element 0 from: -17.0 is outside -16 to 32",
                "ERROR assets/msc/models/block/odd.json: element 0: light_emission must be a whole number 0 to 15",
                "ERROR assets/msc/models/block/odd.json: element 0: rotation axis must be x, y or z",
                "ERROR assets/msc/models/block/odd.json: element 0 top: faces are down, east, north, south, up, west",
                "ERROR assets/msc/models/block/odd.json: element 0 up: uv must be [u1, v1, u2, v2]",
                "ERROR assets/msc/models/block/odd.json: element 0 up: face rotation must be 0, 90, 180 or 270",
                "ERROR assets/msc/models/block/odd.json: element 0 up: cullface must be one of down, east, north, south, up, west",
                "ERROR assets/msc/blockstates/lamp.json: variant \"\": y must be 0, 90, 180 or 270",
                "ERROR assets/msc/blockstates/lamp.json: variant \"\": weight must be at least 1",
                "ERROR assets/msc/blockstates/both.json: has both variants and multipart; give one",
                "ERROR assets/msc/blockstates/parts.json: multipart[0]: when must be an object of properties, or "
                        + "{\"OR\": [...]} or {\"AND\": [...]}",
                "ERROR assets/msc/blockstates/parts.json: multipart[1] has no apply");
    }

    @Test
    void itemDefinitions() {
        put("assets/msc/items/wand.json", "{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:using_item\","
                + "\"on_true\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:speed\",\"entries\":[{\"model\":"
                + "{\"type\":\"minecraft:empty\"}}]},\"on_false\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/stick\","
                + "\"tints\":[{\"type\":\"minecraft:rainbow\"}]}},\"glow\":true}");
        put("assets/msc/items/head.json", "{\"model\":{\"type\":\"minecraft:special\",\"model\":{\"type\":\"minecraft:head\"}}}");
        assertThat(lint()).containsExactlyInAnyOrder(
                "WARNING assets/msc/items/wand.json: unknown field glow",
                "ERROR assets/msc/items/wand.json: model on_true: property minecraft:speed is not one of bundle/fullness, "
                        + "compass, cooldown, count, crossbow/pull, custom_model_data, damage, time, use_cycle, use_duration",
                "ERROR assets/msc/items/wand.json: model on_true entry 0 needs threshold and model",
                "ERROR assets/msc/items/wand.json: model on_false tint: type minecraft:rainbow is not one of constant, "
                        + "custom_model_data, dye, firework, grass, potion, team",
                "ERROR assets/msc/items/head.json: model: special has no base model (for particles and transforms)");
    }

    @Test
    void equipmentAtlasesParticlesAndWaypoints() {
        png("assets/msc/textures/entity/equipment/humanoid/ruby.png", 64, 32);
        put("assets/msc/equipment/ruby.json", "{\"layers\":{\"humanoid\":[{\"texture\":\"msc:ruby\"}],"
                + "\"humanoid_leggings\":[{\"texture\":\"msc:ruby\",\"dyeable\":true}],\"cape\":[]}}");
        put("assets/msc/atlases/blocks.json", "{\"sources\":[]}");
        put("assets/minecraft/atlases/blocks.json", "{\"sources\":[{\"type\":\"single\",\"resource\":\"msc:block/gone\"},"
                + "{\"type\":\"directory\"},{\"type\":\"glob\"}]}");
        put("assets/minecraft/particles/flame.json", "{\"textures\":[\"msc:spark\"]}");
        put("assets/msc/waypoint_style/dot.json", "{\"sprites\":[\"minecraft:default_0\"],\"near_distance\":50,\"far_distance\":10}");
        assertThat(lint()).containsExactlyInAnyOrder(
                "ERROR assets/msc/equipment/ruby.json: humanoid_leggings texture msc:ruby is not in the pack",
                "ERROR assets/msc/equipment/ruby.json: humanoid_leggings: dyeable must be an object, such as {} or "
                        + "{\"color_when_undyed\": -6265536}",
                "ERROR assets/msc/equipment/ruby.json: layer type cape is not one of camel_husk_saddle, camel_saddle, "
                        + "donkey_saddle, happy_ghast_body, horse_body, horse_saddle, humanoid, humanoid_baby, humanoid_leggings, "
                        + "llama_body, mule_saddle, nautilus_body, nautilus_saddle, pig_saddle, skeleton_horse_saddle, "
                        + "strider_saddle, wings, wolf_body, zombie_horse_saddle",
                "WARNING assets/msc/atlases/blocks.json: the game builds only the minecraft atlases banner_patterns, blocks, "
                        + "celestials, chests, decorated_pot, gui, items, map_decorations, paintings, particles, "
                        + "shield_patterns, shulker_boxes; sources here are never stitched",
                "ERROR assets/minecraft/atlases/blocks.json: resource msc:block/gone is not in the pack",
                "ERROR assets/minecraft/atlases/blocks.json: directory source has no source folder",
                "ERROR assets/minecraft/atlases/blocks.json: source type glob is not one of directory, filter, "
                        + "paletted_permutations, single, unstitch",
                "ERROR assets/minecraft/particles/flame.json: texture msc:spark is not in the pack",
                "ERROR assets/msc/waypoint_style/dot.json: far_distance must be beyond near_distance");
    }

    @Test
    void textureMetadata() {
        png("assets/msc/textures/gui/sprites/panel.png", 32, 32);
        png("assets/msc/textures/item/wide.png", 32, 16);
        png("assets/msc/textures/block/flow.png", 16, 48);
        put("assets/msc/textures/gui/sprites/panel.png.mcmeta",
                "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":32,\"height\":32,\"border\":{\"left\":16,\"top\":2,"
                        + "\"right\":16,\"bottom\":2}}},\"sparkle\":{}}");
        put("assets/msc/textures/item/wide.png.mcmeta", "{\"animation\":{\"width\":16,\"height\":16,\"frames\":[0,1,"
                + "{\"index\":2,\"time\":0}]},\"gui\":{\"scaling\":{\"type\":\"tile\"}}}");
        put("assets/msc/textures/block/flow.png.mcmeta", "{\"animation\":{\"frametime\":0,\"frames\":[0,1,2]},"
                + "\"texture\":{\"mipmap_strategy\":\"sharp\"},\"villager\":{\"hat\":\"tall\"}}");
        assertThat(lint()).containsExactlyInAnyOrder(
                "ERROR assets/msc/textures/gui/sprites/panel.png.mcmeta: nine_slice borders leave no centre in 32x32: "
                        + "left+right and top+bottom must be less than width and height",
                "WARNING assets/msc/textures/gui/sprites/panel.png.mcmeta: unknown section sparkle (animation, gui, "
                        + "palette, texture, villager)",
                "ERROR assets/msc/textures/item/wide.png.mcmeta: frame index 2 is past the last frame 1",
                "ERROR assets/msc/textures/item/wide.png.mcmeta: a frame time must be at least 1 tick",
                "WARNING assets/msc/textures/item/wide.png.mcmeta: gui scaling is read only for sprites in "
                        + "textures/gui/sprites/; this texture is not on the gui atlas",
                "ERROR assets/msc/textures/item/wide.png.mcmeta: tile needs width and height, the sprite's size in GUI pixels",
                "ERROR assets/msc/textures/block/flow.png.mcmeta: frametime must be at least 1 tick",
                "ERROR assets/msc/textures/block/flow.png.mcmeta: mipmap_strategy must be auto, mean, cutout, "
                        + "strict_cutout or dark_cutout",
                "ERROR assets/msc/textures/block/flow.png.mcmeta: villager hat must be none, partial or full");
    }

    @Test
    void shadersAndPostEffects() {
        put("assets/minecraft/shaders/core/terrain.fsh", "#version 330\n#include <minecraft:fog.glsl>\n"
                + "#include <msc:wave.glsl>\nvoid main() {\n");
        put("assets/minecraft/shaders/core/glow.fsh", "#moj_import <minecraft:fog.glsl>\nvoid main() {}\n");
        put("assets/msc/shaders/include/tint.glsl", "vec3 tint(vec3 c) { return c; }\n");
        put("assets/minecraft/post_effect/invert.json", "{\"targets\":{\"swap\":{}},\"passes\":[{\"vertex_shader\":"
                + "\"minecraft:core/screenquad\",\"fragment_shader\":\"msc:post/tint\",\"inputs\":[{\"sampler_name\":\"In\","
                + "\"target\":\"minecraft:main\"},{\"sampler_name\":\"In\",\"target\":\"other\"}],\"output\":\"swap\"}]}");
        assertThat(lint()).containsExactlyInAnyOrder(
                "ERROR assets/minecraft/shaders/core/terrain.fsh: include msc:wave.glsl is not in the pack",
                "WARNING assets/minecraft/shaders/core/terrain.fsh: 1 { but 0 }: the shader will not compile",
                "ERROR assets/minecraft/shaders/core/glow.fsh: the first line must be #version (vanilla uses #version 330)",
                "ERROR assets/minecraft/shaders/core/glow.fsh: #moj_import is gone: Minecraft 26.3 uses #include "
                        + "<minecraft:fog.glsl>",
                "WARNING assets/minecraft/shaders/core/glow.fsh: Minecraft 26.3 has no core shader minecraft:core/glow.fsh; "
                        + "a core shader only replaces the vanilla one of the same name",
                "ERROR assets/minecraft/post_effect/invert.json: pass 0: fragment_shader msc:post/tint is not in the pack",
                "ERROR assets/minecraft/post_effect/invert.json: pass 0: sampler In is given twice",
                "ERROR assets/minecraft/post_effect/invert.json: pass 0: target other is not declared in targets");
    }

    @Test
    void onlyMissingReferencesCountAsMissing() {
        put("assets/msc/models/item/a.json", "{\"parent\":\"msc:item/gone\"}");
        put("assets/msc/blockstates/b.json", "{}");
        assertThat(PackLint.check(files).findings()).extracting(PackLint.Finding::missing).containsExactly(false, true);
    }
}
