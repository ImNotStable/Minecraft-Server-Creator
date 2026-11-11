package io.github.imnotstable.msc.textures;

import java.util.Locale;

/** ARGB colour helpers: parsing, formatting and HSL conversion. */
public final class Colors {
    public static final int TRANSPARENT = 0;

    private Colors() {
    }

    /** Parse {@code #rgb}, {@code #rrggbb}, {@code #rrggbbaa} (with or without {@code #}) or {@code transparent}. */
    public static int parse(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.equals("transparent") || t.equals("none")) {
            return TRANSPARENT;
        }
        String hex = t.startsWith("#") ? t.substring(1) : t;
        try {
            switch (hex.length()) {
                case 3 -> {
                    int v = Integer.parseInt(hex, 16);
                    int r = (v >> 8) & 0xF;
                    int g = (v >> 4) & 0xF;
                    int b = v & 0xF;
                    return 0xFF000000 | (r * 17) << 16 | (g * 17) << 8 | (b * 17);
                }
                case 6 -> {
                    return 0xFF000000 | Integer.parseInt(hex, 16);
                }
                case 8 -> {
                    long rgba = Long.parseLong(hex, 16);
                    return (int) ((rgba & 0xFF) << 24 | rgba >>> 8);
                }
                default -> {
                    // Falls through to the error below.
                }
            }
        } catch (NumberFormatException e) {
            // Falls through to the error below.
        }
        throw new TextureException("bad color \"" + text + "\"; use #rrggbb, #rrggbbaa or transparent");
    }

    /** {@code #rrggbb}, or {@code #rrggbbaa} when not fully opaque. */
    public static String format(int argb) {
        int a = alpha(argb);
        String rgb = String.format(Locale.ROOT, "#%06x", argb & 0xFFFFFF);
        return a == 0xFF ? rgb : rgb + String.format(Locale.ROOT, "%02x", a);
    }

    public static int alpha(int argb) {
        return argb >>> 24;
    }

    public static boolean opaque(int argb) {
        return alpha(argb) != 0;
    }

    /** Relative luminance, 0 to 1, ignoring alpha. */
    public static double luminance(int argb) {
        return (0.2126 * ((argb >> 16) & 0xFF) + 0.7152 * ((argb >> 8) & 0xFF) + 0.0722 * (argb & 0xFF)) / 255.0;
    }

    /** Hue in degrees [0, 360), saturation and lightness in [0, 1]. */
    public static double[] toHsl(int argb) {
        double r = ((argb >> 16) & 0xFF) / 255.0;
        double g = ((argb >> 8) & 0xFF) / 255.0;
        double b = (argb & 0xFF) / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double l = (max + min) / 2;
        double h = 0;
        double s = 0;
        double d = max - min;
        if (d > 1e-9) {
            s = d / (1 - Math.abs(2 * l - 1));
            if (max == r) {
                h = 60 * (((g - b) / d) % 6);
            } else if (max == g) {
                h = 60 * ((b - r) / d + 2);
            } else {
                h = 60 * ((r - g) / d + 4);
            }
        }
        return new double[] {(h + 360) % 360, Math.min(1, s), l};
    }

    public static int fromHsl(double h, double s, double l, int alpha) {
        double hue = ((h % 360) + 360) % 360;
        double sat = clamp(s);
        double light = clamp(l);
        double c = (1 - Math.abs(2 * light - 1)) * sat;
        double x = c * (1 - Math.abs((hue / 60) % 2 - 1));
        double m = light - c / 2;
        double r;
        double g;
        double b;
        if (hue < 60) {
            r = c; g = x; b = 0;
        } else if (hue < 120) {
            r = x; g = c; b = 0;
        } else if (hue < 180) {
            r = 0; g = c; b = x;
        } else if (hue < 240) {
            r = 0; g = x; b = c;
        } else if (hue < 300) {
            r = x; g = 0; b = c;
        } else {
            r = c; g = 0; b = x;
        }
        return (alpha & 0xFF) << 24 | channel(r + m) << 16 | channel(g + m) << 8 | channel(b + m);
    }

    private static int channel(double v) {
        return (int) Math.round(clamp(v) * 255);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
