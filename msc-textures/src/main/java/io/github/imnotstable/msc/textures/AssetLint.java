package io.github.imnotstable.msc.textures;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.imnotstable.msc.textures.PackLint.Severity;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link PackLint}'s checks of every pack file other than textures and their models (ADR 0081): fonts, sounds, lang,
 * blockstates, equipment, atlases, texture metadata, element models, item definitions, shaders, post effects,
 * particles, waypoint styles and texts. The field names and allowed values are those of the Minecraft {@value
 * PackSource#MINECRAFT} client's own parsers; a reference into {@code minecraft} is taken to be vanilla, except shaders,
 * whose vanilla files are listed here.
 */
final class AssetLint {
    /** Folders the game reads under {@code assets/<namespace>/}. */
    static final Set<String> FOLDERS = Set.of("atlases", "blockstates", "equipment", "font", "items", "lang", "models",
            "particles", "post_effect", "shaders", "sounds", "texts", "textures", "waypoint_style");
    /** Files the game reads directly under {@code assets/<namespace>/}. */
    static final Set<String> ROOT_FILES = Set.of("sounds.json", "regional_compliancies.json", "gpu_warnlist.json");
    static final Set<String> ATLASES = Set.of("banner_patterns", "blocks", "celestials", "chests", "decorated_pot",
            "gui", "items", "map_decorations", "paintings", "particles", "shield_patterns", "shulker_boxes");
    static final Set<String> EQUIPMENT_LAYERS = Set.of("humanoid", "humanoid_leggings", "humanoid_baby", "wings",
            "wolf_body", "horse_body", "llama_body", "pig_saddle", "strider_saddle", "camel_saddle", "camel_husk_saddle",
            "horse_saddle", "donkey_saddle", "mule_saddle", "zombie_horse_saddle", "skeleton_horse_saddle",
            "happy_ghast_body", "nautilus_saddle", "nautilus_body");
    static final Set<String> DISPLAY = Set.of("thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand",
            "firstperson_lefthand", "head", "gui", "ground", "fixed", "on_shelf");
    static final Set<String> FACES = Set.of("down", "up", "north", "south", "east", "west");
    static final Set<String> ITEM_MODELS = Set.of("empty", "model", "range_dispatch", "special", "composite",
            "bundle/selected_item", "select", "condition");
    static final Set<String> TINTS = Set.of("custom_model_data", "constant", "dye", "grass", "firework", "potion", "team");
    static final Set<String> SELECT = Set.of("custom_model_data", "main_hand", "charge_type", "trim_material",
            "block_state", "display_context", "local_time", "context_entity_type", "context_dimension", "component");
    static final Set<String> CONDITION = Set.of("custom_model_data", "using_item", "broken", "damaged", "fishing_rod/cast",
            "has_component", "bundle/has_selected_item", "selected", "carried", "extended_view", "keybind_down",
            "view_entity", "component");
    static final Set<String> RANGE = Set.of("custom_model_data", "bundle/fullness", "damage", "cooldown", "time",
            "compass", "crossbow/pull", "use_cycle", "use_duration", "count");
    static final Set<String> SPECIAL = Set.of("bell", "banner", "book", "conduit", "chest", "copper_golem_statue", "head",
            "player_head", "shulker_box", "shield", "trident", "decorated_pot", "end_cube");
    static final Set<String> SOURCES = Set.of("single", "directory", "filter", "unstitch", "paletted_permutations");
    static final Set<String> FONT_PROVIDERS = Set.of("bitmap", "ttf", "space", "unihex", "reference");
    static final Set<String> SECTIONS = Set.of("animation", "gui", "texture", "villager", "palette");
    static final Set<String> TEXTS = Set.of("credits.json", "end.txt", "postcredits.txt", "splashes.txt");
    /** The 26.3 client's own shader files, under {@code assets/minecraft/shaders/}. */
    static final Set<String> VANILLA_SHADERS = Set.of(("core/animate_sprite.vsh core/animate_sprite_blit.fsh "
            + "core/animate_sprite_interpolate.fsh core/blit_depth.fsh core/blit_screen.fsh core/block.fsh core/block.vsh "
            + "core/clouds.fsh core/clouds.vsh core/debug_point.vsh core/entity.fsh core/entity.vsh core/glint.fsh "
            + "core/glint.vsh core/gui.fsh core/gui.vsh core/integrate_depth.fsh core/item.fsh core/item.vsh "
            + "core/lightmap.fsh core/oit_composite.fsh core/oit_depth_bounds_cull.fsh core/panorama.fsh "
            + "core/panorama.vsh core/particle.fsh core/particle.vsh core/position.fsh core/position.vsh "
            + "core/position_color.fsh core/position_color.vsh core/position_tex.fsh core/position_tex.vsh "
            + "core/position_tex_color.fsh core/position_tex_color.vsh core/rendertype_beacon_beam.fsh "
            + "core/rendertype_beacon_beam.vsh core/rendertype_crumbling.fsh core/rendertype_crumbling.vsh "
            + "core/rendertype_end_portal.fsh core/rendertype_end_portal.vsh core/rendertype_entity_shadow.fsh "
            + "core/rendertype_entity_shadow.vsh core/rendertype_leash.fsh core/rendertype_leash.vsh "
            + "core/rendertype_lightning.fsh core/rendertype_lightning.vsh core/rendertype_lines.fsh "
            + "core/rendertype_lines.vsh core/rendertype_outline.fsh core/rendertype_outline.vsh "
            + "core/rendertype_water_mask.fsh core/rendertype_water_mask.vsh core/screenquad.vsh core/sky.fsh core/sky.vsh "
            + "core/stars.fsh core/stars.vsh core/terrain.fsh core/terrain.vsh core/text.fsh core/text.vsh "
            + "core/world_border.fsh core/world_border.vsh include/animation_sprite.glsl include/chunksection.glsl "
            + "include/dynamictransforms.glsl include/fog.glsl include/globals.glsl include/light.glsl include/matrix.glsl "
            + "include/oit.glsl include/oit_add_transmittance.glsl include/oit_common.glsl include/oit_depth_bounds.glsl "
            + "include/oit_depth_sample.glsl include/oit_sample.glsl include/projection.glsl include/sample_lightmap.glsl "
            + "include/terrainglobals.glsl include/texture_sampling.glsl post/bits.fsh post/blit.fsh post/box_blur.fsh "
            + "post/color_convolve.fsh post/entity_outline_box_blur.fsh post/entity_sobel.fsh post/invert.fsh "
            + "post/rotscale.vsh post/spiderclip.fsh").split(" "));
    private static final Pattern FILE = Pattern.compile("assets/([^/]+)/(.+)");
    private static final Pattern INCLUDE = Pattern.compile("^\\s*#include\\s*[<\"]([^>\"]+)[>\"]", Pattern.MULTILINE);
    private static final Pattern LANG_NAME = Pattern.compile("[a-z]{2,3}_[a-z0-9]{2,3}");

    private final PackLint lint;
    private final Map<String, byte[]> files;
    private final Set<String> models;
    private final Set<String> usedSounds = new HashSet<>();
    /** Pack models a blockstate or item definition shows: their texture slots must all resolve. */
    private final Map<String, String> shown = new java.util.TreeMap<>();

    AssetLint(PackLint lint, Map<String, byte[]> files, Set<String> models) {
        this.lint = lint;
        this.files = files;
        this.models = models;
    }

    void run() {
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Matcher m = FILE.matcher(e.getKey());
            if (!m.matches()) {
                continue;
            }
            String path = e.getKey();
            String ns = m.group(1);
            String rest = m.group(2);
            int slash = rest.indexOf('/');
            String folder = slash < 0 ? rest : rest.substring(0, slash);
            String name = slash < 0 ? "" : rest.substring(slash + 1);
            if (slash < 0) {
                if (!ROOT_FILES.contains(rest)) {
                    lint.add(Severity.WARNING, path, "the game reads no file " + rest + " here; did you mean sounds.json?");
                } else if (rest.equals("sounds.json")) {
                    sounds(path, ns, e.getValue());
                }
                continue;
            }
            if (!FOLDERS.contains(folder)) {
                lint.add(Severity.WARNING, path, "the game reads no " + folder + " folder; folders are "
                        + String.join(", ", FOLDERS.stream().sorted().toList()));
                continue;
            }
            byte[] data = e.getValue();
            switch (folder) {
                case "models" -> {
                    JsonObject model = PackLint.quietJson(data);
                    if (model != null) {
                        model(path, model);
                    }
                }
                case "items" -> {
                    JsonObject item = PackLint.quietJson(data);
                    if (item != null) {
                        item(path, item);
                    }
                }
                case "font" -> font(path, name, data);
                case "lang" -> lang(path, name, data);
                case "blockstates" -> json(path, name, data, j -> blockstate(path, j));
                case "equipment" -> json(path, name, data, j -> equipment(path, j));
                case "atlases" -> json(path, name, data, j -> atlas(path, ns, name, j));
                case "textures" -> {
                    if (name.endsWith(".png.mcmeta")) {
                        mcmeta(path, name, data);
                    }
                }
                case "shaders" -> shader(path, ns, name, data);
                case "post_effect" -> json(path, name, data, j -> postEffect(path, ns, name, j));
                case "particles" -> json(path, name, data, j -> particle(path, ns, j));
                case "waypoint_style" -> json(path, name, data, j -> waypoint(path, j));
                case "texts" -> {
                    if (!TEXTS.contains(name)) {
                        lint.add(Severity.WARNING, path, "the game reads only " + String.join(", ", TEXTS.stream().sorted().toList())
                                + " from texts");
                    } else if (name.endsWith(".json")) {
                        jsonAny(path, data);
                    }
                }
                case "sounds" -> {
                    if (name.endsWith(".ogg")) {
                        ogg(path, data);
                    } else {
                        lint.add(Severity.WARNING, path, "sounds holds .ogg files only; the game plays no other format");
                    }
                }
                default -> {
                }
            }
        }
        resolveShown();
        for (String path : files.keySet()) {
            Matcher m = Pattern.compile("assets/([^/]+)/sounds/(.+)\\.ogg").matcher(path);
            if (m.matches() && !usedSounds.contains(m.group(1) + ":" + m.group(2))) {
                lint.add(Severity.INFO, path, "not played by any event in sounds.json");
            }
        }
    }

    private interface JsonCheck {
        void check(JsonObject json);
    }


    private void json(String path, String name, byte[] data, JsonCheck check) {
        if (!name.endsWith(".json")) {
            lint.add(Severity.WARNING, path, "not a .json file; the game ignores it");
            return;
        }
        JsonObject json = lint.json(path, data);
        if (json != null) {
            check.check(json);
        }
    }

    // ---- models ----

    /** A model's own geometry and display: elements, faces, rotations, display transforms, gui_light. */
    private void model(String path, JsonObject json) {
        json.keySet().stream().filter(k -> !Set.of("parent", "elements", "textures", "display", "ambientocclusion",
                "gui_light", "texture_size", "groups").contains(k)).forEach(k -> lint.add(Severity.WARNING, path, "unknown field " + k));
        if (json.has("gui_light")) {
            String light = lint.string(path, "gui_light", json.get("gui_light"));
            if (light != null && !light.equals("front") && !light.equals("side")) {
                lint.add(Severity.ERROR, path, "gui_light must be front or side");
            }
        }
        if (json.get("display") instanceof JsonObject display) {
            for (Map.Entry<String, JsonElement> d : display.entrySet()) {
                if (!DISPLAY.contains(d.getKey())) {
                    lint.add(Severity.ERROR, path, "display " + d.getKey() + " is not one of " + sorted(DISPLAY));
                } else if (d.getValue() instanceof JsonObject t) {
                    for (String key : t.keySet()) {
                        if (!Set.of("rotation", "translation", "scale").contains(key)) {
                            lint.add(Severity.WARNING, path, "display " + d.getKey() + ": unknown field " + key);
                        } else {
                            vector(path, "display " + d.getKey() + " " + key, t.get(key), -Double.MAX_VALUE, Double.MAX_VALUE);
                        }
                    }
                }
            }
        }
        if (!json.has("elements")) {
            return;
        }
        if (!(json.get("elements") instanceof JsonArray elements)) {
            lint.add(Severity.ERROR, path, "elements must be an array");
            return;
        }
        int i = 0;
        for (JsonElement e : elements) {
            String where = "element " + i++;
            if (!(e instanceof JsonObject el)) {
                lint.add(Severity.ERROR, path, where + " is not an object");
                continue;
            }
            el.keySet().stream().filter(k -> !Set.of("from", "to", "rotation", "shade", "light_emission", "faces",
                    "shade_direction_override", "__comment", "name").contains(k))
                    .forEach(k -> lint.add(Severity.WARNING, path, where + ": unknown field " + k));
            vector(path, where + " from", el.get("from"), -16, 32);
            vector(path, where + " to", el.get("to"), -16, 32);
            if (el.has("light_emission")) {
                double l = number(path, where + " light_emission", el.get("light_emission"));
                if (l < 0 || l > 15 || l != Math.floor(l)) {
                    lint.add(Severity.ERROR, path, where + ": light_emission must be a whole number 0 to 15");
                }
            }
            if (el.get("rotation") instanceof JsonObject r) {
                rotation(path, where, r);
            }
            if (!(el.get("faces") instanceof JsonObject faces) || faces.isEmpty()) {
                lint.add(Severity.ERROR, path, where + " has no faces");
                continue;
            }
            for (Map.Entry<String, JsonElement> f : faces.entrySet()) {
                face(path, where + " " + f.getKey(), f.getKey(), f.getValue());
            }
        }
    }

    /** {@code axis} and {@code angle} (any angle), or {@code x}, {@code y} and {@code z}; always an {@code origin}. */
    private void rotation(String path, String where, JsonObject r) {
        if (r.has("axis") || r.has("angle")) {
            String axis = r.has("axis") ? lint.string(path, where + " axis", r.get("axis")) : null;
            if (axis == null || !Set.of("x", "y", "z").contains(axis)) {
                lint.add(Severity.ERROR, path, where + ": rotation axis must be x, y or z");
            }
            if (!r.has("angle")) {
                lint.add(Severity.ERROR, path, where + ": rotation has no angle");
            } else {
                number(path, where + " angle", r.get("angle"));
            }
        } else if (!(r.has("x") && r.has("y") && r.has("z"))) {
            lint.add(Severity.ERROR, path, where + ": rotation needs axis and angle, or x, y and z");
        }
        if (r.has("origin")) {
            vector(path, where + " rotation origin", r.get("origin"), -Double.MAX_VALUE, Double.MAX_VALUE);
        } else {
            lint.add(Severity.ERROR, path, where + ": rotation has no origin");
        }
    }

    private void face(String path, String where, String side, JsonElement e) {
        if (!FACES.contains(side)) {
            lint.add(Severity.ERROR, path, where + ": faces are " + sorted(FACES));
            return;
        }
        if (!(e instanceof JsonObject f)) {
            lint.add(Severity.ERROR, path, where + " is not an object");
            return;
        }
        String tex = f.has("texture") ? lint.string(path, where + " texture", f.get("texture")) : null;
        if (tex == null) {
            lint.add(Severity.ERROR, path, where + " has no texture");
        }
        if (f.has("uv")) {
            if (!(f.get("uv") instanceof JsonArray uv) || uv.size() != 4) {
                lint.add(Severity.ERROR, path, where + ": uv must be [u1, v1, u2, v2]");
            } else {
                for (JsonElement v : uv) {
                    double n = number(path, where + " uv", v);
                    if (n < 0 || n > 16) {
                        lint.add(Severity.WARNING, path, where + ": uv " + n + " is outside 0 to 16");
                    }
                }
            }
        }
        if (f.has("rotation")) {
            double r = number(path, where + " rotation", f.get("rotation"));
            if (r != 0 && r != 90 && r != 180 && r != 270) {
                lint.add(Severity.ERROR, path, where + ": face rotation must be 0, 90, 180 or 270");
            }
        }
        if (f.has("cullface")) {
            String cull = lint.string(path, where + " cullface", f.get("cullface"));
            if (cull != null && !FACES.contains(cull) && !cull.equals("bottom")) {
                lint.add(Severity.ERROR, path, where + ": cullface must be one of " + sorted(FACES));
            }
        }
    }

    /**
     * Every {@code #slot} a face of a shown model uses must resolve, through its own and its parents' textures, to a
     * texture: a template such as {@code block/cube_all} leaves {@code #all} to its children, so only models a
     * blockstate or item definition shows are checked. A chain that reaches a vanilla parent is not checked (its slots
     * are not known here).
     */
    private void resolveShown() {
        for (Map.Entry<String, String> e : shown.entrySet()) {
            String id = e.getKey();
            Map<String, String> slots = new java.util.HashMap<>();
            JsonArray elements = null;
            String leafPath = modelFile(id);
            boolean known = true;
            Set<String> seen = new HashSet<>();
            for (String at = id; at != null; ) {
                JsonObject m = files.containsKey(modelFile(at)) && seen.add(at) ? PackLint.quietJson(files.get(modelFile(at))) : null;
                if (m == null) {
                    known = false;
                    break;
                }
                if (m.get("textures") instanceof JsonObject t) {
                    for (Map.Entry<String, JsonElement> s : t.entrySet()) {
                        JsonElement v = s.getValue() instanceof JsonObject o && o.has("sprite") ? o.get("sprite") : s.getValue();
                        if (v instanceof JsonPrimitive p && p.isString()) {
                            slots.putIfAbsent(s.getKey(), p.getAsString());
                        }
                    }
                }
                if (elements == null && m.get("elements") instanceof JsonArray els) {
                    elements = els;
                }
                at = m.get("parent") instanceof JsonPrimitive p && p.isString() ? PackLint.qualify(p.getAsString()) : null;
                if (at != null && at.startsWith("minecraft:") && !files.containsKey(modelFile(at))) {
                    known = !at.equals("minecraft:builtin/generated") ? false : known;
                    break;
                }
            }
            if (!known || elements == null) {
                continue;
            }
            Set<String> reported = new HashSet<>();
            for (JsonElement el : elements) {
                if (!(el instanceof JsonObject o) || !(o.get("faces") instanceof JsonObject faces)) {
                    continue;
                }
                for (JsonElement f : faces.asMap().values()) {
                    String tex = f instanceof JsonObject fo && fo.get("texture") instanceof JsonPrimitive p && p.isString()
                            ? p.getAsString() : null;
                    String resolved = tex;
                    for (int depth = 0; resolved != null && resolved.startsWith("#") && depth < 16; depth++) {
                        resolved = slots.get(resolved.substring(1));
                    }
                    if (tex != null && (resolved == null || resolved.startsWith("#")) && reported.add(tex)) {
                        lint.add(Severity.ERROR, leafPath, "texture " + tex + " is set by neither this model nor its "
                                + "parents; those faces show the missing texture (shown by " + e.getValue() + ")");
                    }
                }
            }
        }
    }

    private static String modelFile(String id) {
        return "assets/" + id.substring(0, id.indexOf(':')) + "/models/" + id.substring(id.indexOf(':') + 1) + ".json";
    }

    /** Three numbers from {@code min} to {@code max}. */
    private void vector(String path, String what, JsonElement e, double min, double max) {
        if (!(e instanceof JsonArray a) || a.size() != 3) {
            lint.add(Severity.ERROR, path, what + " must be [x, y, z]");
            return;
        }
        for (JsonElement v : a) {
            double n = number(path, what, v);
            if (n < min || n > max) {
                lint.add(Severity.ERROR, path, what + ": " + n + " is outside " + (int) min + " to " + (int) max);
                return;
            }
        }
    }

    // ---- item definitions ----

    private void item(String path, JsonObject json) {
        json.keySet().stream().filter(k -> !Set.of("model", "hand_animation_on_swap", "oversized_in_gui",
                "swap_animation_scale").contains(k)).forEach(k -> lint.add(Severity.WARNING, path, "unknown field " + k));
        if (json.get("model") instanceof JsonObject model) {
            itemModel(path, "model", model);
        } else if (json.has("model")) {
            lint.add(Severity.ERROR, path, "model must be an object with a type");
        }
    }

    private void itemModel(String path, String where, JsonElement e) {
        if (!(e instanceof JsonObject m)) {
            lint.add(Severity.ERROR, path, where + " is not an object");
            return;
        }
        // A type that is not a string is reported where the model references are collected.
        if (m.has("type") && !(m.get("type") instanceof JsonPrimitive p && p.isString())) {
            return;
        }
        String type = m.has("type") ? m.get("type").getAsString() : null;
        String bare = type == null ? null : type.replaceFirst("^minecraft:", "");
        if (bare == null || !ITEM_MODELS.contains(bare)) {
            lint.add(Severity.ERROR, path, where + ": type " + type + " is not one of " + sorted(ITEM_MODELS));
            return;
        }
        switch (bare) {
            case "model" -> {
                if (!m.has("model")) {
                    lint.add(Severity.ERROR, path, where + " has no model");
                } else if (m.get("model") instanceof JsonPrimitive ref && ref.isString()
                        && models.contains(PackLint.qualify(ref.getAsString()))) {
                    shown.putIfAbsent(PackLint.qualify(ref.getAsString()), path);
                }
                if (m.get("tints") instanceof JsonArray tints) {
                    tints.forEach(t -> kind(path, where + " tint", t, TINTS));
                }
            }
            case "select" -> {
                property(path, where, m, SELECT);
                if (!(m.get("cases") instanceof JsonArray cases)) {
                    lint.add(Severity.ERROR, path, where + ": select has no cases");
                } else {
                    int i = 0;
                    for (JsonElement c : cases) {
                        String w = where + " case " + i++;
                        if (!(c instanceof JsonObject co) || !co.has("when") || !co.has("model")) {
                            lint.add(Severity.ERROR, path, w + " needs when and model");
                        } else {
                            itemModel(path, w, co.get("model"));
                        }
                    }
                }
                if (m.has("fallback")) {
                    itemModel(path, where + " fallback", m.get("fallback"));
                }
            }
            case "condition" -> {
                property(path, where, m, CONDITION);
                for (String branch : List.of("on_true", "on_false")) {
                    if (!m.has(branch)) {
                        lint.add(Severity.ERROR, path, where + ": condition has no " + branch);
                    } else {
                        itemModel(path, where + " " + branch, m.get(branch));
                    }
                }
            }
            case "range_dispatch" -> {
                property(path, where, m, RANGE);
                if (m.get("entries") instanceof JsonArray entries) {
                    int i = 0;
                    for (JsonElement c : entries) {
                        String w = where + " entry " + i++;
                        if (!(c instanceof JsonObject co) || !co.has("threshold") || !co.has("model")) {
                            lint.add(Severity.ERROR, path, w + " needs threshold and model");
                        } else {
                            number(path, w + " threshold", co.get("threshold"));
                            itemModel(path, w, co.get("model"));
                        }
                    }
                }
                if (m.has("fallback")) {
                    itemModel(path, where + " fallback", m.get("fallback"));
                }
            }
            case "composite" -> {
                if (!(m.get("models") instanceof JsonArray list)) {
                    lint.add(Severity.ERROR, path, where + ": composite has no models array");
                } else {
                    int i = 0;
                    for (JsonElement c : list) {
                        itemModel(path, where + " models[" + i++ + "]", c);
                    }
                }
            }
            case "special" -> {
                if (!m.has("base")) {
                    lint.add(Severity.ERROR, path, where + ": special has no base model (for particles and transforms)");
                }
                kind(path, where + " special model", m.get("model"), SPECIAL);
            }
            default -> {
            }
        }
    }

    private void property(String path, String where, JsonObject m, Set<String> allowed) {
        String p = m.has("property") ? lint.string(path, where + " property", m.get("property")) : null;
        if (p == null || !allowed.contains(p.replaceFirst("^minecraft:", ""))) {
            lint.add(Severity.ERROR, path, where + ": property " + p + " is not one of " + sorted(allowed));
        }
    }

    /** An object whose {@code type} is one of {@code allowed}. */
    private void kind(String path, String what, JsonElement e, Set<String> allowed) {
        String type = e instanceof JsonObject o && o.has("type") ? lint.string(path, what + " type", o.get("type")) : null;
        if (type == null || !allowed.contains(type.replaceFirst("^minecraft:", ""))) {
            lint.add(Severity.ERROR, path, what + ": type " + type + " is not one of " + sorted(allowed));
        }
    }

    // ---- fonts ----

    private void font(String path, String name, byte[] data) {
        if (name.endsWith(".ttf") || name.endsWith(".otf")) {
            boolean ttf = data.length >= 4 && ((data[0] == 0 && data[1] == 1 && data[2] == 0 && data[3] == 0)
                    || new String(data, 0, 4, StandardCharsets.ISO_8859_1).equals("true"));
            boolean otf = data.length >= 4 && new String(data, 0, 4, StandardCharsets.ISO_8859_1).equals("OTTO");
            if (!ttf && !otf) {
                lint.add(Severity.ERROR, path, "not a TrueType or OpenType font");
            }
            return;
        }
        if (name.endsWith(".zip")) {
            return;
        }
        JsonObject json = name.endsWith(".json") ? lint.json(path, data) : null;
        if (json == null) {
            if (!name.endsWith(".json")) {
                lint.add(Severity.WARNING, path, "a font folder holds .json definitions, .ttf, .otf and unihex .zip files");
            }
            return;
        }
        if (!(json.get("providers") instanceof JsonArray providers)) {
            lint.add(Severity.ERROR, path, "no \"providers\" array");
            return;
        }
        Set<Integer> seen = new HashSet<>();
        for (JsonElement e : providers) {
            if (!(e instanceof JsonObject p)) {
                lint.add(Severity.ERROR, path, "a provider is not an object");
                continue;
            }
            String type = p.has("type") ? lint.string(path, "provider type", p.get("type")) : null;
            String bare = type == null ? null : type.replaceFirst("^minecraft:", "");
            if (bare == null) {
                lint.add(Severity.ERROR, path, "a provider has no type");
                continue;
            }
            if (!FONT_PROVIDERS.contains(bare)) {
                lint.add(Severity.ERROR, path, "provider type " + type + " is not one of " + sorted(FONT_PROVIDERS));
                continue;
            }
            if (p.get("filter") instanceof JsonObject filter) {
                filter.keySet().stream().filter(k -> !k.equals("uniform") && !k.equals("jp"))
                        .forEach(k -> lint.add(Severity.ERROR, path, "filter option " + k + " is not uniform or jp"));
            }
            switch (bare) {
                case "bitmap" -> bitmap(path, p, seen);
                case "space" -> {
                    if (!(p.get("advances") instanceof JsonObject advances)) {
                        lint.add(Severity.ERROR, path, "space provider has no \"advances\" object");
                    } else {
                        advances.entrySet().forEach(a -> {
                            if (a.getKey().codePointCount(0, a.getKey().length()) != 1) {
                                lint.add(Severity.ERROR, path, "space advance key \"" + a.getKey() + "\" is not one character");
                            } else if (!seen.add(a.getKey().codePointAt(0))) {
                                lint.add(Severity.WARNING, path, dupe(a.getKey().codePointAt(0)));
                            }
                            number(path, "advance", a.getValue());
                        });
                    }
                }
                case "ttf" -> {
                    String file = p.has("file") ? lint.string(path, "file", p.get("file")) : null;
                    if (file == null) {
                        lint.add(Severity.ERROR, path, "ttf provider has no file");
                    } else {
                        reference(path, "font file " + PackLint.qualify(file), "font/", PackLint.qualify(file), "");
                    }
                    if (p.has("size") && number(path, "size", p.get("size")) <= 0) {
                        lint.add(Severity.ERROR, path, "ttf size must be above 0");
                    }
                }
                case "unihex" -> {
                    String file = p.has("hex_file") ? lint.string(path, "hex_file", p.get("hex_file")) : null;
                    if (file == null) {
                        lint.add(Severity.ERROR, path, "unihex provider has no hex_file");
                    } else {
                        String id = PackLint.qualify(file);
                        reference(path, "hex_file " + id, "", id, "");
                    }
                }
                case "reference" -> {
                    String id = p.has("id") ? lint.string(path, "id", p.get("id")) : null;
                    if (id == null) {
                        lint.add(Severity.ERROR, path, "reference provider has no id");
                    } else {
                        reference(path, "font " + PackLint.qualify(id), "font/", PackLint.qualify(id), ".json");
                    }
                }
                default -> {
                }
            }
        }
    }

    private void bitmap(String path, JsonObject p, Set<Integer> seen) {
        String file = p.has("file") ? lint.string(path, "file", p.get("file")) : null;
        if (file == null) {
            lint.add(Severity.ERROR, path, "bitmap provider has no file");
            return;
        }
        String id = PackLint.qualify(file);
        String texture = id.endsWith(".png") ? id.substring(0, id.length() - 4) : null;
        if (texture == null) {
            lint.add(Severity.ERROR, path, "bitmap file " + id + " must end in .png");
            return;
        }
        int height = p.has("height") ? (int) number(path, "height", p.get("height")) : 8;
        if (!p.has("ascent")) {
            lint.add(Severity.ERROR, path, "bitmap " + id + " has no ascent");
        } else if (number(path, "ascent", p.get("ascent")) > height) {
            lint.add(Severity.ERROR, path, "bitmap " + id + ": ascent is higher than height " + height);
        }
        if (!(p.get("chars") instanceof JsonArray rows) || rows.isEmpty()) {
            lint.add(Severity.ERROR, path, "bitmap " + id + " has no chars");
            return;
        }
        int columns = -1;
        for (JsonElement r : rows) {
            String row = lint.string(path, "chars row", r);
            if (row == null) {
                return;
            }
            int count = row.codePointCount(0, row.length());
            if (columns >= 0 && count != columns) {
                lint.add(Severity.ERROR, path, "bitmap " + id + ": chars rows differ in length (" + columns + " and " + count
                        + "); pad with \\u0000");
                return;
            }
            columns = count;
            row.codePoints().filter(c -> c != 0).forEach(c -> {
                if (!seen.add(c)) {
                    lint.add(Severity.WARNING, path, dupe(c));
                }
            });
        }
        String png = "assets/" + texture.substring(0, texture.indexOf(':')) + "/textures/" + texture.substring(texture.indexOf(':') + 1) + ".png";
        if (!files.containsKey(png)) {
            if (!texture.startsWith("minecraft:")) {
                lint.add(Severity.ERROR, path, "bitmap file " + id + " is not in the pack");
            }
            return;
        }
        lint.used(texture);
        try {
            Texture t = Texture.readAnyPng(files.get(png));
            if (t.width() % columns != 0 || t.height() % rows.size() != 0) {
                lint.add(Severity.ERROR, path, "bitmap " + id + " is " + t.width() + "x" + t.height() + ", not a grid of "
                        + columns + " by " + rows.size() + " equal cells");
            }
        } catch (TextureException ignored) {
            // the texture's own check reports it
        }
    }

    private static String dupe(int c) {
        return String.format("char \\u%04X is mapped twice in this font; the first provider wins", c);
    }

    // ---- sounds ----

    private void sounds(String path, String ns, byte[] data) {
        JsonObject json = lint.json(path, data);
        if (json == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> event : json.entrySet()) {
            String where = "event " + event.getKey();
            if (!event.getKey().matches("[a-z0-9_.-]+")) {
                lint.add(Severity.ERROR, path, where + ": event names are lowercase a-z, 0-9, _ . - only");
            }
            if (!(event.getValue() instanceof JsonObject entry)) {
                lint.add(Severity.ERROR, path, where + " is not an object");
                continue;
            }
            for (String key : entry.keySet()) {
                if (!Set.of("sounds", "replace", "subtitle").contains(key)) {
                    lint.add(Severity.WARNING, path, where + ": unknown field " + key + " (sounds, replace, subtitle)");
                }
            }
            if (!(entry.get("sounds") instanceof JsonArray list) || list.isEmpty()) {
                lint.add(Severity.WARNING, path, where + " has no sounds; it plays nothing");
                continue;
            }
            for (JsonElement s : list) {
                String name;
                boolean isEvent = false;
                if (s instanceof JsonPrimitive prim && prim.isString()) {
                    name = s.getAsString();
                } else if (s instanceof JsonObject o) {
                    name = o.has("name") ? lint.string(path, where + " name", o.get("name")) : null;
                    if (name == null) {
                        lint.add(Severity.ERROR, path, where + ": a sound has no name");
                        continue;
                    }
                    for (String key : o.keySet()) {
                        if (!Set.of("name", "volume", "pitch", "weight", "stream", "attenuation_distance", "preload", "type")
                                .contains(key)) {
                            lint.add(Severity.WARNING, path, where + ": unknown sound field " + key);
                        }
                    }
                    range(path, where + " volume", o.get("volume"), 0, 1);
                    if (o.has("pitch") && number(path, where + " pitch", o.get("pitch")) <= 0) {
                        lint.add(Severity.ERROR, path, where + ": pitch must be above 0");
                    }
                    if (o.has("weight") && number(path, where + " weight", o.get("weight")) < 1) {
                        lint.add(Severity.ERROR, path, where + ": weight must be at least 1");
                    }
                    if (o.has("type")) {
                        String type = lint.string(path, where + " type", o.get("type"));
                        if (type != null && !type.equals("file") && !type.equals("event")) {
                            lint.add(Severity.ERROR, path, where + ": type must be file or event");
                        }
                        isEvent = "event".equals(type);
                    }
                } else {
                    lint.add(Severity.ERROR, path, where + ": a sound must be a name or an object");
                    continue;
                }
                String id = name.contains(":") ? name : ns + ":" + name;
                if (isEvent) {
                    continue;
                }
                usedSounds.add(id);
                String ogg = "assets/" + id.substring(0, id.indexOf(':')) + "/sounds/" + id.substring(id.indexOf(':') + 1) + ".ogg";
                if (!id.startsWith("minecraft:") && !files.containsKey(ogg)) {
                    lint.add(Severity.ERROR, path, where + ": sound " + id + " is not in the pack");
                }
            }
        }
    }

    /** Ogg Vorbis, the only audio the game plays; a stereo file has no position or distance falloff. */
    private void ogg(String path, byte[] data) {
        if (data.length < 28 || !new String(data, 0, 4, StandardCharsets.ISO_8859_1).equals("OggS")) {
            lint.add(Severity.ERROR, path, "not an Ogg file; the game plays Ogg Vorbis only");
            return;
        }
        int at = 27 + (data[26] & 0xff);
        if (data.length < at + 12 || data[at] != 1
                || !new String(data, at + 1, 6, StandardCharsets.ISO_8859_1).equals("vorbis")) {
            lint.add(Severity.ERROR, path, "an Ogg file but not Vorbis (Opus or FLAC?); the game plays Ogg Vorbis only");
            return;
        }
        int channels = data[at + 11] & 0xff;
        if (channels > 1) {
            lint.add(Severity.WARNING, path, channels + " channels: a stereo sound plays at full volume everywhere, "
                    + "with no direction or distance falloff; use mono for sounds in the world (stereo is fine for music)");
        }
    }

    // ---- lang ----

    /** Any JSON value (credits.json is an array). */
    private void jsonAny(String path, byte[] data) {
        try {
            com.google.gson.JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
        } catch (com.google.gson.JsonParseException e) {
            lint.add(Severity.ERROR, path, "invalid JSON");
        }
    }

    private void lang(String path, String name, byte[] data) {
        if (name.equals("deprecated.json")) {
            return;
        }
        if (!name.endsWith(".json")) {
            lint.add(Severity.WARNING, path, "lang holds <code>.json files only");
            return;
        }
        String code = name.substring(0, name.length() - 5);
        if (!LANG_NAME.matcher(code).matches()) {
            lint.add(Severity.WARNING, path, code + " is not a language code such as en_us");
        }
        JsonObject json = lint.json(path, data);
        if (json == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> e : json.entrySet()) {
            if (!(e.getValue() instanceof JsonPrimitive p && p.isString())) {
                lint.add(Severity.ERROR, path, "key " + e.getKey() + ": a translation must be a string");
            }
        }
        String english = path.substring(0, path.lastIndexOf('/') + 1) + "en_us.json";
        if (!code.equals("en_us") && !files.containsKey(english)) {
            lint.add(Severity.WARNING, path, "no en_us.json beside it; players on other languages fall back to en_us and "
                    + "see raw keys");
        }
    }

    // ---- blockstates ----

    private void blockstate(String path, JsonObject json) {
        boolean variants = json.has("variants");
        boolean multipart = json.has("multipart");
        if (variants == multipart) {
            lint.add(Severity.ERROR, path, variants ? "has both variants and multipart; give one" : "has neither variants nor multipart");
            return;
        }
        if (variants) {
            if (!(json.get("variants") instanceof JsonObject all)) {
                lint.add(Severity.ERROR, path, "variants must be an object of \"prop=value,...\": model");
                return;
            }
            all.entrySet().forEach(v -> variantList(path, "variant \"" + v.getKey() + "\"", v.getValue()));
            return;
        }
        if (!(json.get("multipart") instanceof JsonArray parts)) {
            lint.add(Severity.ERROR, path, "multipart must be an array of { when, apply }");
            return;
        }
        int i = 0;
        for (JsonElement e : parts) {
            String where = "multipart[" + i++ + "]";
            if (!(e instanceof JsonObject part) || !part.has("apply")) {
                lint.add(Severity.ERROR, path, where + " has no apply");
                continue;
            }
            variantList(path, where, part.get("apply"));
            if (part.has("when") && !(part.get("when") instanceof JsonObject)) {
                lint.add(Severity.ERROR, path, where + ": when must be an object of properties, or {\"OR\": [...]} or {\"AND\": [...]}");
            }
        }
    }

    private void variantList(String path, String where, JsonElement e) {
        if (e instanceof JsonArray list) {
            if (list.isEmpty()) {
                lint.add(Severity.ERROR, path, where + " is an empty list");
            }
            list.forEach(v -> variant(path, where, v));
        } else {
            variant(path, where, e);
        }
    }

    private void variant(String path, String where, JsonElement e) {
        if (!(e instanceof JsonObject v)) {
            lint.add(Severity.ERROR, path, where + " is not an object");
            return;
        }
        String model = v.has("model") ? lint.string(path, where + " model", v.get("model")) : null;
        if (model == null) {
            lint.add(Severity.ERROR, path, where + " has no model");
        } else if (models.contains(PackLint.qualify(model))) {
            shown.putIfAbsent(PackLint.qualify(model), path);
        } else if (!PackLint.qualify(model).startsWith("minecraft:")) {
            lint.add(Severity.ERROR, path, where + ": model " + PackLint.qualify(model) + " is not in the pack");
        }
        for (String axis : List.of("x", "y", "z")) {
            if (v.has(axis)) {
                double deg = number(path, where + " " + axis, v.get(axis));
                if (deg != 0 && deg != 90 && deg != 180 && deg != 270) {
                    lint.add(Severity.ERROR, path, where + ": " + axis + " must be 0, 90, 180 or 270");
                }
            }
        }
        if (v.has("weight") && number(path, where + " weight", v.get("weight")) < 1) {
            lint.add(Severity.ERROR, path, where + ": weight must be at least 1");
        }
        v.keySet().stream().filter(k -> !Set.of("model", "x", "y", "z", "uvlock", "weight").contains(k))
                .forEach(k -> lint.add(Severity.WARNING, path, where + ": unknown field " + k));
    }

    // ---- equipment ----

    private void equipment(String path, JsonObject json) {
        json.keySet().stream().filter(k -> !k.equals("layers") && !k.equals("trim_overrides"))
                .forEach(k -> lint.add(Severity.WARNING, path, "unknown field " + k + " (layers, trim_overrides)"));
        if (!(json.get("layers") instanceof JsonObject layers)) {
            lint.add(Severity.ERROR, path, "no \"layers\" object");
            return;
        }
        for (Map.Entry<String, JsonElement> layer : layers.entrySet()) {
            if (!EQUIPMENT_LAYERS.contains(layer.getKey())) {
                lint.add(Severity.ERROR, path, "layer type " + layer.getKey() + " is not one of " + sorted(EQUIPMENT_LAYERS));
                continue;
            }
            if (!(layer.getValue() instanceof JsonArray list)) {
                lint.add(Severity.ERROR, path, layer.getKey() + " must be an array of layers");
                continue;
            }
            for (JsonElement e : list) {
                if (!(e instanceof JsonObject l) || !l.has("texture")) {
                    lint.add(Severity.ERROR, path, layer.getKey() + ": a layer has no texture");
                    continue;
                }
                String tex = lint.string(path, "texture", l.get("texture"));
                if (tex != null) {
                    String id = PackLint.qualify(tex);
                    texture(path, layer.getKey() + " texture " + id,
                            id.replaceFirst(":", ":entity/equipment/" + layer.getKey() + "/"));
                }
                if (l.has("dyeable") && !(l.get("dyeable") instanceof JsonObject)) {
                    lint.add(Severity.ERROR, path, layer.getKey() + ": dyeable must be an object, such as {} or "
                            + "{\"color_when_undyed\": -6265536}");
                }
            }
        }
        if (json.has("trim_overrides") && !(json.get("trim_overrides") instanceof JsonArray)) {
            lint.add(Severity.ERROR, path, "trim_overrides must be an array");
        }
    }

    // ---- atlases ----

    private void atlas(String path, String ns, String name, JsonObject json) {
        String atlas = name.substring(0, name.length() - 5);
        if (!ns.equals("minecraft") || !ATLASES.contains(atlas)) {
            lint.add(Severity.WARNING, path, "the game builds only the minecraft atlases " + sorted(ATLASES)
                    + "; sources here are never stitched");
        }
        if (!(json.get("sources") instanceof JsonArray sources)) {
            lint.add(Severity.ERROR, path, "no \"sources\" array");
            return;
        }
        for (JsonElement e : sources) {
            if (!(e instanceof JsonObject s) || !s.has("type")) {
                lint.add(Severity.ERROR, path, "a source has no type");
                continue;
            }
            String type = lint.string(path, "source type", s.get("type"));
            String bare = type == null ? "" : type.replaceFirst("^minecraft:", "");
            switch (bare) {
                case "directory" -> {
                    if (!s.has("source")) {
                        lint.add(Severity.ERROR, path, "directory source has no source folder");
                    }
                }
                case "single", "unstitch" -> {
                    String res = s.has("resource") ? lint.string(path, "resource", s.get("resource")) : null;
                    if (res == null) {
                        lint.add(Severity.ERROR, path, bare + " source has no resource");
                    } else {
                        texture(path, "resource " + PackLint.qualify(res), PackLint.qualify(res));
                    }
                    if (bare.equals("unstitch") && !(s.get("regions") instanceof JsonArray)) {
                        lint.add(Severity.ERROR, path, "unstitch source has no regions");
                    }
                }
                case "filter" -> {
                    if (!(s.get("pattern") instanceof JsonObject)) {
                        lint.add(Severity.ERROR, path, "filter source has no pattern { namespace, path }");
                    }
                }
                case "paletted_permutations" -> {
                    if (!(s.get("textures") instanceof JsonArray) || !s.has("palette_key")
                            || !(s.get("permutations") instanceof JsonObject)) {
                        lint.add(Severity.ERROR, path, "paletted_permutations needs textures, palette_key and permutations");
                    }
                }
                default -> lint.add(Severity.ERROR, path, "source type " + type + " is not one of " + sorted(SOURCES));
            }
        }
    }

    // ---- texture metadata ----

    private void mcmeta(String path, String name, byte[] data) {
        JsonObject json = lint.json(path, data);
        if (json == null) {
            return;
        }
        String png = path.substring(0, path.length() - ".mcmeta".length());
        String texPath = name.substring(0, name.length() - ".png.mcmeta".length());
        Texture t = null;
        if (files.containsKey(png)) {
            try {
                t = Texture.readAnyPng(files.get(png));
            } catch (TextureException ignored) {
                // the texture's own check reports it
            }
        }
        for (Map.Entry<String, JsonElement> section : json.entrySet()) {
            if (!SECTIONS.contains(section.getKey())) {
                lint.add(Severity.WARNING, path, "unknown section " + section.getKey() + " (" + sorted(SECTIONS) + ")");
            } else if (!(section.getValue() instanceof JsonObject)) {
                lint.add(Severity.ERROR, path, section.getKey() + " must be an object");
            }
        }
        if (json.get("animation") instanceof JsonObject a) {
            animation(path, a, t);
        }
        if (json.get("gui") instanceof JsonObject gui) {
            if (!texPath.startsWith("gui/sprites/") && !texPath.startsWith("mob_effect/")) {
                lint.add(Severity.WARNING, path, "gui scaling is read only for sprites in textures/gui/sprites/; this "
                        + "texture is not on the gui atlas");
            }
            if (!(gui.get("scaling") instanceof JsonObject scaling) || !scaling.has("type")) {
                lint.add(Severity.ERROR, path, "gui needs scaling { type: stretch, tile or nine_slice }");
            } else {
                scaling(path, scaling, t);
            }
        }
        if (json.get("texture") instanceof JsonObject tex) {
            if (tex.has("mipmap_strategy")) {
                String s = lint.string(path, "mipmap_strategy", tex.get("mipmap_strategy"));
                if (s != null && !Set.of("auto", "mean", "cutout", "strict_cutout", "dark_cutout").contains(s)) {
                    lint.add(Severity.ERROR, path, "mipmap_strategy must be auto, mean, cutout, strict_cutout or dark_cutout");
                }
            }
            tex.keySet().stream().filter(k -> !Set.of("blur", "clamp", "mipmap_strategy", "alpha_cutoff_bias").contains(k))
                    .forEach(k -> lint.add(Severity.WARNING, path, "texture: unknown field " + k));
        }
        if (json.get("villager") instanceof JsonObject v && v.has("hat")) {
            String hat = lint.string(path, "hat", v.get("hat"));
            if (hat != null && !Set.of("none", "partial", "full").contains(hat)) {
                lint.add(Severity.ERROR, path, "villager hat must be none, partial or full");
            }
        }
    }

    private void animation(String path, JsonObject a, Texture t) {
        a.keySet().stream().filter(k -> !Set.of("frametime", "interpolate", "frames", "width", "height").contains(k))
                .forEach(k -> lint.add(Severity.WARNING, path, "animation: unknown field " + k));
        if (a.has("frametime") && number(path, "frametime", a.get("frametime")) < 1) {
            lint.add(Severity.ERROR, path, "frametime must be at least 1 tick");
        }
        int count = Integer.MAX_VALUE;
        if (t != null) {
            int fw = a.has("width") ? (int) number(path, "width", a.get("width")) : -1;
            int fh = a.has("height") ? (int) number(path, "height", a.get("height")) : -1;
            if (fw < 0 && fh < 0) {
                fw = Math.min(t.width(), t.height());
                fh = fw;
            } else if (fw < 0) {
                fw = t.width();
            } else if (fh < 0) {
                fh = t.height();
            }
            if (fw < 1 || fh < 1 || t.width() % fw != 0 || t.height() % fh != 0) {
                lint.add(Severity.ERROR, path, t.width() + "x" + t.height() + " is not a grid of " + fw + "x" + fh
                        + " frames");
                return;
            }
            count = (t.width() / fw) * (t.height() / fh);
        }
        if (!a.has("frames")) {
            return;
        }
        if (!(a.get("frames") instanceof JsonArray frames) || frames.isEmpty()) {
            lint.add(Severity.ERROR, path, "frames must be a non-empty array of indices or { index, time }");
            return;
        }
        for (JsonElement f : frames) {
            JsonElement index = f instanceof JsonObject o ? o.get("index") : f;
            if (index == null) {
                lint.add(Severity.ERROR, path, "a frame has no index");
                continue;
            }
            double i = number(path, "frame index", index);
            if (i < 0 || i >= count) {
                lint.add(Severity.ERROR, path, "frame index " + (int) i + " is past the last frame " + (count - 1));
            }
            if (f instanceof JsonObject o && o.has("time") && number(path, "frame time", o.get("time")) < 1) {
                lint.add(Severity.ERROR, path, "a frame time must be at least 1 tick");
            }
        }
    }

    private void scaling(String path, JsonObject s, Texture t) {
        String type = lint.string(path, "scaling type", s.get("type"));
        if (type == null || type.equals("stretch")) {
            return;
        }
        if (!type.equals("tile") && !type.equals("nine_slice")) {
            lint.add(Severity.ERROR, path, "scaling type " + type + " is not stretch, tile or nine_slice");
            return;
        }
        if (!s.has("width") || !s.has("height")) {
            lint.add(Severity.ERROR, path, type + " needs width and height, the sprite's size in GUI pixels");
            return;
        }
        int w = (int) number(path, "width", s.get("width"));
        int h = (int) number(path, "height", s.get("height"));
        if (w < 1 || h < 1) {
            lint.add(Severity.ERROR, path, type + " width and height must be at least 1");
        }
        if (type.equals("nine_slice")) {
            JsonElement b = s.get("border");
            int[] sides;
            if (b instanceof JsonObject o) {
                sides = new int[] {side(path, o, "left"), side(path, o, "top"), side(path, o, "right"), side(path, o, "bottom")};
            } else if (b != null) {
                int all = (int) number(path, "border", b);
                sides = new int[] {all, all, all, all};
            } else {
                lint.add(Severity.ERROR, path, "nine_slice needs border: a number or { left, top, right, bottom }");
                return;
            }
            if (sides[0] + sides[2] >= w || sides[1] + sides[3] >= h) {
                lint.add(Severity.ERROR, path, "nine_slice borders leave no centre in " + w + "x" + h
                        + ": left+right and top+bottom must be less than width and height");
            }
        }
        if (t != null && (t.width() != w || t.height() != h)) {
            lint.add(Severity.INFO, path, "the png is " + t.width() + "x" + t.height() + " but its scaling says " + w + "x"
                    + h + "; usually they match");
        }
    }

    private int side(String path, JsonObject border, String side) {
        if (!border.has(side)) {
            lint.add(Severity.ERROR, path, "border has no " + side);
            return 0;
        }
        return (int) number(path, "border " + side, border.get(side));
    }

    // ---- shaders ----

    private void shader(String path, String ns, String name, byte[] data) {
        String ext = name.substring(name.lastIndexOf('.') + 1);
        if (!Set.of("vsh", "fsh", "glsl").contains(ext)) {
            lint.add(Severity.WARNING, path, "shaders holds .vsh, .fsh and .glsl files; the game ignores this one");
            return;
        }
        String src = new String(data, StandardCharsets.UTF_8);
        if (!ext.equals("glsl") && src.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("//"))
                .findFirst().map(l -> !l.startsWith("#version")).orElse(true)) {
            lint.add(Severity.ERROR, path, "the first line must be #version (vanilla uses #version 330)");
        }
        if (src.contains("#moj_import")) {
            lint.add(Severity.ERROR, path, "#moj_import is gone: Minecraft " + PackSource.MINECRAFT
                    + " uses #include <minecraft:fog.glsl>");
        }
        Matcher m = INCLUDE.matcher(src);
        while (m.find()) {
            String id = m.group(1).contains(":") ? m.group(1) : ns + ":" + m.group(1);
            String file = id.substring(id.indexOf(':') + 1);
            String inPack = "assets/" + id.substring(0, id.indexOf(':')) + "/shaders/include/" + file;
            if (!files.containsKey(inPack) && !(id.startsWith("minecraft:") && VANILLA_SHADERS.contains("include/" + file))) {
                lint.add(Severity.ERROR, path, "include " + id + " is not in the pack");
            }
        }
        long open = src.chars().filter(c -> c == '{').count();
        long close = src.chars().filter(c -> c == '}').count();
        if (open != close) {
            lint.add(Severity.WARNING, path, open + " { but " + close + " }: the shader will not compile");
        }
        if (name.startsWith("core/") && (!ns.equals("minecraft") || !VANILLA_SHADERS.contains(name))) {
            lint.add(Severity.WARNING, path, "Minecraft " + PackSource.MINECRAFT + " has no core shader minecraft:" + name
                    + "; a core shader only replaces the vanilla one of the same name");
        }
    }

    // ---- post effects ----

    private void postEffect(String path, String ns, String name, JsonObject json) {
        String effect = name.substring(0, name.length() - 5);
        if (!ns.equals("minecraft") || !Set.of("blur", "creeper", "entity_outline", "invert", "spider").contains(effect)) {
            lint.add(Severity.INFO, path, "the game applies only minecraft:blur, creeper, entity_outline, invert and "
                    + "spider; a new effect needs a mod or a replaced vanilla one");
        }
        Set<String> targets = new HashSet<>(Set.of("minecraft:main", "minecraft:entity_outline", "minecraft:translucent",
                "minecraft:item_entity", "minecraft:particles", "minecraft:weather", "minecraft:clouds"));
        if (json.get("targets") instanceof JsonObject t) {
            targets.addAll(t.keySet());
        }
        if (!(json.get("passes") instanceof JsonArray passes) || passes.isEmpty()) {
            lint.add(Severity.ERROR, path, "no \"passes\" array");
            return;
        }
        int i = 0;
        for (JsonElement e : passes) {
            String where = "pass " + i++;
            if (!(e instanceof JsonObject p)) {
                lint.add(Severity.ERROR, path, where + " is not an object");
                continue;
            }
            for (String[] shader : new String[][] {{"vertex_shader", ".vsh"}, {"fragment_shader", ".fsh"}}) {
                String ref = p.has(shader[0]) ? lint.string(path, shader[0], p.get(shader[0])) : null;
                if (ref == null) {
                    lint.add(Severity.ERROR, path, where + " has no " + shader[0]);
                    continue;
                }
                String id = PackLint.qualify(ref);
                String file = id.substring(id.indexOf(':') + 1) + shader[1];
                if (!files.containsKey("assets/" + id.substring(0, id.indexOf(':')) + "/shaders/" + file)
                        && !(id.startsWith("minecraft:") && VANILLA_SHADERS.contains(file))) {
                    lint.add(Severity.ERROR, path, where + ": " + shader[0] + " " + id + " is not in the pack");
                }
            }
            Set<String> samplers = new HashSet<>();
            if (p.get("inputs") instanceof JsonArray inputs) {
                for (JsonElement in : inputs) {
                    String sampler = in instanceof JsonObject o && o.has("sampler_name")
                            ? lint.string(path, "sampler_name", o.get("sampler_name")) : null;
                    if (sampler == null) {
                        lint.add(Severity.ERROR, path, where + ": an input has no sampler_name");
                        continue;
                    }
                    JsonObject o = in.getAsJsonObject();
                    if (!samplers.add(sampler)) {
                        lint.add(Severity.ERROR, path, where + ": sampler " + sampler + " is given twice");
                    }
                    if (o.has("target")) {
                        targetRef(path, where, o.get("target"), targets);
                    } else if (o.has("location")) {
                        String loc = lint.string(path, "location", o.get("location"));
                        if (loc != null) {
                            texture(path, where + " texture " + PackLint.qualify(loc), PackLint.qualify(loc));
                        }
                        if (!o.has("width") || !o.has("height")) {
                            lint.add(Severity.ERROR, path, where + ": a texture input needs width and height");
                        }
                    } else {
                        lint.add(Severity.ERROR, path, where + ": an input needs a target or a location");
                    }
                }
            }
            if (!p.has("output")) {
                lint.add(Severity.ERROR, path, where + " has no output");
            } else {
                targetRef(path, where, p.get("output"), targets);
            }
        }
    }

    private void targetRef(String path, String where, JsonElement e, Set<String> targets) {
        String t = lint.string(path, "target", e);
        if (t != null && !targets.contains(t) && !targets.contains(PackLint.qualify(t))) {
            lint.add(Severity.ERROR, path, where + ": target " + t + " is not declared in targets");
        }
    }

    // ---- particles and waypoints ----

    private void particle(String path, String ns, JsonObject json) {
        if (!(json.get("textures") instanceof JsonArray list) || list.isEmpty()) {
            lint.add(Severity.ERROR, path, "no \"textures\" array");
            return;
        }
        for (JsonElement e : list) {
            String tex = lint.string(path, "texture", e);
            if (tex != null) {
                String id = PackLint.qualify(tex);
                texture(path, "texture " + id, id.replaceFirst(":", ":particle/"));
            }
        }
        if (!ns.equals("minecraft")) {
            lint.add(Severity.INFO, path, "only particle types the game knows read this; a new particle type needs a mod");
        }
    }

    private void waypoint(String path, JsonObject json) {
        if (!(json.get("sprites") instanceof JsonArray list) || list.isEmpty()) {
            lint.add(Severity.ERROR, path, "needs at least one sprite in \"sprites\"");
            return;
        }
        for (JsonElement e : list) {
            String tex = lint.string(path, "sprite", e);
            if (tex != null) {
                String id = PackLint.qualify(tex);
                texture(path, "sprite " + id, id.replaceFirst(":", ":gui/sprites/hud/locator_bar_dot/"));
            }
        }
        double near = json.has("near_distance") ? number(path, "near_distance", json.get("near_distance")) : 128;
        double far = json.has("far_distance") ? number(path, "far_distance", json.get("far_distance")) : 332;
        if (near <= 0) {
            lint.add(Severity.ERROR, path, "near_distance must be above 0");
        } else if (far <= near) {
            lint.add(Severity.ERROR, path, "far_distance must be beyond near_distance");
        }
    }

    // ---- helpers ----

    /** A texture id ({@code ns:path}, no .png): an ERROR when the pack lacks it, unless it is vanilla. */
    private void texture(String path, String what, String id) {
        lint.used(id);
        String png = "assets/" + id.substring(0, id.indexOf(':')) + "/textures/" + id.substring(id.indexOf(':') + 1) + ".png";
        if (!id.startsWith("minecraft:") && !files.containsKey(png)) {
            lint.add(Severity.ERROR, path, what + " is not in the pack");
        }
    }

    /** Any file {@code assets/<ns>/<folder><path><ext>}: an ERROR when the pack lacks it, unless it is vanilla. */
    private void reference(String path, String what, String folder, String id, String ext) {
        String file = "assets/" + id.substring(0, id.indexOf(':')) + "/" + folder + id.substring(id.indexOf(':') + 1) + ext;
        if (!id.startsWith("minecraft:") && !files.containsKey(file)) {
            lint.add(Severity.ERROR, path, what + " is not in the pack");
        }
    }

    /** The number, or an ERROR and NaN when the value is not one. */
    private double number(String path, String what, JsonElement e) {
        if (e instanceof JsonPrimitive p && p.isNumber()) {
            return p.getAsDouble();
        }
        lint.add(Severity.ERROR, path, what + " must be a number");
        return Double.NaN;
    }

    private void range(String path, String what, JsonElement e, double min, double max) {
        if (e != null) {
            double v = number(path, what, e);
            if (v < min || v > max) {
                lint.add(Severity.WARNING, path, what + " " + v + " is outside " + min + " to " + max);
            }
        }
    }

    static String sorted(Set<String> names) {
        return String.join(", ", names.stream().sorted().toList());
    }
}
