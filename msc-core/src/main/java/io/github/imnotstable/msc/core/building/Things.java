package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.world.BlockNames;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The things a build holds besides plain blocks (ADR 0082): written signs, patterned banners, textured heads, books on
 * lecterns, filled containers, entities (mobs, villagers, armor stands, item frames, displays), painted biomes, the
 * game's own features, and light where it is dark.
 */
final class Things {
    private static final Pattern ID = Pattern.compile("([a-z0-9_.-]+:)?[a-z0-9_./-]+");
    static final List<String> DYES = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    private Things() {
    }

    /** The dimension id a world name stands for, for {@code execute in}. */
    static String dimension(String world) {
        if (world.contains(":")) {
            return world;
        }
        if (world.endsWith("_nether") || world.equals("nether")) {
            return "minecraft:the_nether";
        }
        if (world.endsWith("_the_end") || world.equals("end") || world.equals("the_end")) {
            return "minecraft:the_end";
        }
        return "minecraft:overworld";
    }

    static String id(String text, String what) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (!ID.matcher(t).matches()) {
            throw new ToolException(what + " must be an id such as minecraft:plains, got " + text);
        }
        return t.contains(":") ? t : "minecraft:" + t;
    }

    /**
     * An item id, refused when it is a minecraft: id no bundled item list has: the game drops an unknown item from a
     * chest, frame or stand with only a log warning, so the build reported success with the item missing.
     * shortcut: checked against the item lists player_test bundles (msc-bot items/), so a newer Minecraft's new items
     * are refused until its list is added there (ItemsTest has the command); with no list at all nothing is checked.
     */
    static String itemId(String text) {
        String id = id(text, "item");
        if (id.startsWith("minecraft:") && !VANILLA_ITEMS.isEmpty() && !VANILLA_ITEMS.contains(id.substring(10))) {
            throw new ToolException("unknown item " + id + "; use the game's item id (api_search or /give names "
                    + "it), and a modded item with its namespace");
        }
        return id;
    }

    private static final java.util.Set<String> VANILLA_ITEMS = vanillaItems();

    private static java.util.Set<String> vanillaItems() {
        java.util.Set<String> all = new java.util.HashSet<>();
        // Every list msc-bot bundles, without naming them here: year versions 26 to 30, drops .0 to .20.
        for (String version : java.util.stream.IntStream.rangeClosed(26, 30).boxed()
                .flatMap(y -> java.util.stream.IntStream.rangeClosed(0, 20).mapToObj(d -> y + "." + d)).toList()) {
            try (java.io.InputStream in = Things.class.getResourceAsStream(
                    "/io/github/imnotstable/msc/bot/items/" + version + ".txt")) {
                if (in != null) {
                    new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
                            .map(String::strip).filter(l -> !l.isEmpty()).forEach(all::add);
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        all.remove("air");
        return all;
    }

    /** A string as SNBT, quoted and escaped. */
    static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String lines(String text, int count) {
        String[] parts = text == null ? new String[0] : text.split("\\|", -1);
        if (parts.length > count) {
            throw new ToolException("at most " + count + " lines (split with |), got " + parts.length);
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(quote(i < parts.length ? parts[i] : ""));
        }
        return "[" + String.join(",", out) + "]";
    }

    /** A sign's data: front and back lines, dye colour, glowing text, waxed against edits. */
    static String sign(String front, String back, String color, boolean glow, boolean wax) {
        if (color != null && !DYES.contains(color)) {
            throw new ToolException("color is a dye colour (" + String.join(", ", DYES) + "), got " + color);
        }
        String c = color == null ? "black" : color;
        String side = ",color:" + quote(c) + ",has_glowing_text:" + (glow ? "1b" : "0b");
        return "{front_text:{messages:" + lines(front, 4) + side + "},back_text:{messages:" + lines(back, 4) + side
                + "},is_waxed:" + (wax ? "1b" : "0b") + "}";
    }

    /** A banner's patterns, {@code pattern:colour} in order, bottom layer first. */
    static String banner(String patterns) {
        List<String> out = new ArrayList<>();
        for (String entry : patterns.split(",")) {
            String[] pc = entry.trim().split(":");
            if (pc.length != 2 || !DYES.contains(pc[1])) {
                throw new ToolException("patterns take pattern:colour entries such as stripe_top:red,cross:black; got "
                        + entry);
            }
            out.add("{pattern:" + quote(id(pc[0], "pattern")) + ",color:" + quote(pc[1]) + "}");
        }
        if (out.size() > 16) {
            throw new ToolException("a banner takes at most 16 patterns, got " + out.size());
        }
        return "{patterns:[" + String.join(",", out) + "]}";
    }

    /**
     * A head's profile: a player's name, a base64 texture value, or a skin from textures.minecraft.net (its URL or the
     * texture hash), which is how decorative object heads are made.
     */
    static String head(String player, String texture, String skin) {
        int given = (player != null ? 1 : 0) + (texture != null ? 1 : 0) + (skin != null ? 1 : 0);
        if (given != 1) {
            throw new ToolException("head takes one of player=NAME, texture=BASE64 or skin=HASH|URL");
        }
        if (player != null) {
            if (!player.matches("[A-Za-z0-9_]{1,16}")) {
                throw new ToolException("player must be a Minecraft name (1-16 letters, digits, _), got " + player);
            }
            return "{profile:{name:" + quote(player) + "}}";
        }
        String value = texture;
        if (skin != null) {
            String hash = skin.replaceFirst("^https?://textures\\.minecraft\\.net/texture/", "");
            if (!hash.matches("[0-9a-fA-F]{32,128}")) {
                throw new ToolException("skin must be a textures.minecraft.net texture URL or its hash, got " + skin);
            }
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\"}}}";
            value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        }
        if (!value.matches("[A-Za-z0-9+/=]+")) {
            throw new ToolException("texture must be base64 (as head sites give it), got " + texture);
        }
        return "{profile:{properties:[{name:\"textures\",value:" + quote(value) + "}]}}";
    }

    /** A written book on a lectern, open at its first page. */
    static String book(String title, String author, String pages) {
        if (pages == null || pages.isEmpty()) {
            throw new ToolException("book needs pages=First page|Second page");
        }
        List<String> out = new ArrayList<>();
        for (String p : pages.split("\\|", -1)) {
            out.add(quote(p.replace("\\n", "\n")));
        }
        String t = title == null ? "Book" : title;
        if (t.length() > 32) {
            throw new ToolException("a book title is at most 32 characters, got " + t.length());
        }
        return "{Book:{id:\"minecraft:written_book\",count:1,components:{\"minecraft:written_book_content\":{title:"
                + quote(t) + ",author:" + quote(author == null ? "?" : author) + ",pages:[" + String.join(",", out)
                + "]}}},Page:0}";
    }

    /** A container's items ({@code item:count}, slots in order) or a loot table it fills from when first opened. */
    static String container(String items, String loot) {
        if ((items == null) == (loot == null)) {
            throw new ToolException("container takes items=id:count,... or loot=TABLE, one of them");
        }
        if (loot != null) {
            return "{LootTable:" + quote(id(loot, "loot")) + "}";
        }
        List<String> out = new ArrayList<>();
        int slot = 0;
        for (String entry : items.split(",")) {
            String[] ic = entry.trim().split(":");
            String item;
            int count = 1;
            if (ic.length == 3) { // namespace:item:count
                item = ic[0] + ":" + ic[1];
                count = count(ic[2], entry);
            } else if (ic.length == 2 && ic[1].matches("\\d+")) {
                item = ic[0];
                count = count(ic[1], entry);
            } else {
                item = entry.trim();
            }
            out.add("{Slot:" + slot++ + "b,id:" + quote(itemId(item)) + ",count:" + count + "}");
        }
        if (slot > 54) {
            throw new ToolException("at most 54 item stacks, got " + slot);
        }
        return "{Items:[" + String.join(",", out) + "]}";
    }

    private static int count(String text, String entry) {
        try {
            int n = Integer.parseInt(text);
            if (n < 1 || n > 99) {
                throw new ToolException("item count must be 1 to 99, got " + entry);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new ToolException("items take id:count entries, got " + entry);
        }
    }

    /** {@code data} as the state's block entity data, checked to be one compound. */
    static String withData(String state, String data) {
        if (BlockPattern.isMix(state)) {
            throw new ToolException("give one block here, not a mix");
        }
        if (BlockNames.data(state) != null) {
            throw new ToolException("give the block without {data}; this step writes it");
        }
        return BlockNames.full(state) + data;
    }

    // ------------------------------------------------------------------ entities

    /** Merge the build's tag into an entity's SNBT. */
    static String tagged(String nbt, String tag) {
        String tags = "\"msc_build\",\"" + tag + "\"";
        String body = nbt == null || nbt.isBlank() ? "{}" : nbt.trim();
        if (!body.startsWith("{") || !body.endsWith("}")) {
            throw new ToolException("entity data must be one SNBT compound {...}, got " + nbt);
        }
        int at = body.indexOf("Tags:[");
        if (at >= 0) {
            return body.substring(0, at + 6) + tags + (body.charAt(at + 6) == ']' ? "" : ",") + body.substring(at + 6);
        }
        return "{Tags:[" + tags + "]" + (body.length() > 2 ? "," + body.substring(1) : "}");
    }

    /** The summon command for one entity at a block's centre. */
    static String summon(String world, String type, double x, double y, double z, String nbt, String tag) {
        return "execute in " + dimension(world) + " run summon " + id(type, "entity type") + " " + x + " " + y + " " + z
                + " " + tagged(nbt == null ? null : components(nbt), tag);
    }

    private static final java.util.regex.Pattern QUOTED_NAME =
            java.util.regex.Pattern.compile("(CustomName:)'(\\{[^']*\\}|\\[[^']*\\])'");

    /**
     * A text component written as a quoted JSON string (the old format, CustomName:'{"text":"Vela"}') shows its JSON
     * as the name in today's game, which reads components as SNBT; JSON is valid SNBT, so the quotes go (ADR 0093).
     */
    static String components(String nbt) {
        return QUOTED_NAME.matcher(nbt).replaceAll("$1$2");
    }

    /** Data that makes a mob stay as placed: no AI, silent, never despawns, unhurt. */
    static final String STILL = "NoAI:1b,Silent:1b,PersistenceRequired:1b,Invulnerable:1b";

    /** Facing ids the game uses for hanging entities. */
    static int facing(String dir) {
        return switch (dir) {
            case "down" -> 0;
            case "up" -> 1;
            case "north" -> 2;
            case "south" -> 3;
            case "west" -> 4;
            case "east" -> 5;
            default -> throw new ToolException("facing is north, south, east, west, up or down, got " + dir);
        };
    }

    /** An item stack as SNBT. */
    static String item(String item) {
        return "{id:" + quote(itemId(item)) + ",count:1}";
    }

    /** Biome commands for a box, cut into pieces /fillbiome takes (at most 32768 blocks each). */
    static List<String> biome(Region box, String biome, String replace) {
        List<String> out = new ArrayList<>();
        String dim = dimension(box.world());
        for (int y = box.min().y(); y <= box.max().y(); y += 32) {
            for (int z = box.min().z(); z <= box.max().z(); z += 32) {
                for (int x = box.min().x(); x <= box.max().x(); x += 32) {
                    int y2 = Math.min(box.max().y(), y + 31);
                    int z2 = Math.min(box.max().z(), z + 31);
                    int x2 = Math.min(box.max().x(), x + 31);
                    out.add("execute in " + dim + " run fillbiome " + x + " " + y + " " + z + " " + x2 + " " + y2 + " " + z2
                            + " " + biome + (replace == null ? "" : " replace " + replace));
                }
            }
        }
        if (out.size() > 4096) {
            throw new ToolException("the biome box is too big (" + out.size() + " pieces of 32^3); paint it in parts");
        }
        return out;
    }

    // ------------------------------------------------------------------ light

    /** The light a block gives off, 0 for none. */
    static int emits(String state) {
        String id = Joins.id(state);
        if (id.equals("light")) {
            return Integer.parseInt(Joins.get(state, "level", "15"));
        }
        if (id.endsWith("campfire") || id.equals("redstone_lamp") || id.endsWith("copper_bulb") || id.equals("furnace")
                || id.equals("smoker") || id.equals("blast_furnace") || id.endsWith("candle") || id.equals("redstone_ore")) {
            if (!Joins.get(state, "lit", id.endsWith("campfire") ? "true" : "false").equals("true")) {
                return 0;
            }
        }
        if (id.endsWith("candle")) {
            return 3 * Integer.parseInt(Joins.get(state, "candles", "1"));
        }
        if (id.startsWith("soul_") && (id.contains("torch") || id.contains("lantern") || id.contains("campfire"))
                || id.equals("soul_fire")) {
            return 10;
        }
        if (id.contains("torch") && !id.contains("redstone")) {
            return 14;
        }
        return switch (id) {
            case "lantern", "copper_lantern", "glowstone", "sea_lantern", "shroomlight", "jack_o_lantern", "campfire",
                 "redstone_lamp", "lava", "beacon", "conduit", "fire", "ochre_froglight", "verdant_froglight",
                 "pearlescent_froglight", "end_gateway", "end_portal", "copper_bulb" -> 15;
            case "end_rod" -> 14;
            case "furnace", "smoker", "blast_furnace", "nether_portal", "crying_obsidian" -> 13;
            case "glow_lichen", "redstone_torch", "redstone_wall_torch" -> 7;
            case "amethyst_cluster", "large_amethyst_bud" -> 5;
            case "magma_block", "brewing_stand" -> 3;
            case "sea_pickle" -> 6;
            default -> id.endsWith("copper_lantern") ? 15 : id.endsWith("copper_bulb") ? 12 : 0;
        };
    }

    /**
     * Light where it is dark (ADR 0082): over the spots of kind {@code on} in the box, in a spread-out order, a light
     * goes wherever the light from the sources already there (and lights placed so far) falls below {@code level},
     * counting a block's light as fading one per block of walking distance (walls are not traced).
     */
    static void light(Canvas c, Step.Light l) throws InterruptedException {
        Region box = l.box();
        Region seen = Detail.grow(box, 15);
        Canvas.Lookup at = c.current(seen);
        Canvas.Mask mask = c.mask(box, l.replace());
        List<int[]> sources = new ArrayList<>();
        for (int y = seen.min().y(); y <= seen.max().y(); y++) {
            for (int z = seen.min().z(); z <= seen.max().z(); z++) {
                for (int x = seen.min().x(); x <= seen.max().x(); x++) {
                    int e = emits(at.at(x, y, z));
                    if (e > 0) {
                        sources.add(new int[] {x, y, z, e});
                    }
                }
            }
        }
        List<long[]> spots = new ArrayList<>();
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    if (Detail.fits(at, l.on(), x, y, z)) {
                        spots.add(new long[] {x, y, z, Noise.hash(l.seed() + 13, x * 31L + y, z)});
                    }
                }
            }
        }
        // A spread-out order: sorted by hash, so the first lights land all over the box, not along one edge.
        spots.sort((a, b) -> Long.compareUnsigned(a[3], b[3]));
        int given = l.block() == null ? 15 : emits(l.block().at(0, 0, 0));
        int strength = given == 0 ? 15 : given;
        for (long[] s : spots) {
            int x = (int) s[0];
            int y = (int) s[1];
            int z = (int) s[2];
            int best = 0;
            for (int[] src : sources) {
                best = Math.max(best, src[3] - Math.abs(src[0] - x) - Math.abs(src[1] - y) - Math.abs(src[2] - z));
                if (best >= l.level()) {
                    break;
                }
            }
            if (best >= l.level() || !mask.allows(x, y, z) || c.planned(x, y, z) != null) {
                continue;
            }
            String state = l.block() != null ? l.block().at(x, y, z) : switch (l.on()) {
                case "ceiling" -> "minecraft:lantern[hanging=true]";
                case "wall" -> "minecraft:torch";
                default -> "minecraft:lantern[hanging=false]";
            };
            c.set(x, y, z, state);
            sources.add(new int[] {x, y, z, strength});
        }
    }
}
