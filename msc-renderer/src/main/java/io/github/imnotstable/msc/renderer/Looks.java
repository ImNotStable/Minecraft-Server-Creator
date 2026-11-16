package io.github.imnotstable.msc.renderer;

import io.github.imnotstable.msc.renderer.Look.Tex;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves block-state strings to {@link Look}s and caches them, textures included, until the texture source's
 * {@link TextureSource#stamp() stamp} changes.
 */
final class Looks {
    static final Set<String> INVISIBLE = Set.of("air", "cave_air", "void_air", "barrier", "light",
            "structure_void", "moving_piston");
    private static final int[] UPRIGHT = {BlockColors.SIDE, BlockColors.SIDE, BlockColors.BOTTOM, BlockColors.TOP,
            BlockColors.SIDE, BlockColors.SIDE};
    private static final int[] ALONG_X = {BlockColors.TOP, BlockColors.TOP, BlockColors.SIDE, BlockColors.SIDE,
            BlockColors.SIDE, BlockColors.SIDE};
    private static final int[] ALONG_Z = {BlockColors.SIDE, BlockColors.SIDE, BlockColors.SIDE, BlockColors.SIDE,
            BlockColors.TOP, BlockColors.TOP};
    private static final String[][] KIND_SUFFIXES = {{"_top", "", "_side"}, {"_side", "", "_front"},
            {"_bottom", "_top", "", "_side"}};
    private static final int GRASS_TINT = 0x7fb238;
    private static final int FOLIAGE_TINT = 0x59a52c;
    private static final Map<String, Integer> TINTS = Map.ofEntries(
            Map.entry("grass_block_top", GRASS_TINT), Map.entry("short_grass", GRASS_TINT),
            Map.entry("grass", GRASS_TINT), Map.entry("tall_grass_top", GRASS_TINT),
            Map.entry("tall_grass_bottom", GRASS_TINT), Map.entry("fern", GRASS_TINT),
            Map.entry("large_fern_top", GRASS_TINT), Map.entry("large_fern_bottom", GRASS_TINT),
            Map.entry("oak_leaves", FOLIAGE_TINT), Map.entry("jungle_leaves", FOLIAGE_TINT),
            Map.entry("acacia_leaves", FOLIAGE_TINT), Map.entry("dark_oak_leaves", FOLIAGE_TINT),
            Map.entry("mangrove_leaves", FOLIAGE_TINT), Map.entry("vine", FOLIAGE_TINT),
            Map.entry("spruce_leaves", 0x619961), Map.entry("birch_leaves", 0x80a755),
            Map.entry("lily_pad", 0x208030), Map.entry("water_still", 0x3f76e4));

    private final TextureSource textures;
    private final Map<String, Optional<Look>> cache = new ConcurrentHashMap<>();
    private final Map<String, Optional<Tex>> texCache = new ConcurrentHashMap<>();
    private long stamp;

    Looks(TextureSource textures) {
        this.textures = textures;
        this.stamp = textures.stamp();
    }

    /** One entry per palette index; {@code null} for blocks that are not drawn (air and friends). */
    Look[] resolve(List<String> palette) {
        long now = textures.stamp();
        synchronized (this) {
            if (now != stamp) {
                // Textures were added or changed: forget every look and lookup, misses included.
                cache.clear();
                texCache.clear();
                stamp = now;
            }
        }
        Look[] out = new Look[palette.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = cache.computeIfAbsent(palette.get(i), s -> Optional.ofNullable(build(s))).orElse(null);
        }
        return out;
    }

    private Look build(String stateString) {
        BlockState state = BlockState.parse(stateString);
        String name = state.name();
        if (INVISIBLE.contains(name)) {
            return null;
        }
        int[] faceKind = switch (state.prop("axis", "y")) {
            case "x" -> ALONG_X;
            case "z" -> ALONG_Z;
            default -> UPRIGHT;
        };
        int[] argb = new int[3];
        Tex[] tex = new Tex[3];
        boolean known = true;
        boolean translucent = false;
        for (int kind = 0; kind < 3; kind++) {
            BlockColors.Found found = BlockColors.find(name, kind);
            argb[kind] = found.argb();
            known &= found.known();
            tex[kind] = findTexture(state, kind);
            translucent |= tex[kind] != null ? (tex[kind].average() >>> 24) < 0xe6 : (found.argb() >>> 24) < 0xe6;
        }
        return new Look(name, Shapes.of(state), argb, tex, faceKind, translucent, known || tex[BlockColors.SIDE] != null);
    }

    private Tex findTexture(BlockState state, int kind) {
        String name = state.name();
        if (name.equals("water") || name.equals("lava")) {
            return texture(state.namespace(), name + "_still");
        }
        for (String candidate : BlockColors.candidates(name)) {
            if (kind == BlockColors.SIDE && (state.is("half", "upper") || state.is("half", "lower"))) {
                Tex half = texture(state.namespace(), candidate + (state.is("half", "upper") ? "_top" : "_bottom"));
                if (half != null) {
                    return half;
                }
            }
            for (String suffix : KIND_SUFFIXES[kind]) {
                Tex t = texture(state.namespace(), candidate + suffix);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    private Tex texture(String namespace, String blockTexture) {
        return texCache.computeIfAbsent(namespace + ":" + blockTexture, key -> textures
                .texture(namespace, "block/" + blockTexture)
                .map(image -> toTex(image, TINTS.get(blockTexture)))).orElse(null);
    }

    /** Converts to ARGB pixels, keeps the first frame of animated strips, and applies a biome-style tint. */
    private static Tex toTex(BufferedImage image, Integer tint) {
        int size = Math.min(image.getWidth(), image.getHeight());
        int[] px = image.getRGB(0, 0, size, size, null, 0, size);
        long a = 0;
        long r = 0;
        long g = 0;
        long b = 0;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            if (tint != null) {
                p = (p & 0xff000000) | ((((p >> 16) & 0xff) * ((tint >> 16) & 0xff) / 255) << 16)
                        | ((((p >> 8) & 0xff) * ((tint >> 8) & 0xff) / 255) << 8) | ((p & 0xff) * (tint & 0xff) / 255);
                px[i] = p;
            }
            int alpha = p >>> 24;
            a += alpha;
            r += ((p >> 16) & 0xff) * alpha;
            g += ((p >> 8) & 0xff) * alpha;
            b += (p & 0xff) * alpha;
        }
        int average = a == 0 ? 0 : (int) ((a / px.length) << 24 | (r / a) << 16 | (g / a) << 8 | (b / a));
        return new Tex(size, px, average);
    }
}
