package io.github.imnotstable.msc.core.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Reads the plugin or mod jars in a server's addon folder and says, one line each, what would stop them loading: a jar
 * for another platform, a jar that is no plugin or mod, a required dependency that is not installed, one id in two
 * jars (ADR 0057). Silent when all is well, so a start pays nothing for it.
 */
public final class AddonCheck {
    /** What one jar declares: the kinds of descriptor it has, its id, version, other ids it provides, and what it needs. */
    public record Addon(Path jar, Set<Kind> kinds, String id, String version, Set<String> provides, Set<String> requires) {
        /** {@code LuckPerms 5.5.85 (plugins/LuckPerms.jar)}. */
        public String describe(Path dir) {
            return (id == null ? "?" : id) + (version == null ? "" : " " + version) + " ("
                    + dir.getParent().relativize(jar).toString().replace('\\', '/') + ")";
        }
    }

    /** A descriptor file, and the platforms that load a jar with it. */
    public enum Kind {
        BUKKIT("plugin.yml", "a Bukkit/Paper plugin", Platform.PAPER, Platform.PURPUR, Platform.FOLIA),
        PAPER("paper-plugin.yml", "a Paper plugin", Platform.PAPER, Platform.PURPUR, Platform.FOLIA),
        FABRIC("fabric.mod.json", "a Fabric mod", Platform.FABRIC, Platform.QUILT),
        QUILT("quilt.mod.json", "a Quilt mod", Platform.QUILT),
        NEOFORGE("META-INF/neoforge.mods.toml", "a NeoForge mod", Platform.NEOFORGE),
        FORGE("META-INF/mods.toml", "a Forge mod", Platform.FORGE),
        VELOCITY("velocity-plugin.json", "a Velocity plugin", Platform.VELOCITY),
        BUNGEE("bungee.yml", "a BungeeCord plugin", Platform.WATERFALL);

        final String file;
        final String what;
        final Set<Platform> platforms;

        Kind(String file, String what, Platform first, Platform... more) {
            this.file = file;
            this.what = what;
            this.platforms = EnumSet.of(first, more);
        }
    }

    /** Ids a platform provides itself, never a jar in the folder. */
    private static final Set<String> BUILT_IN = Set.of("minecraft", "java", "fabricloader", "fabric-loader",
            "quilt_loader", "neoforge", "forge", "fml", "velocity", "bungeecord", "waterfall");

    private AddonCheck() {
    }

    /** Every jar in {@code dir} (not below it), sorted by name; none when the folder is missing. */
    public static List<Addon> read(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Addon> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path jar : files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                    && Files.isRegularFile(p)).sorted().toList()) {
                out.add(read(jar, true));
            }
        }
        return out;
    }

    /**
     * The problems of {@code platform}'s addon folder in {@code serverDir}, one line each; empty when there are none.
     * {@code unknown}: also name jars with no descriptor at all (a library a mod loader may still load, or a broken file).
     */
    public static List<String> problems(Path serverDir, Platform platform, boolean unknown) throws IOException {
        if (platform.addonDir() == null) {
            return List.of();
        }
        Path dir = serverDir.resolve(platform.addonDir());
        return problems(dir, platform, read(dir), unknown);
    }

    static List<String> problems(Path dir, Platform platform, List<Addon> addons, boolean unknown) {
        List<String> out = new ArrayList<>();
        Map<String, List<Addon>> byId = new LinkedHashMap<>();
        Set<String> present = new LinkedHashSet<>(BUILT_IN);
        List<Addon> loaded = new ArrayList<>();
        for (Addon a : addons) {
            if (a.kinds().isEmpty()) {
                if (unknown) {
                    out.add(a.describe(dir) + " is not a plugin or mod (no plugin.yml, fabric.mod.json, mods.toml, "
                            + "velocity-plugin.json or bungee.yml, or not a jar)");
                }
                continue;
            }
            if (a.kinds().stream().noneMatch(k -> k.platforms.contains(platform))) {
                out.add(a.describe(dir) + " is " + a.kinds().iterator().next().what + "; " + platform.displayName()
                        + " does not load it: delete it");
                continue;
            }
            loaded.add(a);
            if (a.id() != null) {
                byId.computeIfAbsent(a.id().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(a);
                present.add(a.id());
            }
            present.addAll(a.provides());
        }
        byId.values().stream().filter(l -> l.size() > 1).forEach(l -> out.add(l.get(0).id() + " is in "
                + l.size() + " jars: " + String.join(", ", l.stream().map(a -> a.describe(dir)).toList())
                + "; keep one"));
        for (Addon a : loaded) {
            for (String need : a.requires()) {
                if (!present.contains(need)) {
                    out.add(a.describe(dir) + " requires " + need + ", which is not installed");
                }
            }
        }
        return out;
    }

    /** One jar; {@code nested}: also read the Fabric jars inside it (jar-in-jar), whose ids it provides. */
    static Addon read(Path jar, boolean nested) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Map<String, byte[]> files = new LinkedHashMap<>();
            for (Kind k : Kind.values()) {
                ZipEntry e = zip.getEntry(k.file);
                if (e != null) {
                    try (InputStream in = zip.getInputStream(e)) {
                        files.put(k.file, in.readAllBytes());
                    }
                }
            }
            Addon a = parse(jar, files);
            if (nested && a.kinds().contains(Kind.FABRIC)) {
                Set<String> provides = new LinkedHashSet<>(a.provides());
                for (String inner : fabricJars(files.get(Kind.FABRIC.file))) {
                    ZipEntry e = zip.getEntry(inner);
                    if (e == null) {
                        continue;
                    }
                    try (InputStream in = zip.getInputStream(e)) {
                        Addon n = parse(jar, innerFiles(in.readAllBytes()));
                        if (n.id() != null) {
                            provides.add(n.id());
                        }
                        provides.addAll(n.provides());
                    }
                }
                return new Addon(jar, a.kinds(), a.id(), a.version(), provides, a.requires());
            }
            return a;
        } catch (java.util.zip.ZipException e) {
            return new Addon(jar, Set.of(), null, null, Set.of(), Set.of());
        }
    }

    private static Map<String, byte[]> innerFiles(byte[] jar) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (Kind.FABRIC.file.equals(e.getName())) {
                    files.put(e.getName(), in.readAllBytes());
                }
            }
        }
        return files;
    }

    /** The descriptors of one jar, by file name, read into what it declares. Unknown or broken text declares less. */
    static Addon parse(Path jar, Map<String, byte[]> files) {
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        String id = null;
        String version = null;
        Set<String> provides = new LinkedHashSet<>();
        Set<String> requires = new LinkedHashSet<>();
        for (Kind k : Kind.values()) {
            byte[] bytes = files.get(k.file);
            if (bytes == null) {
                continue;
            }
            kinds.add(k);
            String text = new String(bytes, StandardCharsets.UTF_8);
            switch (k) {
                case BUKKIT, PAPER, BUNGEE -> {
                    id = id != null ? id : yamlScalar(text, "name");
                    version = version != null ? version : yamlScalar(text, "version");
                    provides.addAll(yamlList(text, "provides"));
                    if (k == Kind.BUNGEE) {
                        requires.addAll(yamlList(text, "depends"));
                    } else if (k == Kind.BUKKIT) {
                        requires.addAll(yamlList(text, "depend"));
                    } else {
                        requires.addAll(paperRequired(text));
                    }
                }
                case FABRIC, QUILT, VELOCITY -> {
                    JsonObject o = json(text);
                    JsonObject meta = k == Kind.QUILT && o.has("quilt_loader") && o.get("quilt_loader").isJsonObject()
                            ? o.getAsJsonObject("quilt_loader") : o;
                    id = id != null ? id : string(meta.get("id"));
                    version = version != null ? version : string(meta.get("version"));
                    if (meta.has("provides") && meta.get("provides").isJsonArray()) {
                        meta.getAsJsonArray("provides").forEach(e -> add(provides, e));
                    }
                    if (k == Kind.FABRIC && o.has("depends") && o.get("depends").isJsonObject()) {
                        requires.addAll(o.getAsJsonObject("depends").keySet());
                    } else if (k == Kind.QUILT && meta.has("depends") && meta.get("depends").isJsonArray()) {
                        meta.getAsJsonArray("depends").forEach(e -> {
                            if (e.isJsonObject() && !Boolean.TRUE.equals(bool(e.getAsJsonObject().get("optional")))) {
                                add(requires, e.getAsJsonObject().get("id"));
                            } else {
                                add(requires, e);
                            }
                        });
                    } else if (k == Kind.VELOCITY && o.has("dependencies") && o.get("dependencies").isJsonArray()) {
                        o.getAsJsonArray("dependencies").forEach(e -> {
                            if (e.isJsonObject() && !Boolean.TRUE.equals(bool(e.getAsJsonObject().get("optional")))) {
                                add(requires, e.getAsJsonObject().get("id"));
                            }
                        });
                    }
                }
                case NEOFORGE, FORGE -> {
                    Matcher m = TOML_MOD_ID.matcher(text);
                    String own = m.find() ? m.group(1) : null;
                    id = id != null ? id : own;
                    Matcher v = TOML_VERSION.matcher(text);
                    version = version != null ? version : v.find() ? v.group(1) : null;
                    requires.addAll(tomlRequired(text));
                }
                default -> throw new IllegalStateException(k.name());
            }
        }
        requires.removeAll(BUILT_IN);
        if (id != null) {
            requires.remove(id);
        }
        return new Addon(jar, kinds, id, version, provides, requires);
    }

    // shortcut: line-based YAML and TOML reading covers the descriptor shapes Paper, NeoForge and Forge document;
    // a descriptor in another YAML style reads as having fewer dependencies (never more). Upgrade to a parser if one
    // lands in the catalog.
    private static final Pattern TOML_MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern TOML_VERSION = Pattern.compile("(?m)^\\s*version\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern TOML_DEP_TABLE = Pattern.compile("(?m)^\\s*\\[\\[dependencies\\.[^]]+]]");

    /** The modIds of {@code [[dependencies.x]]} tables that are {@code type="required"} or {@code mandatory=true}. */
    static List<String> tomlRequired(String text) {
        List<String> out = new ArrayList<>();
        String[] tables = TOML_DEP_TABLE.split(text);
        for (int i = 1; i < tables.length; i++) {
            String t = tables[i].split("(?m)^\\s*\\[", 2)[0];
            Matcher id = TOML_MOD_ID.matcher(t);
            boolean required = t.matches("(?s).*\\btype\\s*=\\s*\"(?i:required)\".*")
                    || t.matches("(?s).*\\bmandatory\\s*=\\s*true\\b.*");
            if (id.find() && required) {
                out.add(id.group(1));
            }
        }
        return out;
    }

    /** A top-level {@code key: value}, quotes removed. */
    static String yamlScalar(String text, String key) {
        Matcher m = Pattern.compile("(?m)^" + key + ":[ \\t]*['\"]?([^'\"#\\r\\n]*?)['\"]?[ \\t]*(#.*)?$").matcher(text);
        return m.find() && !m.group(1).isBlank() ? m.group(1).strip() : null;
    }

    /** A top-level list, flow ({@code [a, b]}) or block ({@code - a} lines below the key). */
    static List<String> yamlList(String text, String key) {
        List<String> out = new ArrayList<>();
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith(key + ":")) {
                continue;
            }
            String rest = lines[i].substring(key.length() + 1).replaceAll("#.*", "").strip();
            if (rest.startsWith("[")) {
                for (String s : rest.replaceAll("[\\[\\]]", "").split(",")) {
                    addName(out, s);
                }
            } else if (rest.isEmpty()) {
                for (int j = i + 1; j < lines.length && lines[j].matches("\\s*(-.*|#.*)?"); j++) {
                    if (lines[j].strip().startsWith("-")) {
                        addName(out, lines[j].strip().substring(1));
                    }
                }
            } else {
                addName(out, rest);
            }
        }
        return out;
    }

    /** paper-plugin.yml: names under {@code dependencies: server:}, except those whose block says {@code required: false}. */
    static List<String> paperRequired(String text) {
        List<String> out = new ArrayList<>();
        int section = 0; // 0 outside, 1 in dependencies:, 2 in its server:
        int serverIndent = -1;
        int nameIndent = -1;
        String current = null;
        boolean required = true;
        for (String line : (text + "\nend:").split("\\R")) {
            String s = line.replaceAll("#.*", "").stripTrailing();
            if (s.isBlank()) {
                continue;
            }
            int indent = s.length() - s.stripLeading().length();
            s = s.strip();
            boolean leavesName = current != null && indent <= nameIndent;
            if (leavesName) {
                if (required) {
                    out.add(current);
                }
                current = null;
            }
            if (indent == 0) {
                section = s.equals("dependencies:") ? 1 : 0;
            } else if (section == 1 && s.equals("server:")) {
                section = 2;
                serverIndent = indent;
                nameIndent = -1;
            } else if (section == 2 && indent <= serverIndent) {
                section = 1;
            } else if (section == 2 && (nameIndent < 0 || indent == nameIndent) && s.endsWith(":")) {
                nameIndent = indent;
                current = s.substring(0, s.length() - 1).replaceAll("['\"]", "").strip();
                required = true;
            } else if (current != null && s.startsWith("required:")) {
                required = !s.substring("required:".length()).strip().startsWith("false");
            }
        }
        return out;
    }

    private static void addName(List<String> out, String raw) {
        String s = raw.replaceAll("['\"]", "").strip();
        if (!s.isEmpty()) {
            out.add(s);
        }
    }

    private static List<String> fabricJars(byte[] json) {
        List<String> out = new ArrayList<>();
        JsonObject o = json(new String(json, StandardCharsets.UTF_8));
        if (o.has("jars") && o.get("jars").isJsonArray()) {
            o.getAsJsonArray("jars").forEach(e -> {
                if (e.isJsonObject()) {
                    add(out, e.getAsJsonObject().get("file"));
                }
            });
        }
        return out;
    }

    private static JsonObject json(String text) {
        try {
            JsonElement e = JsonParser.parseString(text);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (JsonParseException e) {
            return new JsonObject();
        }
    }

    private static void add(java.util.Collection<String> to, JsonElement e) {
        String s = string(e);
        if (s != null) {
            to.add(s);
        }
    }

    private static String string(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static Boolean bool(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : null;
    }
}
