package io.github.imnotstable.msc.textures;

/** Seeded, tileable value noise. The same seed always gives the same texture. */
final class Noise {
    private Noise() {
    }

    /** A hash of (x, y, seed) in [0, 1). */
    static double hash(int x, int y, long seed) {
        long h = seed * 0x9E3779B97F4A7C15L + x * 0xBF58476D1CE4E5B9L + y * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        h *= 0xD6E8FEB86659FD93L;
        h ^= h >>> 32;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Smooth noise that wraps every {@code period} pixels, with lattice cells {@code cell} pixels wide. */
    static double value(int x, int y, int cell, int periodX, int periodY, long seed) {
        int cellsX = Math.max(1, periodX / cell);
        int cellsY = Math.max(1, periodY / cell);
        double fx = (double) x / cell;
        double fy = (double) y / cell;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        double tx = smooth(fx - x0);
        double ty = smooth(fy - y0);
        double a = hash(Math.floorMod(x0, cellsX), Math.floorMod(y0, cellsY), seed);
        double b = hash(Math.floorMod(x0 + 1, cellsX), Math.floorMod(y0, cellsY), seed);
        double c = hash(Math.floorMod(x0, cellsX), Math.floorMod(y0 + 1, cellsY), seed);
        double d = hash(Math.floorMod(x0 + 1, cellsX), Math.floorMod(y0 + 1, cellsY), seed);
        return lerp(lerp(a, b, tx), lerp(c, d, tx), ty);
    }

    /** Layered noise for a {@code w} x {@code h} tile: two smooth octaves plus per-pixel grit. */
    static double[] field(int w, int h, double grit, long seed) {
        double[] out = new double[w * h];
        int big = Math.max(2, Math.min(w, h) / 2);
        int small = Math.max(1, Math.min(w, h) / 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double v = 0.5 * value(x, y, big, w, h, seed)
                        + 0.35 * value(x, y, small, w, h, seed + 1)
                        + grit * hash(x, y, seed + 2);
                out[y * w + x] = v;
            }
        }
        return out;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
