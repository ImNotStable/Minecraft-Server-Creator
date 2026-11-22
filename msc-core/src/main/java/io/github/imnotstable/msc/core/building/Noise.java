package io.github.imnotstable.msc.core.building;

/**
 * Smooth 2D gradient noise, the same for the same seed and position, so a dry run, a repeat and an undo-and-redo give
 * the same land (ADR 0075). Values are in world blocks: {@code scale} is the size of one hill or patch.
 */
final class Noise {
    private Noise() {
    }

    /** Gradient noise at a point, in about -1..1. */
    static double gradient(long seed, double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fz = z - z0;
        double u = fade(fx);
        double v = fade(fz);
        double a = dot(seed, x0, z0, fx, fz);
        double b = dot(seed, x0 + 1, z0, fx - 1, fz);
        double c = dot(seed, x0, z0 + 1, fx, fz - 1);
        double d = dot(seed, x0 + 1, z0 + 1, fx - 1, fz - 1);
        // A 2D gradient noise reaches at most sqrt(2)/2; scale it to about -1..1.
        return Math.sqrt(2) * lerp(v, lerp(u, a, b), lerp(u, c, d));
    }

    /** Fractal noise: {@code octaves} layers, each half the size and half the height of the one before, in about -1..1. */
    static double fbm(long seed, double x, double z, double scale, int octaves) {
        double sum = 0;
        double amp = 1;
        double norm = 0;
        double f = 1 / scale;
        for (int i = 0; i < octaves; i++) {
            sum += amp * gradient(seed + i * 0x632BE59BD9B4E019L, x * f, z * f);
            norm += amp;
            amp *= 0.5;
            f *= 2;
        }
        return sum / norm;
    }

    /** Ridged noise in 0..1: sharp crests where the fractal noise crosses zero, for mountain chains. */
    static double ridged(long seed, double x, double z, double scale, int octaves) {
        double sum = 0;
        double amp = 1;
        double norm = 0;
        double f = 1 / scale;
        double weight = 1;
        for (int i = 0; i < octaves; i++) {
            double n = 1 - Math.abs(gradient(seed + i * 0x632BE59BD9B4E019L, x * f, z * f));
            n *= n * weight;
            weight = Math.clamp(n * 2, 0, 1);
            sum += amp * n;
            norm += amp;
            amp *= 0.5;
            f *= 2;
        }
        return Math.clamp(sum / norm, 0, 1);
    }

    /** A well-spread hash of a lattice point, for jitter and picks. */
    static long hash(long seed, long x, long z) {
        return BlockPattern.mix(seed ^ BlockPattern.mix(x * 0x9E3779B97F4A7C15L ^ BlockPattern.mix(z)));
    }

    /** A hash as a number in 0..1. */
    static double unit(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }

    static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.clamp((x - edge0) / (edge1 - edge0), 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static double dot(long seed, int x, int z, double dx, double dz) {
        double angle = unit(hash(seed, x, z)) * 2 * Math.PI;
        return Math.cos(angle) * dx + Math.sin(angle) * dz;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }
}
