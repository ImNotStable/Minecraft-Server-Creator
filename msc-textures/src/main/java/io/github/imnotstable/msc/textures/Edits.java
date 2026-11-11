package io.github.imnotstable.msc.textures;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Reduce to at most {@code max} opaque colours by repeatedly merging the closest pair, weighted by
     * pixel count. Transparency is kept.
     */
    public static Texture quantize(Texture in, int max) {
        if (max < 1) {
            throw new TextureException("max colors must be at least 1");
        }
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int y = 0; y < in.height(); y++) {
            for (int x = 0; x < in.width(); x++) {
                int c = in.get(x, y);
                if (Colors.opaque(c)) {
                    counts.merge(coarse(c, in), 1, Integer::sum);
                }
            }
        }
        if (in.colors().size() <= max) {
            return in.copy();
        }
        List<int[]> clusters = new ArrayList<>();
        Map<Integer, int[]> owner = new HashMap<>();
        counts.forEach((c, n) -> {
            int[] cl = {(c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, n, c};
            clusters.add(cl);
            owner.put(c, cl);
        });
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
                    int[] cl = owner.get(coarse(c, in));
                    out.set(x, y, (c & 0xFF000000) | cl[0] << 16 | cl[1] << 8 | cl[2]);
                }
            }
        }
        return out;
    }

    /** Large images are pre-reduced to 5 bits per channel so merging stays fast. */
    private static int coarse(int c, Texture in) {
        return in.width() * in.height() > 4096 ? c & 0xFFF8F8F8 : c & 0xFFFFFFFF;
    }

    private static double dist(int[] a, int[] b) {
        double dr = a[0] - b[0];
        double dg = a[1] - b[1];
        double db = a[2] - b[2];
        return 0.3 * dr * dr + 0.59 * dg * dg + 0.11 * db * db;
    }
}
