package io.github.imnotstable.msc.textures;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Consistency checks across a whole resource pack, folder or zip: pack metadata, file names, texture
 * sizes and animation files, palette discipline, and models or item definitions that point at
 * textures or models the pack does not have. References into the {@code minecraft} namespace are taken
 * to be vanilla assets and are not reported as missing.
 */
public final class PackLint {
    /** How bad a finding is. */
    public enum Severity { ERROR, WARNING, INFO }

    /** One problem, at a pack-relative path. */
    public record Finding(Severity severity, String path, String message) {
        @Override
        public String toString() {
            return severity + " " + path + ": " + message;
        }

        /** A reference to a file the pack does not have yet: fixed by adding that file, not by changing this one. */
        public boolean missing() {
            return message.endsWith(" is not in the pack");
        }
    }

    /** All findings, errors first. */
    public record Report(List<Finding> findings, int files) {
        public Report {
            findings = findings.stream()
                    .sorted(Comparator.comparing(Finding::severity).thenComparing(Finding::path).thenComparing(Finding::message))
                    .toList();
        }

        public long count(Severity s) {
            return findings.stream().filter(f -> f.severity() == s).count();
        }

        public boolean ok() {
            return count(Severity.ERROR) == 0;
        }

        /** Compact text: a summary line, then at most {@code limit} findings. */
        public String text(int limit) {
            StringBuilder out = new StringBuilder();
            out.append("pack check: ").append(files).append(" files, ").append(count(Severity.ERROR)).append(" errors, ")
                    .append(count(Severity.WARNING)).append(" warnings, ").append(count(Severity.INFO)).append(" notes");
            findings.stream().limit(limit).forEach(f -> out.append('\n').append(f));
            if (findings.size() > limit) {
                out.append("\n... ").append(findings.size() - limit).append(" more");
            }
            return out.toString();
        }
    }

    private static final Pattern ASSET_PATH = Pattern.compile("assets/[a-z0-9_.-]+/[a-z0-9_./-]+");
    private static final Pattern TEXTURE = Pattern.compile("assets/([^/]+)/textures/(.+)\\.png");
    private static final Pattern MODEL = Pattern.compile("assets/([^/]+)/models/(.+)\\.json");
    private static final Pattern ITEM = Pattern.compile("assets/([^/]+)/items/(.+)\\.json");
    private static final int MAX_COLORS = 16;

    private final Map<String, byte[]> files;
    private final List<Finding> findings = new ArrayList<>();
    private final Set<String> usedTextures = new HashSet<>();

    private PackLint(Map<String, byte[]> files) {
        this.files = files;
    }

    public static Report check(Map<String, byte[]> files) {
        return new PackLint(new TreeMap<>(files)).run();
    }

    public static Report checkDirectory(Path root) throws IOException {
        return check(read(root));
    }

    /** Every file under a pack folder, by its pack-relative path. */
    public static Map<String, byte[]> read(Path root) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return files;
    }

    public static Report checkZip(InputStream zip) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(zip)) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (!e.isDirectory()) {
                    files.put(e.getName(), in.readAllBytes());
                }
            }
        }
        return check(files);
    }

    private Report run() {
        checkMeta();
        Map<String, Texture> textures = new HashMap<>();
        Set<String> models = new HashSet<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String path = e.getKey();
            if (path.startsWith("assets/") && !ASSET_PATH.matcher(path).matches()) {
                add(Severity.ERROR, path, "file names must be lowercase a-z, 0-9, _ . - only; the game ignores this file");
                continue;
            }
            var tm = TEXTURE.matcher(path);
            if (tm.matches()) {
                Texture t = readTexture(path, e.getValue());
                if (t != null) {
                    textures.put(tm.group(1) + ":" + tm.group(2), t);
                    checkTexture(path, tm.group(2), t);
                }
            }
            var mm = MODEL.matcher(path);
            if (mm.matches()) {
                models.add(mm.group(1) + ":" + mm.group(2));
            }
            if (path.endsWith(".png.mcmeta") && !files.containsKey(path.substring(0, path.length() - 7))) {
                add(Severity.WARNING, path, ".png.mcmeta without its PNG");
            }
        }
        checkResolutions(textures);
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            var mm = MODEL.matcher(e.getKey());
            if (mm.matches()) {
                checkModel(e.getKey(), e.getValue(), textures.keySet(), models);
            }
            var im = ITEM.matcher(e.getKey());
            if (im.matches()) {
                checkItem(e.getKey(), e.getValue(), models);
            }
        }
        new AssetLint(this, files, models).run();
        for (String id : textures.keySet()) {
            String texPath = id.substring(id.indexOf(':') + 1);
            // Only block and item textures need a model; gui sprites, fonts, entities and the rest are read by name.
            if ((texPath.startsWith("block/") || texPath.startsWith("item/"))
                    && !id.startsWith("minecraft:") && !usedTextures.contains(id)) {
                add(Severity.INFO, texturePath(id), "not used by any model in the pack");
            }
        }
        return new Report(findings, files.size());
    }

    private void checkMeta() {
        byte[] meta = files.get("pack.mcmeta");
        if (meta == null) {
            add(Severity.ERROR, "pack.mcmeta", "missing; the game will not load the pack");
            return;
        }
        JsonObject json = json("pack.mcmeta", meta);
        if (json == null) {
            return;
        }
        JsonObject pack = json.has("pack") && json.get("pack").isJsonObject() ? json.getAsJsonObject("pack") : null;
        if (pack == null) {
            add(Severity.ERROR, "pack.mcmeta", "no \"pack\" object");
            return;
        }
        if (!pack.has("pack_format") && !pack.has("min_format")) {
            add(Severity.ERROR, "pack.mcmeta", "no pack_format or min_format");
        }
        if (!pack.has("description")) {
            add(Severity.WARNING, "pack.mcmeta", "no description");
        }
    }

    private Texture readTexture(String path, byte[] png) {
        try {
            return Texture.readAnyPng(png);
        } catch (TextureException ex) {
            add(Severity.ERROR, path, ex.getMessage());
            return null;
        }
    }

    private void checkTexture(String path, String texPath, Texture t) {
        int w = t.width();
        int h = t.height();
        JsonObject meta = files.containsKey(path + ".mcmeta") ? quietJson(files.get(path + ".mcmeta")) : null;
        boolean animated = meta != null && meta.has("animation");
        // Frames of their own size: the animation check sizes them.
        boolean framed = animated && meta.get("animation") instanceof JsonObject a && (a.has("width") || a.has("height"));
        if (Integer.bitCount(w) != 1) {
            add(Severity.WARNING, path, "width " + w + " is not a power of two");
        }
        if (h != w && !framed && !texPath.startsWith("gui/") && !texPath.startsWith("font/") && !texPath.startsWith("entity/")
                && !texPath.startsWith("painting/")
                && !texPath.startsWith("environment/")) {
            if (h % w != 0) {
                add(Severity.WARNING, path, w + "x" + h + " is neither square nor a stack of square frames");
            } else if (!animated) {
                add(Severity.ERROR, path, (h / w) + " stacked frames but no .png.mcmeta; it will show as missing");
            }
        }
        int colors = t.colors().size();
        if (colors > MAX_COLORS && w <= 32) {
            add(Severity.WARNING, path, colors + " colors; pixel art at this size reads best with 4 to 12");
        }
        Stats stats = Stats.of(t);
        if (texPath.startsWith("block/") && stats.partialAlpha()) {
            add(Severity.WARNING, path, "semi-transparent pixels; most blocks render them fully opaque or not at all");
        }
        if (texPath.startsWith("item/") && stats.opaqueFraction() >= 0.999) {
            add(Severity.WARNING, path, "item texture has no transparent background");
        }
        if (stats.opaqueFraction() == 0) {
            add(Severity.WARNING, path, "fully transparent");
        }
    }

    /** Mixing 16 and 32 pixel textures in one folder usually means one was drawn at the wrong size. */
    private void checkResolutions(Map<String, Texture> textures) {
        for (String folder : List.of("block/", "item/")) {
            Map<Integer, Integer> widths = new HashMap<>();
            textures.forEach((id, t) -> {
                if (id.substring(id.indexOf(':') + 1).startsWith(folder)) {
                    widths.merge(t.width(), 1, Integer::sum);
                }
            });
            if (widths.size() < 2) {
                continue;
            }
            int common = widths.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            textures.forEach((id, t) -> {
                if (id.substring(id.indexOf(':') + 1).startsWith(folder) && t.width() != common) {
                    add(Severity.WARNING, texturePath(id), t.width() + " pixels wide; most " + folder
                            + " textures in this pack are " + common);
                }
            });
        }
    }

    private void checkModel(String path, byte[] data, Set<String> textures, Set<String> models) {
        JsonObject json = json(path, data);
        if (json == null) {
            return;
        }
        if (json.has("parent")) {
            String raw = string(path, "parent", json.get("parent"));
            String parent = raw == null ? null : qualify(raw);
            if (parent != null && !parent.startsWith("minecraft:") && !models.contains(parent)) {
                add(Severity.ERROR, path, "parent " + parent + " is not in the pack");
            }
        } else if (!json.has("elements")) {
            add(Severity.WARNING, path, "no parent and no elements; the model is empty");
        }
        if (json.has("textures") && json.get("textures").isJsonObject()) {
            for (Map.Entry<String, JsonElement> slot : json.getAsJsonObject("textures").entrySet()) {
                // A slot is a texture name, or { "sprite": name, "force_translucent": true }.
                JsonElement value = slot.getValue() instanceof JsonObject o && o.has("sprite") ? o.get("sprite") : slot.getValue();
                String ref = string(path, "texture " + slot.getKey(), value);
                if (ref == null || ref.startsWith("#")) {
                    continue;
                }
                String id = qualify(ref);
                usedTextures.add(id);
                if (!id.startsWith("minecraft:") && !textures.contains(id)) {
                    add(Severity.ERROR, path, "texture " + slot.getKey() + " = " + id + " is not in the pack");
                }
            }
        }
    }

    private void checkItem(String path, byte[] data, Set<String> models) {
        JsonObject json = json(path, data);
        if (json == null) {
            return;
        }
        if (!json.has("model")) {
            add(Severity.ERROR, path, "item definition has no \"model\"");
            return;
        }
        List<String> refs = new ArrayList<>();
        collectModelRefs(path, json.get("model"), refs);
        for (String ref : refs) {
            String id = qualify(ref);
            if (!id.startsWith("minecraft:") && !models.contains(id)) {
                add(Severity.ERROR, path, "model " + id + " is not in the pack");
            }
        }
    }

    /** Every {@code "model"} string under a {@code minecraft:model} entry, however deeply nested. */
    private void collectModelRefs(String path, JsonElement e, List<String> out) {
        if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(c -> collectModelRefs(path, c, out));
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            String type = o.has("type") ? string(path, "type", o.get("type")) : null;
            boolean plain = type != null && qualify(type).equals("minecraft:model");
            for (Map.Entry<String, JsonElement> c : o.entrySet()) {
                if (plain && c.getKey().equals("model")) {
                    String ref = string(path, "model", c.getValue());
                    if (ref != null) {
                        out.add(ref);
                    }
                } else {
                    collectModelRefs(path, c.getValue(), out);
                }
            }
        }
    }

    /** The JSON object, or null without a finding: for a file another check reports on. */
    static JsonObject quietJson(byte[] data) {
        try {
            return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)) instanceof JsonObject o ? o : null;
        } catch (JsonParseException ex) {
            return null;
        }
    }

    /** Note a texture as used by something in the pack. */
    void used(String id) {
        usedTextures.add(id);
    }

    JsonObject json(String path, byte[] data) {
        try {
            JsonElement e = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
            if (e.isJsonObject()) {
                return e.getAsJsonObject();
            }
            add(Severity.ERROR, path, "not a JSON object");
        } catch (JsonParseException ex) {
            add(Severity.ERROR, path, "invalid JSON: " + firstLine(ex.getMessage()));
        }
        return null;
    }

    /** The value if it is a JSON string; otherwise an ERROR finding and null. */
    String string(String path, String what, JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return value.getAsString();
        }
        add(Severity.ERROR, path, what + " must be a string");
        return null;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "parse error";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    static String qualify(String ref) {
        return ref.contains(":") ? ref : "minecraft:" + ref;
    }

    private static String texturePath(String id) {
        int colon = id.indexOf(':');
        return "assets/" + id.substring(0, colon) + "/textures/" + id.substring(colon + 1) + ".png";
    }

    void add(Severity s, String path, String message) {
        findings.add(new Finding(s, path, message));
    }
}
