package io.github.imnotstable.msc.textures;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pixel art as text, the same format {@code msc-core}'s {@code texture_create} and {@code texture_view}
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
        Texture t = new Texture(Math.max(width, 1), rows.size());
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
                        throw new TextureException("texture has more than " + SYMBOLS.length() + " colors; reduce it with quantize first");
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
