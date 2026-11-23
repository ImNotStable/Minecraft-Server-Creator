package io.github.imnotstable.msc.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.textures.PackLint;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * What the pack the server actually served gives a client: the glyphs its fonts draw, the sound events it adds, and
 * what {@link PackLint} finds in it. The test player checks what it sees and hears in game against this.
 */
record PackContents(Set<Integer> glyphs, boolean anyGlyph, Set<String> sounds, PackLint.Report lint) {
    private static final Pattern FONT = Pattern.compile("assets/[^/]+/font/[^/]+\\.json");
    private static final Pattern SOUNDS = Pattern.compile("assets/([^/]+)/sounds\\.json");
    /** Where packs put custom glyphs: the Private Use Area, which no default font draws. */
    static final int PUA_FIRST = 0xE000;
    static final int PUA_LAST = 0xF8FF;
    /** No pack: nothing custom is drawn or played. */
    static final PackContents NONE = new PackContents(Set.of(), false, Set.of(), new PackLint.Report(List.of(), 0));

    static PackContents of(byte[] zip) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                if (!e.isDirectory()) {
                    files.put(e.getName(), in.readAllBytes());
                }
            }
        }
        Set<Integer> glyphs = new HashSet<>();
        Set<String> sounds = new HashSet<>();
        boolean anyGlyph = false;
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            if (FONT.matcher(f.getKey()).matches()) {
                anyGlyph |= font(json(f.getValue()), glyphs);
            }
            Matcher m = SOUNDS.matcher(f.getKey());
            if (m.matches() && json(f.getValue()) instanceof JsonObject o) {
                o.keySet().forEach(k -> sounds.add(m.group(1) + ":" + k));
            }
        }
        return new PackContents(Set.copyOf(glyphs), anyGlyph, Set.copyOf(sounds), PackLint.check(files));
    }

    /**
     * Adds the characters a font file's providers draw; true when one draws characters the file does not list.
     * shortcut: a ttf, unihex or reference provider counts as drawing every glyph; read those files if a pack's
     * missing glyphs go unreported.
     */
    private static boolean font(JsonElement json, Set<Integer> glyphs) {
        if (!(json instanceof JsonObject o) || !(o.get("providers") instanceof JsonArray providers)) {
            return false;
        }
        boolean any = false;
        for (JsonElement p : providers) {
            if (!(p instanceof JsonObject provider) || !provider.has("type")) {
                continue;
            }
            switch (provider.get("type").getAsString().replace("minecraft:", "")) {
                case "bitmap" -> {
                    if (provider.get("chars") instanceof JsonArray rows) {
                        for (JsonElement row : rows) {
                            if (row.isJsonPrimitive()) {
                                row.getAsString().codePoints().forEach(glyphs::add);
                            }
                        }
                    }
                }
                case "space" -> {
                    if (provider.get("advances") instanceof JsonObject advances) {
                        advances.keySet().forEach(k -> k.codePoints().forEach(glyphs::add));
                    }
                }
                default -> any = true;
            }
        }
        return any;
    }

    private static JsonElement json(byte[] data) {
        try {
            return JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return null; // PackLint reports the broken file
        }
    }

    /** Private Use Area characters in {@code text} that no font of this pack draws: a client shows each as a box. */
    List<Integer> missingGlyphs(Set<Integer> seen) {
        return anyGlyph ? List.of() : seen.stream().filter(c -> !glyphs.contains(c)).sorted().toList();
    }

    /** Sounds outside the minecraft namespace that this pack does not add: a client plays nothing for them. */
    List<String> missingSounds(Set<String> heard) {
        return heard.stream().map(PackContents::qualify).filter(s -> !s.startsWith("minecraft:"))
                .filter(s -> !sounds.contains(s)).sorted().toList();
    }

    static String qualify(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    static boolean custom(int codePoint) {
        return codePoint >= PUA_FIRST && codePoint <= PUA_LAST;
    }
}
