package io.github.imnotstable.msc.textures;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every resource pack file other than a drawn texture (ADR 0079): where each type lives in the pack, and how a write
 * merges into what is already there (a lang file gains keys, sounds.json gains events, an atlas gains sources, a font
 * gains glyphs, a texture's .png.mcmeta gains sections). {@link #plan} only computes the files; nothing is written until
 * the caller has checked them with {@link PackLint#checkFiles}.
 */
public final class PackAssets {
    /** What a type reads: a JSON object, source text, or a file copied from the workspace. */
    public enum Input { JSON, TEXT, FROM }

    /** One kind of pack file. */
    public enum Type {
        /** {@code font/<name>.json}: a whole font definition, its providers. */
        FONT("font", Set.of(Input.JSON)),
        /** One bitmap glyph added to {@code font/<name>.json}; with no {@code chars}, the next free private-use char. */
        GLYPH("font", Set.of(Input.JSON)),
        /** An event in {@code sounds.json}: {@code json} fields merged, {@code from} an .ogg added to its sounds. */
        SOUND("sounds", Set.of(Input.JSON, Input.FROM)),
        /** {@code lang/<name>.json}: keys merged into the file. */
        LANG("lang", Set.of(Input.JSON)),
        /** {@code models/<name>.json}: a block or item model, parent-based or with its own elements. */
        MODEL("models", Set.of(Input.JSON)),
        /** {@code items/<name>.json}: an item definition (model, select, condition, range_dispatch, composite...). */
        ITEM("items", Set.of(Input.JSON)),
        /** {@code blockstates/<name>.json}: variants or multipart. */
        BLOCKSTATE("blockstates", Set.of(Input.JSON)),
        /** {@code equipment/<name>.json}: worn armour, wings, saddles and body armour layers. */
        EQUIPMENT("equipment", Set.of(Input.JSON)),
        /** {@code atlases/<name>.json}: sources appended to the atlas. */
        ATLAS("atlases", Set.of(Input.JSON)),
        /** {@code textures/<name>.png.mcmeta}: the given sections (animation, gui, texture, villager) replace theirs. */
        MCMETA("textures", Set.of(Input.JSON)),
        /** {@code shaders/<name>}: a .vsh, .fsh or .glsl source. */
        SHADER("shaders", Set.of(Input.TEXT)),
        /** {@code post_effect/<name>.json}: a post-processing chain. */
        POST_EFFECT("post_effect", Set.of(Input.JSON)),
        /** {@code particles/<name>.json}: the textures a particle cycles through. */
        PARTICLE("particles", Set.of(Input.JSON)),
        /** {@code waypoint_style/<name>.json}: locator bar sprites. */
        WAYPOINT_STYLE("waypoint_style", Set.of(Input.JSON)),
        /** {@code texts/<name>}: splashes.txt, credits.json, end.txt, postcredits.txt. */
        TEXT("texts", Set.of(Input.TEXT)),
        /** A binary file copied into the pack: a .ttf or .otf font, a unihex .zip, an .ogg or a .png. */
        FILE("", Set.of(Input.FROM));

        private final String folder;
        private final Set<Input> inputs;

        Type(String folder, Set<Input> inputs) {
            this.folder = folder;
            this.inputs = inputs;
        }

        public Set<Input> inputs() {
            return inputs;
        }

        /** {@code font}, {@code post_effect} and so on. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Type parse(String text) {
            for (Type t : values()) {
                if (t.id().equals(text.trim().toLowerCase(Locale.ROOT))) {
                    return t;
                }
            }
            throw new TextureException("unknown type \"" + text + "\"; use one of " + ids());
        }

        public static String ids() {
            return String.join(", ", Arrays.stream(values()).map(Type::id).toList());
        }
    }

    /** What a call gives: at most one of each input, already read. */
    public record Content(JsonObject json, String text, byte[] from, String fromName) {
    }

    /** The files a write puts in the pack (pack-relative path to bytes), and a line saying what it did. */
    public record Plan(Map<String, byte[]> files, String note) {
    }

    /** First private-use codepoint a glyph is given: chars there never clash with real text. */
    public static final int FIRST_GLYPH = 0xE000;
    private static final int LAST_GLYPH = 0xF8FF;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private PackAssets() {
    }

    /** Parse a JSON object argument, refusing anything else in one line. */
    public static JsonObject parseObject(String text) {
        try {
            JsonElement e = JsonParser.parseString(text);
            if (e.isJsonObject()) {
                return e.getAsJsonObject();
            }
        } catch (JsonParseException e) {
            // Gson names its exception class and its leniency setting; only the place helps.
            java.util.regex.Matcher at = java.util.regex.Pattern.compile("line \\d+ column \\d+")
                    .matcher(String.valueOf(e.getMessage()));
            throw new TextureException("json is not valid JSON" + (at.find() ? " at " + at.group() : "")
                    + "; give one JSON object such as {\"key\": \"value\"}");
        }
        throw new TextureException("json must be a JSON object { ... }");
    }

    /** The files that writing {@code content} as {@code type} named {@code name} puts in the pack. */
    public static Plan plan(PackSource pack, Type type, ResourceId name, Content content) throws IOException {
        checkInputs(type, content);
        String assets = "assets/" + name.namespace() + "/";
        return switch (type) {
            case GLYPH -> glyph(pack, assets + "font/" + name.path() + ".json", name, content.json());
            case SOUND -> sound(pack, name, content);
            case LANG -> {
                if (!name.path().matches("[a-z0-9_]+")) {
                    throw new TextureException("a lang name is a language code such as en_us, not " + name.path());
                }
                String path = assets + "lang/" + name.path() + ".json";
                JsonObject merged = existingObject(pack, path);
                content.json().entrySet().forEach(e -> merged.add(e.getKey(), e.getValue()));
                yield one(path, merged, content.json().size() + " keys into " + path + " (" + merged.size() + " in all)");
            }
            case ATLAS -> {
                String path = assets + "atlases/" + name.path() + ".json";
                JsonObject merged = existingObject(pack, path);
                JsonArray sources = merged.has("sources") && merged.get("sources").isJsonArray()
                        ? merged.getAsJsonArray("sources") : new JsonArray();
                int added = 0;
                if (content.json().get("sources") instanceof JsonArray given) {
                    for (JsonElement s : given) {
                        if (!sources.contains(s)) {
                            sources.add(s);
                            added++;
                        }
                    }
                }
                content.json().entrySet().stream().filter(e -> !e.getKey().equals("sources"))
                        .forEach(e -> merged.add(e.getKey(), e.getValue()));
                merged.add("sources", sources);
                yield one(path, merged, added + " sources into " + path + " (" + sources.size() + " in all)");
            }
            case MCMETA -> {
                String png = assets + "textures/" + name.path() + ".png";
                if (!Files.isRegularFile(pack.root().resolve(png))) {
                    throw new TextureException("no texture " + name + " in the pack: draw it first, then give it an mcmeta");
                }
                JsonObject merged = existingObject(pack, png + ".mcmeta");
                content.json().entrySet().forEach(e -> merged.add(e.getKey(), e.getValue()));
                yield one(png + ".mcmeta", merged, "sections " + String.join(", ", content.json().keySet()) + " of "
                        + png + ".mcmeta");
            }
            case SHADER -> {
                if (!name.path().matches(".+\\.(vsh|fsh|glsl)")) {
                    throw new TextureException("a shader name ends in .vsh, .fsh or .glsl, such as minecraft:core/terrain.fsh");
                }
                String path = assets + "shaders/" + name.path();
                yield new Plan(Map.of(path, bytes(content.text())), path);
            }
            case TEXT -> {
                if (!name.path().matches("[a-z0-9_./-]+\\.(txt|json)")) {
                    throw new TextureException("a text name ends in .txt or .json, such as minecraft:splashes.txt");
                }
                String path = assets + "texts/" + name.path();
                yield new Plan(Map.of(path, bytes(content.text())), path);
            }
            case FILE -> {
                String path = assets + name.path();
                String ext = extension(name.path());
                String fromExt = extension(content.fromName().toLowerCase(Locale.ROOT));
                boolean font = name.path().startsWith("font/") && Set.of("ttf", "otf", "zip").contains(ext);
                boolean sound = name.path().startsWith("sounds/") && ext.equals("ogg");
                boolean texture = name.path().startsWith("textures/") && ext.equals("png");
                if (!font && !sound && !texture) {
                    throw new TextureException("file copies a font (font/....ttf, .otf or unihex .zip), a sound "
                            + "(sounds/....ogg) or a texture (textures/....png) into the pack; name it with its folder "
                            + "and extension, such as myplugin:font/runes.ttf");
                }
                if (!ext.equals(fromExt)) {
                    throw new TextureException("from is a ." + fromExt + " file but the name ends in ." + ext);
                }
                yield new Plan(Map.of(path, content.from()), path + " (" + content.from().length + " bytes)");
            }
            default -> {
                String path = assets + type.folder + "/" + name.path() + ".json";
                yield one(path, content.json(), path);
            }
        };
    }

    private static void checkInputs(Type type, Content c) {
        Map<Input, Boolean> given = new LinkedHashMap<>();
        given.put(Input.JSON, c.json() != null);
        given.put(Input.TEXT, c.text() != null);
        given.put(Input.FROM, c.from() != null);
        for (Map.Entry<Input, Boolean> e : given.entrySet()) {
            if (e.getValue() && !type.inputs().contains(e.getKey())) {
                throw new TextureException(type.id() + " takes " + inputs(type) + ", not "
                        + e.getKey().name().toLowerCase(Locale.ROOT));
            }
        }
        if (type.inputs().stream().noneMatch(given::get)) {
            throw new TextureException(type.id() + " needs " + inputs(type));
        }
    }

    private static String inputs(Type type) {
        return String.join(" or ", type.inputs().stream().map(i -> i.name().toLowerCase(Locale.ROOT)).sorted().toList());
    }

    /** Adds one bitmap provider to the font; with no {@code chars} it gets the next private-use char nothing uses. */
    private static Plan glyph(PackSource pack, String path, ResourceId font, JsonObject provider) throws IOException {
        JsonObject merged = existingObject(pack, path);
        JsonArray providers = merged.has("providers") && merged.get("providers").isJsonArray()
                ? merged.getAsJsonArray("providers") : new JsonArray();
        JsonObject p = provider.deepCopy();
        if (!p.has("type")) {
            p.addProperty("type", "bitmap");
        }
        if (!p.has("height")) {
            p.addProperty("height", 8);
        }
        if (!p.has("ascent")) {
            p.addProperty("ascent", Math.min(7, p.get("height").getAsInt()));
        }
        String note;
        if (!p.has("chars")) {
            int c = freeChar(providers);
            JsonArray chars = new JsonArray();
            chars.add(Character.toString(c));
            p.add("chars", chars);
            note = String.format(Locale.ROOT, "glyph \\u%04X in %s: show it with {\"text\":\"\\u%04X\",\"font\":\"%s\"}",
                    c, font, c, font);
        } else {
            note = "glyphs " + p.get("chars") + " in " + font;
        }
        providers.add(p);
        merged.add("providers", providers);
        return one(path, merged, note);
    }

    /** The lowest private-use codepoint no provider of the font maps. */
    static int freeChar(JsonArray providers) {
        Set<Integer> used = new HashSet<>();
        for (JsonElement e : providers) {
            if (!(e instanceof JsonObject p)) {
                continue;
            }
            if (p.get("chars") instanceof JsonArray rows) {
                rows.forEach(r -> {
                    if (r.isJsonPrimitive()) {
                        r.getAsString().codePoints().forEach(used::add);
                    }
                });
            }
            if (p.get("advances") instanceof JsonObject advances) {
                advances.keySet().forEach(k -> k.codePoints().forEach(used::add));
            }
        }
        for (int c = FIRST_GLYPH; c <= LAST_GLYPH; c++) {
            if (!used.contains(c)) {
                return c;
            }
        }
        throw new TextureException("the font has no free private-use char left; give chars yourself");
    }

    /**
     * An event in {@code sounds.json}: {@code json} fields replace the event's, its {@code sounds} are appended; a
     * {@code from} .ogg goes to {@code sounds/<event with dots as folders>.ogg} ({@code _2}, {@code _3}... when that
     * holds another sound) and is added to the event's sounds.
     */
    private static Plan sound(PackSource pack, ResourceId event, Content content) throws IOException {
        if (!event.path().matches("[a-z0-9_.-]+")) {
            throw new TextureException("a sound event is a dotted name such as myplugin:boss.roar, without folders");
        }
        String json = "assets/" + event.namespace() + "/sounds.json";
        JsonObject all = existingObject(pack, json);
        JsonObject entry = all.get(event.path()) instanceof JsonObject o ? o : new JsonObject();
        JsonArray sounds = entry.get("sounds") instanceof JsonArray a ? a : new JsonArray();
        if (content.json() != null) {
            for (Map.Entry<String, JsonElement> e : content.json().entrySet()) {
                if (e.getKey().equals("sounds") && e.getValue() instanceof JsonArray given) {
                    given.forEach(sounds::add);
                } else {
                    entry.add(e.getKey(), e.getValue());
                }
            }
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        String note = "event " + event + " in " + json;
        if (content.from() != null) {
            if (!extension(content.fromName().toLowerCase(Locale.ROOT)).equals("ogg")) {
                throw new TextureException("from must be an .ogg (Ogg Vorbis) file; Minecraft plays no other format");
            }
            String base = event.path().replace('.', '/');
            String file = null;
            for (int i = 1; file == null; i++) {
                String candidate = base + (i == 1 ? "" : "_" + i);
                Path existing = pack.root().resolve("assets/" + event.namespace() + "/sounds/" + candidate + ".ogg");
                if (!Files.exists(existing) || Arrays.equals(Files.readAllBytes(existing), content.from())) {
                    file = candidate;
                }
            }
            String ogg = "assets/" + event.namespace() + "/sounds/" + file + ".ogg";
            files.put(ogg, content.from());
            String id = event.namespace() + ":" + file;
            boolean listed = false;
            for (JsonElement s : sounds) {
                listed |= (s.isJsonPrimitive() && s.getAsString().equals(id))
                        || (s instanceof JsonObject o && o.has("name") && o.get("name").getAsString().equals(id));
            }
            if (!listed) {
                sounds.add(id);
            }
            note += ", sound " + ogg;
        }
        entry.add("sounds", sounds);
        all.add(event.path(), entry);
        files.put(json, bytes(GSON.toJson(all) + "\n"));
        return new Plan(files, note + "; play it with /playsound " + event + " master @s");
    }

    private static Plan one(String path, JsonObject json, String note) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(path, bytes(GSON.toJson(json) + "\n"));
        return new Plan(files, note);
    }

    /** The JSON object at a pack-relative path, or an empty one; a file that is not a JSON object is refused. */
    private static JsonObject existingObject(PackSource pack, String path) throws IOException {
        Path file = pack.root().resolve(path);
        if (!Files.isRegularFile(file)) {
            return new JsonObject();
        }
        try {
            JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (e.isJsonObject()) {
                return e.getAsJsonObject();
            }
        } catch (JsonParseException e) {
            // fall through
        }
        throw new TextureException(path + " is not a JSON object, so nothing can be merged into it; replace it with "
                + "a type that writes the whole file, or fix it by hand");
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Write a plan's files. */
    public static void write(PackSource pack, Plan plan) throws IOException {
        for (Map.Entry<String, byte[]> e : plan.files().entrySet()) {
            Path file = pack.root().resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, e.getValue());
        }
    }
}
