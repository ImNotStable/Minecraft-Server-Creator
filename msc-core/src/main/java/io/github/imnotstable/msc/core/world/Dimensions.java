package io.github.imnotstable.msc.core.world;

import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Generated worlds as a data pack in the main world's {@code datapacks} folder (ADR 0075): one dimension file per
 * world, {@code msc:<name>}, with the game's own terrain generators and a chosen set of biomes. Every platform on the
 * vanilla server (Paper and its forks, Fabric, Quilt, NeoForge, Forge) loads such dimensions on its next start; the
 * build tools then reach them with {@code world=msc:<name>}.
 */
public final class Dimensions {
    public static final String NAMESPACE = "msc";
    /** The pack's folder in {@code <world>/datapacks}. */
    public static final String PACK = "msc-worlds";
    public static final List<String> TERRAINS = List.of("normal", "amplified", "large_biomes", "caves",
            "floating_islands", "nether", "end", "flat", "void");
    /** Data pack formats this pack declares: 82 is the first with min/max_format (1.21.9); the top is open-ended. */
    static final int MIN_FORMAT = 82;
    static final int MAX_FORMAT = 1000;
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,40}");
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+(:[a-z0-9_./-]+)?");
    private static final Pattern LAYER = Pattern.compile("^(.*\\S):(\\d{1,4})$");
    /** Most biomes one world takes: each gets a share of the climate. */
    static final int MAX_BIOMES = 16;

    /**
     * One world: {@code terrain} is one of {@link #TERRAINS}; {@code biomes} empty for the terrain's own mix, one for
     * that biome everywhere, several to share the land between them; {@code layers} for flat, bottom up.
     */
    public record Spec(String name, String terrain, List<String> biomes, List<String> layers) {
        public Spec {
            if (!NAME.matcher(name).matches()) {
                throw new ToolException("name must be 1 to 40 of a-z, 0-9 and _, got " + name);
            }
            if (!TERRAINS.contains(terrain)) {
                throw new ToolException("unknown terrain " + terrain + "; use one of " + TERRAINS);
            }
            biomes = biomes.stream().map(Dimensions::id).toList();
            if (biomes.size() > MAX_BIOMES) {
                throw new ToolException("at most " + MAX_BIOMES + " biomes, got " + biomes.size());
            }
            if (!layers.isEmpty() && !terrain.equals("flat")) {
                throw new ToolException("layers= is for terrain=flat");
            }
            if (biomes.size() > 1 && (terrain.equals("flat") || terrain.equals("void"))) {
                throw new ToolException(terrain + " has one biome, got " + biomes.size());
            }
            layers = List.copyOf(layers);
        }

        public String id() {
            return NAMESPACE + ":" + name;
        }
    }

    private Dimensions() {
    }

    private static String id(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (!ID.matcher(t).matches()) {
            throw new ToolException("not a resource id: " + text);
        }
        return t.contains(":") ? t : "minecraft:" + t;
    }

    /** Where the pack lives for the main world folder {@code worldDir}. */
    public static Path pack(Path worldDir) {
        return worldDir.resolve("datapacks").resolve(PACK);
    }

    static Path file(Path worldDir, String name) {
        return pack(worldDir).resolve("data").resolve(NAMESPACE).resolve("dimension").resolve(name + ".json");
    }

    /** Write the world's dimension file and the pack's {@code pack.mcmeta}; returns the dimension file. */
    public static Path write(Path worldDir, Spec spec) throws IOException {
        Path meta = pack(worldDir).resolve("pack.mcmeta");
        Files.createDirectories(meta.getParent());
        Files.writeString(meta, mcmeta());
        Path file = file(worldDir, spec.name());
        Files.createDirectories(file.getParent());
        Files.writeString(file, json(spec));
        return file;
    }

    /** Take a world out of the pack; its saved chunks stay in the world folder. False when there was none. */
    public static boolean remove(Path worldDir, String name) throws IOException {
        return Files.deleteIfExists(file(worldDir, name));
    }

    /** The worlds in the pack, as {@code msc:<name>}, sorted. */
    public static List<String> list(Path worldDir) throws IOException {
        Path dir = file(worldDir, "x").getParent();
        List<String> out = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json"))
                        .forEach(n -> out.add(NAMESPACE + ":" + n.substring(0, n.length() - ".json".length())));
            }
        }
        out.sort(null);
        return out;
    }

    static String mcmeta() {
        return "{\"pack\":{\"description\":\"Worlds made with world_create (Minecraft Server Creator)\","
                + "\"min_format\":" + MIN_FORMAT + ",\"max_format\":" + MAX_FORMAT + "}}\n";
    }

    /** The dimension file: a dimension type and a generator. */
    static String json(Spec s) {
        String type = switch (s.terrain()) {
            case "caves" -> "minecraft:overworld_caves";
            case "nether" -> "minecraft:the_nether";
            case "end" -> "minecraft:the_end";
            default -> "minecraft:overworld";
        };
        String generator = switch (s.terrain()) {
            case "flat" -> flat(s.biomes().isEmpty() ? "minecraft:plains" : s.biomes().get(0),
                    s.layers().isEmpty() ? List.of("bedrock", "dirt:2", "grass_block") : s.layers());
            case "void" -> flat(s.biomes().isEmpty() ? "minecraft:the_void" : s.biomes().get(0), List.of("air"));
            default -> "{\"type\":\"minecraft:noise\",\"settings\":\"minecraft:" + switch (s.terrain()) {
                case "normal" -> "overworld";
                default -> s.terrain();
            } + "\",\"biome_source\":" + biomeSource(s) + "}";
        };
        return "{\"type\":\"" + type + "\",\"generator\":" + generator + "}\n";
    }

    private static String biomeSource(Spec s) {
        List<String> biomes = s.biomes();
        if (biomes.size() == 1) {
            return "{\"type\":\"minecraft:fixed\",\"biome\":\"" + biomes.get(0) + "\"}";
        }
        if (biomes.isEmpty()) {
            return switch (s.terrain()) {
                case "nether" -> "{\"type\":\"minecraft:multi_noise\",\"preset\":\"minecraft:nether\"}";
                case "end" -> "{\"type\":\"minecraft:the_end\"}";
                default -> "{\"type\":\"minecraft:multi_noise\",\"preset\":\"minecraft:overworld\"}";
            };
        }
        // Each biome takes a point on a circle in temperature and humidity: every biome borders two others, and the
        // game's climate noise gives the regions organic shapes.
        StringBuilder out = new StringBuilder("{\"type\":\"minecraft:multi_noise\",\"biomes\":[");
        for (int i = 0; i < biomes.size(); i++) {
            double angle = 2 * Math.PI * i / biomes.size();
            out.append(i == 0 ? "" : ",").append("{\"biome\":\"").append(biomes.get(i)).append("\",\"parameters\":{")
                    .append(String.format(Locale.ROOT, "\"temperature\":%.3f,\"humidity\":%.3f,", 0.6 * Math.cos(angle),
                            0.6 * Math.sin(angle)))
                    .append("\"continentalness\":[-1,1],\"erosion\":[-1,1],\"weirdness\":[-1,1],\"depth\":0,\"offset\":0}}");
        }
        return out.append("]}").toString();
    }

    private static String flat(String biome, List<String> layers) {
        StringBuilder out = new StringBuilder("{\"type\":\"minecraft:flat\",\"settings\":{\"biome\":\"").append(biome)
                .append("\",\"lakes\":false,\"features\":false,\"layers\":[");
        for (int i = 0; i < layers.size(); i++) {
            String layer = layers.get(i).trim();
            int height = 1;
            Matcher m = LAYER.matcher(layer);
            if (m.matches()) {
                height = Integer.parseInt(m.group(2));
                layer = m.group(1);
            }
            if (height < 1 || height > 384) {
                throw new ToolException("layer height must be 1 to 384, got " + height);
            }
            out.append(i == 0 ? "" : ",").append("{\"block\":\"").append(id(layer)).append("\",\"height\":")
                    .append(height).append('}');
        }
        return out.append("]}}").toString();
    }
}
