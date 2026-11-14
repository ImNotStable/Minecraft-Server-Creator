package io.github.imnotstable.msc.textures;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Edits on whole textures: outline, recolour onto a ramp, and colour-count reduction. */
public final class Edits {
    private Edits() {
    }

    /** Add a one-pixel outline on transparent pixels next to opaque ones (4-neighbour). */
    public static Texture outline(Texture in, int color) {
        Texture out = in.copy();
        for (int y = 0; y < in.height(); y++) {
            for (int x = 0; x < in.width(); x++) {
                if (Colors.opaque(in.get(x, y))) {
                    continue;
                }
                if (opaqueAt(in, x + 1, y) || opaqueAt(in, x - 1, y) || opaqueAt(in, x, y + 1) || opaqueAt(in, x, y - 1)) {
                    out.set(x, y, color);
                }
            }
        }
        return out;
    }

    private static boolean opaqueAt(Texture t, int x, int y) {
        return t.inside(x, y) && Colors.opaque(t.get(x, y));
    }

    /**
     * Recolour by brightness: the texture's colours, darkest to lightest, are spread over the ramp. Turns
     * a diamond into a ruby, or grey stone into sandstone, keeping all the shading.
     */
    public static Texture recolor(Texture in, Ramp ramp) {
        List<Integer> colors = new ArrayList<>(in.colors());
        colors.sort(Comparator.comparingDouble(Colors::luminance));
        Map<Integer, Integer> map = new HashMap<>();
        for (int i = 0; i < colors.size(); i++) {
            double f = colors.size() == 1 ? 0.5 : (double) i / (colors.size() - 1);
            int c = ramp.at(f);
            map.put(colors.get(i), (Colors.alpha(colors.get(i)) << 24) | (c & 0xFFFFFF));
        }
        Texture out = in.copy();
        for (int y = 0; y < in.height(); y++) {
            for (int x = 0; x < in.width(); x++) {
                Integer c = map.get(in.get(x, y));
                if (c != null) {
                    out.set(x, y, c);
                }
            }
        }
        return out;
    }

    /** Merging starts from at most this many clusters, so it stays fast regardless of texture size. */
    static final int MAX_CLUSTERS = 256;

    /**
     * Reduce to at most {@code max} opaque RGB colours by repeatedly merging the closest pair, weighted by
     * pixel count. Each pixel keeps its alpha, so colours that differ only in alpha stay apart, and
     * transparent pixels stay as they are. A texture with more than {@code max} RGB colours ends with
     * {@code min(max, MAX_CLUSTERS)} of them: colours are first grouped into {@link #MAX_CLUSTERS} start
     * clusters (the most used fine buckets, each other bucket joining its nearest one), which bounds the work.
     */
    public static Texture quantize(Texture in, int max) {
        if (max < 1) {
            throw new TextureException("max colors must be at least 1");
        }
        if (in.colors().size() <= max) {
            return in.copy();
        }
        int mask = bucketMask(in);
        Map<Integer, long[]> buckets = new LinkedHashMap<>();
        for (int y = 0; y < in.height(); y++) {
            for (int x = 0; x < in.width(); x++) {
                int c = in.get(x, y);
                if (Colors.opaque(c)) {
                    long[] s = buckets.computeIfAbsent(c & mask, k -> new long[4]);
                    s[0] += (c >> 16) & 0xFF;
                    s[1] += (c >> 8) & 0xFF;
                    s[2] += c & 0xFF;
                    s[3]++;
                }
            }
        }
        List<Map.Entry<Integer, long[]>> byUse = new ArrayList<>(buckets.entrySet());
        byUse.sort(Comparator.comparingLong(e -> -e.getValue()[3]));
        int seeds = Math.min(MAX_CLUSTERS, byUse.size());
        List<long[]> sums = new ArrayList<>();
        List<int[]> clusters = new ArrayList<>();
        Map<Integer, int[]> owner = new HashMap<>();
        for (int i = 0; i < seeds; i++) {
            sums.add(byUse.get(i).getValue().clone());
            clusters.add(mean(byUse.get(i).getValue()));
        }
        int[] home = new int[byUse.size()];
        for (int i = seeds; i < byUse.size(); i++) {
            int[] b = mean(byUse.get(i).getValue());
            double best = Double.MAX_VALUE;
            for (int j = 0; j < seeds; j++) {
                double d = dist(b, clusters.get(j));
                if (d < best) {
                    best = d;
                    home[i] = j;
                }
            }
        }
        for (int i = seeds; i < byUse.size(); i++) {
            long[] to = sums.get(home[i]);
            long[] from = byUse.get(i).getValue();
            for (int k = 0; k < 4; k++) {
                to[k] += from[k];
            }
        }
        for (int j = 0; j < seeds; j++) {
            clusters.set(j, mean(sums.get(j)));
        }
        for (int i = 0; i < byUse.size(); i++) {
            owner.put(byUse.get(i).getKey(), clusters.get(i < seeds ? i : home[i]));
        }
        while (clusters.size() > max) {
            int bi = 0;
            int bj = 1;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < clusters.size(); i++) {
                for (int j = i + 1; j < clusters.size(); j++) {
                    int[] a = clusters.get(i);
                    int[] b = clusters.get(j);
                    double d = dist(a, b) * a[3] * b[3] / (double) (a[3] + b[3]);
                    if (d < best) {
                        best = d;
                        bi = i;
                        bj = j;
                    }
                }
            }
            int[] a = clusters.get(bi);
            int[] b = clusters.remove(bj);
            int n = a[3] + b[3];
            for (int k = 0; k < 3; k++) {
                a[k] = Math.round((a[k] * (float) a[3] + b[k] * (float) b[3]) / n);
            }
            a[3] = n;
            owner.replaceAll((c, cl) -> cl == b ? a : cl);
        }
        Texture out = in.copy();
        for (int y = 0; y < in.height(); y++) {
            for (int x = 0; x < in.width(); x++) {
                int c = in.get(x, y);
                if (Colors.opaque(c)) {
                    int[] cl = owner.get(c & mask);
                    out.set(x, y, (c & 0xFF000000) | cl[0] << 16 | cl[1] << 8 | cl[2]);
                }
            }
        }
        return out;
    }

    private static int[] mean(long[] s) {
        int n = (int) s[3];
        return new int[] {Math.round((float) s[0] / n), Math.round((float) s[1] / n), Math.round((float) s[2] / n), n};
    }

    /** Fine buckets kept before grouping into start clusters; each bit dropped can cut the count by up to 8. */
    private static final int MAX_BUCKETS = 8 * MAX_CLUSTERS;

    /** The finest RGB mask (8 bits per channel down to 1) that leaves at most {@link #MAX_BUCKETS} buckets. */
    private static int bucketMask(Texture in) {
        for (int bits = 8; bits > 1; bits--) {
            int m = (0xFF << (8 - bits)) & 0xFF;
            int mask = m << 16 | m << 8 | m;
            Set<Integer> buckets = new HashSet<>();
            for (int y = 0; y < in.height() && buckets.size() <= MAX_BUCKETS; y++) {
                for (int x = 0; x < in.width(); x++) {
                    int c = in.get(x, y);
                    if (Colors.opaque(c)) {
                        buckets.add(c & mask);
                    }
                }
            }
            if (buckets.size() <= MAX_BUCKETS) {
                return mask;
            }
        }
        return 0x808080;
    }

    private static double dist(int[] a, int[] b) {
        double dr = a[0] - b[0];
        double dg = a[1] - b[1];
        double db = a[2] - b[2];
        return 0.3 * dr * dr + 0.59 * dg * dg + 0.11 * db * db;
    }
}
