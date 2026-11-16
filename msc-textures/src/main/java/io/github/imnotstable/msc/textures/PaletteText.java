package io.github.imnotstable.msc.textures;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pixel art as text, the same format {@code msc-core}'s {@code texture} and {@code texture_view}
 * use: a palette of single characters and one string per pixel row. {@code .} is always transparent.
 *
 * <pre>
 * palette: .=transparent a=#3a0a12 b=#d4263a
 * ..aa..
 * .abba.
 * </pre>
 */
public final class PaletteText {
    static final String SYMBOLS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#@%&*+~^!$";

    private PaletteText() {
    }

    /**
     * @param palette entries such as {@code s=#7f7f7f}; several may share one string, separated by spaces
     * @param rows    one string per pixel row, top first
     */
    public static Texture parse(List<String> palette, List<String> rows) {
        Map<Character, Integer> colors = new LinkedHashMap<>();
        colors.put('.', Colors.TRANSPARENT);
        for (String line : palette) {
            for (String e : line.trim().split("\\s+")) {
                if (e.isEmpty() || e.equals("palette:")) {
                    continue;
                }
                if (e.length() < 3 || e.charAt(1) != '=') {
                    throw new TextureException("bad palette entry \"" + e + "\"; use symbol=#rrggbb, for example r=#d4263a");
                }
                colors.put(e.charAt(0), Colors.parse(e.substring(2)));
            }
        }
        if (rows.isEmpty()) {
            throw new TextureException("texture has no rows");
        }
        int width = rows.getFirst().length();
        Texture t = new Texture(width, rows.size());
        for (int y = 0; y < rows.size(); y++) {
            String row = rows.get(y);
            if (row.length() != width) {
                throw new TextureException("row " + (y + 1) + " has " + row.length() + " pixels, expected " + width);
            }
            for (int x = 0; x < width; x++) {
                Integer argb = colors.get(row.charAt(x));
                if (argb == null) {
                    throw new TextureException("symbol '" + row.charAt(x) + "' in row " + (y + 1) + " is not in the palette");
                }
                t.set(x, y, argb);
            }
        }
        return t;
    }

    /**
     * {@link #render} for any texture: one with more opaque colours than there are symbols is shown with
     * its closest colours merged, after a line that says so. When the pixels also differ in partial alpha,
     * the alpha is snapped to a few levels if that at least doubles the RGB colours that can be shown, or
     * if merging alone cannot fit.
     */
    public static String view(Texture t) {
        int colors = t.colors().size();
        if (colors <= SYMBOLS.length()) {
            return render(t);
        }
        Texture shown = fit(t);
        Texture snapped = snapAlpha(t);
        if (!snapped.equals(t)) {
            Texture alt = fit(snapped);
            if (shown == null || rgbCount(alt) > 2 * rgbCount(shown)) {
                shown = alt;
            }
        }
        return colors + " colors merged to " + shown.colors().size() + " to show them; the file is unchanged\n"
                + render(shown);
    }

    /** The texture merged to as many colours as the symbols allow, or null when even one RGB colour is too many. */
    private static Texture fit(Texture t) {
        Texture best = null;
        int lo = 1;
        int hi = SYMBOLS.length();
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Texture q = Edits.quantize(t, mid);
            if (q.colors().size() <= SYMBOLS.length()) {
                best = q;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    private static int rgbCount(Texture t) {
        Set<Integer> rgb = new HashSet<>();
        t.colors().forEach(c -> rgb.add(c & 0xFFFFFF));
        return rgb.size();
    }

    /** Partial alpha snapped to 64, 128, 192 or 255; transparent stays transparent. */
    private static Texture snapAlpha(Texture t) {
        Texture out = t.copy();
        for (int y = 0; y < t.height(); y++) {
            for (int x = 0; x < t.width(); x++) {
                int c = t.get(x, y);
                if (Colors.opaque(c)) {
                    int level = Math.max(1, Math.round(Colors.alpha(c) / 64f));
                    out.set(x, y, Math.min(255, level * 64) << 24 | c & 0xFFFFFF);
                }
            }
        }
        return out;
    }

    /** The texture as a size line, a palette line and pixel rows. */
    public static String render(Texture t) {
        Map<Integer, Character> symbols = new LinkedHashMap<>();
        List<String> rows = new ArrayList<>(t.height());
        for (int y = 0; y < t.height(); y++) {
            StringBuilder row = new StringBuilder(t.width());
            for (int x = 0; x < t.width(); x++) {
                int argb = t.get(x, y);
                if (!Colors.opaque(argb)) {
                    row.append('.');
                    continue;
                }
                Character c = symbols.get(argb);
                if (c == null) {
                    if (symbols.size() >= SYMBOLS.length()) {
                        throw new TextureException("texture has more than " + SYMBOLS.length() + " colors to show as text");
                    }
                    c = SYMBOLS.charAt(symbols.size());
                    symbols.put(argb, c);
                }
                row.append(c);
            }
            rows.add(row.toString());
        }
        StringBuilder out = new StringBuilder();
        out.append(t.width()).append('x').append(t.height()).append("\npalette: .=transparent");
        symbols.forEach((argb, c) -> out.append(' ').append(c).append('=').append(Colors.format(argb)));
        for (String row : rows) {
            out.append('\n').append(row);
        }
        return out.toString();
    }
}
