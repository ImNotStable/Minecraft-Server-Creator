package io.github.imnotstable.msc.textures;

import java.util.Locale;

/**
 * A one-line description of a texture, so an AI can check its own work without seeing the image:
 * size, colour count, opaque share and main hue.
 */
public record Stats(int width, int height, int colors, double opaqueFraction, int mainHue, double mainHueFraction,
        boolean partialAlpha) {

    /** Hue buckets are 30 degrees wide, centred on 0, 30, 60 and so on; greys (saturation below 0.2) are skipped. */
    public static Stats of(Texture t) {
        int[] buckets = new int[12];
        int opaque = 0;
        int saturated = 0;
        boolean partial = false;
        for (int y = 0; y < t.height(); y++) {
            for (int x = 0; x < t.width(); x++) {
                int c = t.get(x, y);
                int a = Colors.alpha(c);
                if (a == 0) {
                    continue;
                }
                partial |= a != 0xFF;
                opaque++;
                double[] hsl = Colors.toHsl(c);
                if (hsl[1] >= 0.2 && hsl[2] > 0.05 && hsl[2] < 0.95) {
                    buckets[(int) Math.round(hsl[0] / 30) % 12]++;
                    saturated++;
                }
            }
        }
        int best = 0;
        for (int i = 1; i < 12; i++) {
            if (buckets[i] > buckets[best]) {
                best = i;
            }
        }
        double total = (double) t.width() * t.height();
        return new Stats(t.width(), t.height(), t.colors().size(), opaque / total,
                saturated == 0 ? -1 : best * 30, opaque == 0 ? 0 : buckets[best] / (double) opaque, partial);
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        out.append(width).append('x').append(height).append(", ").append(colors).append(" colors, ")
                .append(Math.round(opaqueFraction * 100)).append("% opaque");
        if (mainHue < 0) {
            out.append(", grey");
        } else {
            out.append(String.format(Locale.ROOT, ", main hue %d (%s) on %d%% of opaque pixels", mainHue,
                    hueName(mainHue), Math.round(mainHueFraction * 100)));
        }
        if (partialAlpha) {
            out.append(", has semi-transparent pixels");
        }
        if (height != width && width > 0 && height % width == 0) {
            out.append(", ").append(height / width).append(" frames");
        }
        return out.toString();
    }

    private static String hueName(int hue) {
        String[] names = {"red", "orange", "yellow", "yellow-green", "green", "green", "cyan", "azure", "blue",
                "violet", "magenta", "pink"};
        return names[hue / 30 % 12];
    }
}
