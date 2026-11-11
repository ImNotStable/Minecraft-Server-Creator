package io.github.imnotstable.msc.textures;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Procedural block textures. Each one tiles seamlessly, uses only the colours of its ramps and is
 * deterministic for a given seed, so an AI can ask for "stone, seed 3" instead of drawing 256 pixels.
 */
public final class Patterns {
    /** Pattern names accepted by {@link #generate}. */
    public static final List<String> NAMES = List.of("noise", "ore", "planks", "bricks");

    private Patterns() {
    }

    /**
     * @param name   one of {@link #NAMES}
     * @param size   16, 32 or 64
     * @param ramp   main colours, darkest first
     * @param accent second colours: the ore for {@code ore}, the mortar for {@code bricks}; may be null
     */
    public static Texture generate(String name, int size, Ramp ramp, Ramp accent, long seed) {
        return switch (name) {
            case "noise" -> noise(size, ramp, seed);
            case "ore" -> ore(size, ramp, accent, seed);
            case "planks" -> planks(size, ramp, seed);
            case "bricks" -> bricks(size, ramp, accent, seed);
            default -> throw new TextureException("unknown pattern \"" + name + "\"; use one of " + String.join(", ", NAMES));
        };
    }

    /** Stone, dirt or sand: mottled noise mapped onto the ramp, with fewer extreme shades. */
    public static Texture noise(int size, Ramp ramp, long seed) {
        checkSize(size);
        Texture t = Texture.square(size);
        double[] field = Noise.field(size, size, 0.2, seed);
        int[] shade = quantize(field, ramp.size());
        for (int i = 0; i < shade.length; i++) {
            t.set(i % size, i / size, ramp.get(shade[i]));
        }
        return t;
    }

    /**
     * Stone noise with shaded ore clusters: light at the top left, dark at the bottom right.
     *
     * @param ore ore colours; null gives red
     */
    public static Texture ore(int size, Ramp stone, Ramp ore, long seed) {
        if (ore == null) {
            ore = Ramp.from(0xFFD4263A, 4);
        }
        Texture t = noise(size, stone, seed);
        int clusters = Math.max(3, size * size / 64);
        Set<Integer> taken = new HashSet<>();
        long s = seed + 101;
        for (int c = 0, tries = 0; c < clusters && tries < clusters * 20; tries++) {
            int cx = 1 + (int) (Noise.hash(tries, 0, s) * (size - 3));
            int cy = 1 + (int) (Noise.hash(tries, 1, s) * (size - 3));
            List<Integer> blob = new ArrayList<>();
            blob.add(cy * size + cx);
            int want = 4 + (int) (Noise.hash(tries, 2, s) * (size >= 32 ? 8 : 4));
            for (int k = 0; blob.size() < want && k < 40; k++) {
                int from = blob.get((int) (Noise.hash(tries, 10 + k, s) * blob.size()));
                int dir = (int) (Noise.hash(tries, 60 + k, s) * 4);
                int x = from % size + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
                int y = from / size + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
                int p = y * size + x;
                if (x >= 1 && y >= 1 && x < size - 1 && y < size - 1 && !blob.contains(p)) {
                    blob.add(p);
                }
            }
            if (blob.stream().anyMatch(p -> nearTaken(p, size, taken))) {
                continue;
            }
            taken.addAll(blob);
            Set<Integer> in = new HashSet<>(blob);
            for (int p : blob) {
                int x = p % size;
                int y = p / size;
                boolean lit = !in.contains(p - size) && !in.contains(p - 1);
                boolean shadow = !in.contains(p + size) && !in.contains(p + 1);
                int mid = (ore.size() - 1) / 2;
                int shade = lit ? ore.size() - 1 : shadow ? 0 : mid;
                t.set(x, y, ore.get(shade));
            }
            c++;
        }
        return t;
    }

    private static boolean nearTaken(int p, int size, Set<Integer> taken) {
        int x = p % size;
        int y = p / size;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (taken.contains((y + dy) * size + x + dx)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Horizontal boards with grain, a dark seam under each board and one end joint per board. */
    public static Texture planks(int size, Ramp ramp, long seed) {
        checkSize(size);
        Texture t = Texture.square(size);
        int board = size / 4;
        int top = ramp.size() - 1;
        for (int y = 0; y < size; y++) {
            int b = y / board;
            int row = y % board;
            int joint = (int) (Noise.hash(b, 7, seed) * size);
            for (int x = 0; x < size; x++) {
                int shade;
                if (row == board - 1) {
                    shade = 0;
                } else if (x == joint) {
                    shade = Math.min(1, top);
                } else {
                    double grain = Noise.value(x, b * 31 + row, Math.max(2, size / 4), size, size * 8, seed)
                            + 0.25 * Noise.hash(x, y, seed + 3);
                    shade = 1 + (int) Math.min(top - 1, grain * top * 0.95);
                    if (row == 0 && shade < top) {
                        shade++;
                    }
                }
                t.set(x, y, ramp.get(Math.min(top, shade)));
            }
        }
        return t;
    }

    /**
     * Running-bond bricks with mortar lines and a lit top edge on each brick.
     *
     * @param mortar mortar colours; null gives light grey
     */
    public static Texture bricks(int size, Ramp brick, Ramp mortar, long seed) {
        checkSize(size);
        if (mortar == null) {
            mortar = Ramp.from(0xFFA8A29A, 3);
        }
        Texture t = Texture.square(size);
        int course = size / 4;
        int width = size / 2;
        int top = brick.size() - 1;
        for (int y = 0; y < size; y++) {
            int c = y / course;
            int row = y % course;
            int offset = c % 2 == 0 ? 0 : width / 2;
            for (int x = 0; x < size; x++) {
                int bx = Math.floorMod(x - offset, size);
                if (row == course - 1 || bx % width == width - 1) {
                    t.set(x, y, mortar.get(Noise.hash(x, y, seed + 5) < 0.8 ? (mortar.size() - 1) / 2 : 0));
                    continue;
                }
                int brickId = c * 8 + bx / width;
                double v = 0.6 * Noise.hash(brickId, 3, seed) + 0.4 * Noise.hash(x, y, seed + 9);
                int shade = Math.max(0, Math.min(top - 1, (int) (v * top)));
                if (row == 0) {
                    shade = top;
                }
                t.set(x, y, brick.get(shade));
            }
        }
        return t;
    }

    /**
     * Map values to {@code n} shades by rank, giving the middle shades more pixels than the extremes,
     * so a texture never ends up mostly the darkest or lightest colour.
     */
    static int[] quantize(double[] values, int n) {
        double[] weights = new double[n];
        double total = 0;
        for (int i = 0; i < n; i++) {
            weights[i] = 1 + Math.min(i, n - 1 - i);
            total += weights[i];
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double[] cuts = new double[n - 1];
        double acc = 0;
        for (int i = 0; i < n - 1; i++) {
            acc += weights[i] / total;
            cuts[i] = sorted[Math.min(sorted.length - 1, (int) (acc * sorted.length))];
        }
        int[] out = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            int s = 0;
            while (s < cuts.length && values[i] >= cuts[s]) {
                s++;
            }
            out[i] = s;
        }
        return out;
    }

    static void checkSize(int size) {
        if (size != 16 && size != 32 && size != 64) {
            throw new TextureException("pattern size must be 16, 32 or 64, not " + size);
        }
    }
}
