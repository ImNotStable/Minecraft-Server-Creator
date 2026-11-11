package io.github.imnotstable.msc.textures;

import java.util.ArrayList;
import java.util.List;

/**
 * A shade ramp, darkest first. Pixel-art shading uses a few shades of one colour with the hue nudged
 * towards cool in shadow and warm in light, which reads better than plain darker and lighter copies.
 */
public record Ramp(List<Integer> colors) {
    public Ramp {
        colors = List.copyOf(colors);
        if (colors.isEmpty()) {
            throw new TextureException("a ramp needs at least one color");
        }
    }

    /**
     * Build {@code count} shades around {@code base}, which lands in the middle.
     *
     * @param count 2 to 9 shades; 4 or 5 is typical
     */
    public static Ramp from(int base, int count) {
        return from(base, count, 1.0);
    }

    /**
     * @param contrast 0.2 to 1.5; 1 suits items, about 0.6 suits block textures such as stone
     */
    public static Ramp from(int base, int count, double contrast) {
        if (contrast < 0.2 || contrast > 1.5) {
            throw new TextureException("ramp contrast must be 0.2 to 1.5, not " + contrast);
        }
        if (count < 2 || count > 9) {
            throw new TextureException("ramp size must be 2 to 9, not " + count);
        }
        double[] hsl = Colors.toHsl(base);
        int mid = (count - 1) / 2;
        List<Integer> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double t = mid == 0 ? i : (double) (i - mid) / Math.max(mid, count - 1 - mid);
            double light = t < 0 ? hsl[2] * (1 + 0.55 * contrast * t) : hsl[2] + (0.94 - hsl[2]) * Math.min(1, 0.75 * contrast) * t;
            double hue = hsl[0] + shift(hsl[0], t);
            double sat = t < 0 ? Math.min(1, hsl[1] * (1 - 0.1 * t)) : hsl[1] * (1 - 0.45 * t * t);
            out.add(Colors.fromHsl(hue, sat, light, 0xFF));
        }
        return new Ramp(out);
    }

    /** Hue shift in degrees: shadows move towards blue (240), light towards yellow (60), at most 10. */
    private static double shift(double hue, double t) {
        double target = t < 0 ? 240 : 60;
        double diff = ((target - hue + 540) % 360) - 180;
        return Math.signum(diff) * Math.min(Math.abs(diff), 10) * Math.abs(t);
    }

    /** Parse colours such as {@code #3a0a12 #8c1a2b #d4263a}. */
    public static Ramp parse(List<String> colors) {
        List<Integer> out = new ArrayList<>();
        for (String entry : colors) {
            for (String c : entry.trim().split("[\\s,]+")) {
                if (!c.isEmpty()) {
                    out.add(Colors.parse(c));
                }
            }
        }
        return new Ramp(out);
    }

    public int size() {
        return colors.size();
    }

    /** Shade {@code i}, clamped to the ramp. */
    public int get(int i) {
        return colors.get(Math.max(0, Math.min(colors.size() - 1, i)));
    }

    public int darkest() {
        return colors.getFirst();
    }

    public int lightest() {
        return colors.getLast();
    }

    /** Shade at fraction {@code f} in [0, 1] of the ramp. */
    public int at(double f) {
        return get((int) Math.round(f * (colors.size() - 1)));
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        for (int c : colors) {
            out.append(out.isEmpty() ? "" : " ").append(Colors.format(c));
        }
        return out.toString();
    }
}
