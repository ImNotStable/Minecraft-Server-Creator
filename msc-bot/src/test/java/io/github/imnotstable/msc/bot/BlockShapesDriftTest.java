package io.github.imnotstable.msc.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The collision table keeps up with the game. Minestom's data artifact (on the test classpath only, see
 * build.gradle.kts) has vanilla's collision shape for every block state; each state must get the same {@link Kind}
 * from {@link BlockShapes}. The data lags the catalog's Minecraft version by a release, so the blocks that are only in
 * the newer block list are checked against a block of the same form that the data has, or an expected kind of our
 * own where there is none.
 */
class BlockShapesDriftTest {
    /** How a player meets a block state, in the classes route search and physics tell apart. */
    enum Kind {
        /** No collision box at all. */
        NONE,
        /** Boxes a player walks onto without a jump (carpets, slabs, stairs, repeaters, heads). */
        LOW,
        /** In the way; a jump climbs it. */
        BLOCK,
        /** Higher than a jump (fences, walls). */
        TALL;

        static Kind of(List<Box> boxes) {
            if (boxes.isEmpty()) {
                return NONE;
            }
            return switch (Footing.of(boxes)) {
                case OPEN, STEP -> LOW;
                case SOLID -> BLOCK;
                case TALL -> TALL;
            };
        }
    }

    /**
     * Simplified on purpose: a thin panel at the edge of the cell (an open door or trapdoor, a ladder) is no box, so
     * the route search walks through the doorway and climbs the ladder (Walk's climb ids) instead of seeing a wall.
     * The piston head's arm reaches into the next cell, which the bot does not model.
     */
    private static final List<Simplified> SIMPLIFIED = List.of(new Simplified("_door", Kind.BLOCK, Kind.NONE),
            new Simplified("_trapdoor", Kind.BLOCK, Kind.NONE), new Simplified("ladder", Kind.BLOCK, Kind.NONE),
            new Simplified("piston_head", Kind.TALL, Kind.BLOCK));

    /**
     * Blocks of the newer list the data does not have yet: the data block of the same form, or our own expected kind.
     * Ours, read from the 26.3 block code: red_shrub is registered with noCollision, shelf_mushroom is a ledge at most
     * 5/16 high, straw_bed is 5/16 high.
     */
    private static final Map<String, String> NEWER = newer();

    /** Names in the block list that are no blocks: their blockstate files only give the entity's model. */
    private static final List<String> NOT_BLOCKS = List.of("item_frame", "glow_item_frame");

    record Simplified(String suffix, Kind vanilla, Kind bot) {
        static boolean allows(String name, Kind vanilla, Kind bot) {
            return SIMPLIFIED.stream().anyMatch(s -> name.endsWith(s.suffix) && s.vanilla == vanilla && s.bot == bot);
        }
    }

    private static Map<String, String> newer() {
        Map<String, String> out = new TreeMap<>(Map.of("red_shrub", "NONE", "shelf_mushroom", "LOW", "straw_bed", "LOW",
                "potted_poplar_sapling", "potted_oak_sapling"));
        for (String color : List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black")) {
            for (String stuff : List.of("concrete", "wool")) {
                out.put(color + "_" + stuff + "_slab", "stone_slab");
                out.put(color + "_" + stuff + "_stairs", "stone_stairs");
            }
        }
        for (String leaves : List.of("orange", "red", "yellow")) {
            out.put(leaves + "_poplar_leaves", "oak_leaves");
        }
        for (String form : List.of("button", "door", "fence", "fence_gate", "hanging_sign", "log", "planks",
                "pressure_plate", "sapling", "shelf", "sign", "slab", "stairs", "trapdoor", "wall_hanging_sign",
                "wall_sign", "wood")) {
            out.put("poplar_" + form, "oak_" + form);
        }
        out.put("stripped_poplar_log", "stripped_oak_log");
        out.put("stripped_poplar_wood", "stripped_oak_wood");
        return out;
    }

    private static final Pattern AABB = Pattern.compile(
            "AABB\\[([-\\d.E]+), ([-\\d.E]+), ([-\\d.E]+)] -> \\[([-\\d.E]+), ([-\\d.E]+), ([-\\d.E]+)]");

    private static Map<String, Map<String, Kind>> vanilla;

    @BeforeAll
    static void loadData() throws IOException {
        vanilla = new TreeMap<>();
        try (InputStream in = BlockShapesDriftTest.class.getResourceAsStream("/net/minestom/data/block.json")) {
            assertThat(in).as("net.minestom data block.json on the test classpath").isNotNull();
            JsonObject blocks;
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                blocks = JsonParser.parseReader(reader).getAsJsonObject();
            }
            for (Map.Entry<String, JsonElement> block : blocks.entrySet()) {
                JsonObject b = block.getValue().getAsJsonObject();
                String shape = b.get("collisionShape").getAsString();
                Map<String, Kind> states = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> state : b.getAsJsonObject("states").entrySet()) {
                    JsonElement own = state.getValue().getAsJsonObject().get("collisionShape");
                    states.put(state.getKey(), Kind.of(boxes(own != null ? own.getAsString() : shape)));
                }
                vanilla.put(BlockShapes.name(block.getKey()), states);
            }
        }
    }

    @Test
    void everyStateOfTheDataGetsVanillasKind() {
        List<String> wrong = new ArrayList<>();
        vanilla.forEach((name, states) -> {
            String why = check(name, states);
            if (why != null) {
                wrong.add(why);
            }
        });
        assertThat(vanilla).as("blocks in the data").hasSizeGreaterThan(1000);
        assertThat(wrong).as("blocks whose collision kind differs from vanilla").isEmpty();
    }

    @Test
    void everyBlockOfTheSupportedVersionIsChecked() throws IOException {
        String version = System.getProperty("msc.minecraft");
        assertThat(version).as("msc.minecraft is set from the version catalog").isNotBlank();
        List<String> wrong = new ArrayList<>();
        List<String> names = blocks("blocks-" + version + ".txt");
        assertThat(names).as("block list for Minecraft " + version).hasSizeGreaterThan(1000);
        for (String name : names) {
            if (vanilla.containsKey(name) || NOT_BLOCKS.contains(name)) {
                continue;
            }
            String like = NEWER.get(name);
            if (like == null) {
                wrong.add(name + ": not in the data; add it to NEWER");
            } else if (vanilla.containsKey(like)) {
                String why = check(name, vanilla.get(like));
                if (why != null) {
                    wrong.add(why + " (as " + like + ")");
                }
            } else {
                Kind got = Kind.of(BlockShapes.of("minecraft:" + name));
                if (got != Kind.valueOf(like)) {
                    wrong.add(name + ": expected " + like + ", bot " + got);
                }
            }
        }
        assertThat(wrong).as("blocks of " + version + " with the wrong collision kind").isEmpty();
    }

    /** Null when every state of {@code name} gets the kind vanilla gives that state in {@code states}. */
    private static String check(String name, Map<String, Kind> states) {
        for (Map.Entry<String, Kind> state : states.entrySet()) {
            String props = state.getKey().equals("[]") ? "" : state.getKey();
            Kind got = Kind.of(BlockShapes.of("minecraft:" + name + props));
            if (got != state.getValue() && !Simplified.allows(name, state.getValue(), got)) {
                return name + props + ": vanilla " + state.getValue() + ", bot " + got;
            }
        }
        return null;
    }

    private static List<Box> boxes(String shape) {
        List<Box> out = new ArrayList<>();
        Matcher m = AABB.matcher(shape);
        while (m.find()) {
            out.add(new Box(d(m, 1), d(m, 2), d(m, 3), d(m, 4), d(m, 5), d(m, 6)));
        }
        return out;
    }

    private static double d(Matcher m, int group) {
        return Double.parseDouble(m.group(group));
    }

    private static List<String> blocks(String resource) throws IOException {
        try (InputStream in = BlockShapesDriftTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        }
    }
}
