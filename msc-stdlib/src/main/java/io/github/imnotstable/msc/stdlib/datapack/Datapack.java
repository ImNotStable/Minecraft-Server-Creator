package io.github.imnotstable.msc.stdlib.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.imnotstable.msc.stdlib.loot.LootTable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A data pack written from code: custom recipes (shaped, shapeless, smelting, blasting, smoking, campfire, stonecutting,
 * smithing), loot tables, item and block tags, functions and advancements, the game's own ways to add content on every
 * vanilla-based server (Paper, Fabric, Forge, NeoForge). Write it into the world's {@code datapacks} folder at start and
 * reload; the platform adapters do both ({@code Recipes} on Paper and the mod loaders).
 *
 * <pre>{@code
 * Datapack pack = Datapack.of("myplugin", "My plugin's recipes")
 *         .shaped("ruby_sword", "myplugin:ruby_sword", 1, List.of(" R ", " R ", " S "),
 *                 Map.of('R', "minecraft:redstone_block", 'S', "minecraft:stick"))
 *         .smelting("ruby", "minecraft:redstone_ore", "minecraft:redstone_block", 1.0, 200)
 *         .loot("chests/ruin", LootTable.vanillaJson(table, "minecraft:chest"))
 *         .itemTag("rubies", "minecraft:redstone_block");
 * pack.write(worldFolder.resolve("datapacks"));
 * }</pre>
 * Ids without a namespace get the pack's. Result items with custom names or data are written as
 * {@code {"id":..., "components":{...}}} through {@link #result}.
 */
public final class Datapack {
    private static final Pattern NAME = Pattern.compile("[a-z0-9_./-]+");
    private static final Pattern NS = Pattern.compile("[a-z0-9_.-]+");
    /** Data pack formats: 82 is the first with min/max_format (1.21.9); the top is open-ended. */
    static final int MIN_FORMAT = 82;
    static final int MAX_FORMAT = 1000;

    private final String namespace;
    private final String description;
    private final Map<String, String> files = new LinkedHashMap<>();

    private Datapack(String namespace, String description) {
        if (!NS.matcher(namespace).matches()) {
            throw new IllegalArgumentException("namespace must be lower case letters, digits, _ . -, got " + namespace);
        }
        this.namespace = namespace;
        this.description = description;
    }

    public static Datapack of(String namespace, String description) {
        return new Datapack(namespace, description);
    }

    public String namespace() {
        return namespace;
    }

    /** The files this pack writes, path inside the pack to content. */
    public Map<String, String> files() {
        return Map.copyOf(files);
    }

    private String id(String id) {
        return id.contains(":") || id.startsWith("#") ? id : namespace + ":" + id;
    }

    private Datapack put(String folder, String name, String ext, String content) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(folder + " name must be lower case letters, digits, _ . / -, got " + name);
        }
        files.put("data/" + namespace + "/" + folder + "/" + name + "." + ext, content);
        return this;
    }

    /** A result stack: {@code {"id":..., "count":n}}, plus data components such as a custom name. */
    public static JsonObject result(String item, int count, Map<String, com.google.gson.JsonElement> components) {
        JsonObject r = new JsonObject();
        r.addProperty("id", item);
        if (count != 1) {
            r.addProperty("count", count);
        }
        if (components != null && !components.isEmpty()) {
            JsonObject c = new JsonObject();
            components.forEach(c::add);
            r.add("components", c);
        }
        return r;
    }

    private JsonObject recipe(String type, String category) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "minecraft:" + type);
        if (category != null) {
            o.addProperty("category", category);
        }
        return o;
    }

    /** A crafting table recipe from rows of key characters ({@code ' '} empty); keys are items or {@code #tags}. */
    public Datapack shaped(String name, String result, int count, List<String> pattern, Map<Character, String> key) {
        return shaped(name, result(id(result), count, null), pattern, key);
    }

    public Datapack shaped(String name, JsonObject result, List<String> pattern, Map<Character, String> key) {
        if (pattern.isEmpty() || pattern.size() > 3 || pattern.stream().anyMatch(r -> r.isEmpty() || r.length() > 3)) {
            throw new IllegalArgumentException("a shaped recipe has 1 to 3 rows of 1 to 3 characters");
        }
        JsonObject o = recipe("crafting_shaped", "misc");
        JsonArray rows = new JsonArray();
        pattern.forEach(rows::add);
        o.add("pattern", rows);
        JsonObject k = new JsonObject();
        for (String row : pattern) {
            for (char c : row.toCharArray()) {
                if (c != ' ' && !key.containsKey(c)) {
                    throw new IllegalArgumentException("pattern uses '" + c + "' but the key has no item for it");
                }
            }
        }
        key.forEach((c, item) -> k.addProperty(String.valueOf(c), id(item)));
        o.add("key", k);
        o.add("result", result);
        return put("recipe", name, "json", o.toString());
    }

    /** A crafting recipe in any arrangement, 1 to 9 ingredients. */
    public Datapack shapeless(String name, String result, int count, List<String> ingredients) {
        if (ingredients.isEmpty() || ingredients.size() > 9) {
            throw new IllegalArgumentException("a shapeless recipe has 1 to 9 ingredients");
        }
        JsonObject o = recipe("crafting_shapeless", "misc");
        JsonArray in = new JsonArray();
        ingredients.forEach(i -> in.add(id(i)));
        o.add("ingredients", in);
        o.add("result", result(id(result), count, null));
        return put("recipe", name, "json", o.toString());
    }

    private Datapack cooking(String type, String name, String input, String result, double xp, int ticks) {
        JsonObject o = recipe(type, "misc");
        o.addProperty("ingredient", id(input));
        o.add("result", result(id(result), 1, null));
        o.addProperty("experience", xp);
        o.addProperty("cookingtime", ticks);
        return put("recipe", name, "json", o.toString());
    }

    /** A furnace recipe; 200 ticks is the game's usual. */
    public Datapack smelting(String name, String input, String result, double xp, int ticks) {
        return cooking("smelting", name, input, result, xp, ticks);
    }

    public Datapack blasting(String name, String input, String result, double xp, int ticks) {
        return cooking("blasting", name, input, result, xp, ticks);
    }

    public Datapack smoking(String name, String input, String result, double xp, int ticks) {
        return cooking("smoking", name, input, result, xp, ticks);
    }

    public Datapack campfire(String name, String input, String result, double xp, int ticks) {
        return cooking("campfire_cooking", name, input, result, xp, ticks);
    }

    public Datapack stonecutting(String name, String input, String result, int count) {
        JsonObject o = recipe("stonecutting", null);
        o.addProperty("ingredient", id(input));
        o.add("result", result(id(result), count, null));
        return put("recipe", name, "json", o.toString());
    }

    /** A smithing table upgrade: template, base item and addition turn into the result, keeping the base's data. */
    public Datapack smithing(String name, String template, String base, String addition, String result) {
        JsonObject o = recipe("smithing_transform", null);
        o.addProperty("template", id(template));
        o.addProperty("base", id(base));
        o.addProperty("addition", id(addition));
        o.add("result", result(id(result), 1, null));
        return put("recipe", name, "json", o.toString());
    }

    /** A loot table, as {@link LootTable#vanillaJson} writes one or by hand. */
    public Datapack loot(String name, String json) {
        return put("loot_table", name, "json", json);
    }

    /** An item tag ({@code #<ns>:name}), replacing nothing the game already has. */
    public Datapack itemTag(String name, String... items) {
        return tag("tags/item", name, items);
    }

    public Datapack blockTag(String name, String... blocks) {
        return tag("tags/block", name, blocks);
    }

    private Datapack tag(String folder, String name, String... values) {
        JsonObject o = new JsonObject();
        o.addProperty("replace", false);
        JsonArray v = new JsonArray();
        for (String s : values) {
            v.add(id(s));
        }
        o.add("values", v);
        return put(folder, name, "json", o.toString());
    }

    /** A function: one command per line, run with {@code /function <ns>:name}. */
    public Datapack function(String name, List<String> commands) {
        return put("function", name, "mcfunction", String.join("\n", commands) + "\n");
    }

    /** An advancement, as JSON. */
    public Datapack advancement(String name, String json) {
        return put("advancement", name, "json", json);
    }

    /** Any other file under {@code data/<ns>/}, such as a worldgen feature or a damage type. */
    public Datapack raw(String path, String content) {
        if (path.contains("..") || path.startsWith("/")) {
            throw new IllegalArgumentException("path must stay inside the pack: " + path);
        }
        files.put("data/" + namespace + "/" + path, content);
        return this;
    }

    /** The pack's {@code pack.mcmeta}. */
    public String mcmeta() {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", description);
        pack.addProperty("min_format", MIN_FORMAT);
        pack.addProperty("max_format", MAX_FORMAT);
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return root.toString();
    }

    /**
     * Write the pack as a folder {@code <datapacks>/<namespace>}, replacing what an earlier run wrote there. Returns
     * the pack's folder. A changed pack takes effect after {@code /reload} or a restart.
     */
    public Path write(Path datapacks) throws IOException {
        Path root = datapacks.resolve(namespace);
        if (Files.isDirectory(root.resolve("data"))) {
            try (var walk = Files.walk(root.resolve("data"))) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        Files.createDirectories(root);
        Files.writeString(root.resolve("pack.mcmeta"), mcmeta(), StandardCharsets.UTF_8);
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path file = root.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue(), StandardCharsets.UTF_8);
        }
        return root;
    }
}
