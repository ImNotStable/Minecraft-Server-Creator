package io.github.imnotstable.msc.core.texture;

import io.github.imnotstable.msc.core.tool.ToolException;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pixel art as text: a palette of single characters and rows of those characters. This is how a
 * model draws a texture without an image model, and how it reads one back.
 *
 * <pre>
 * palette: .=transparent s=#7f7f7f r=#d4263a
 * ssssssssssssssss
 * ssrrssssssssrrss
 * </pre>
 */
public final class PaletteTexture {
    private static final String SYMBOLS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#@%&*+~^!$";

    /** @param warnings things that will look wrong in game but do not stop the texture being written */
    public record Parsed(BufferedImage image, List<String> warnings) {
    }

    private PaletteTexture() {
    }

    /**
     * @param palette entries such as {@code s=#7f7f7f}, {@code g=#00ff0080} (with alpha) or {@code .=transparent}
     * @param rows    one string per pixel row, top first
     */
    public static Parsed parse(List<String> palette, List<String> rows) {
        Map<Character, Integer> colors = new LinkedHashMap<>();
        colors.put('.', 0);
        for (String entry : palette) {
            for (String e : entry.trim().split("\\s+")) {
                if (e.isEmpty()) {
                    continue;
                }
                if (e.length() < 3 || e.charAt(1) != '=') {
                    throw new ToolException("bad palette entry \"" + e + "\"; use symbol=#rrggbb, for example r=#d4263a");
                }
                colors.put(e.charAt(0), color(e.substring(2)));
            }
        }
        if (rows.isEmpty()) {
            throw new ToolException("texture has no rows");
        }
        int height = rows.size();
        int width = rows.get(0).length();
        if (width == 0 || width > 512 || height > 512 * 64) {
            throw new ToolException("texture size " + width + "x" + height + " is out of range");
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            String row = rows.get(y);
            if (row.length() != width) {
                throw new ToolException("row " + (y + 1) + " has " + row.length() + " pixels, expected " + width);
            }
            for (int x = 0; x < width; x++) {
                Integer argb = colors.get(row.charAt(x));
                if (argb == null) {
                    throw new ToolException("symbol '" + row.charAt(x) + "' in row " + (y + 1) + " is not in the palette");
                }
                image.setRGB(x, y, argb);
            }
        }
        List<String> warnings = new ArrayList<>();
        if (Integer.bitCount(width) != 1) {
            warnings.add("width " + width + " is not a power of two; use 16, 32 or 64");
        }
        if (width != height && height % width != 0) {
            warnings.add("height " + height + " is not a multiple of width " + width + "; textures are square, or stacked square frames for animation");
        } else if (width != height) {
            warnings.add((height / width) + " stacked frames: this needs a .png.mcmeta animation file to display correctly");
        }
        if (colors.size() - 1 > 16) {
            warnings.add((colors.size() - 1) + " colors; vanilla-style textures use 4 to 12");
        }
        return new Parsed(image, warnings);
    }

    static int color(String text) {
        String t = text.trim().toLowerCase();
        if (t.equals("transparent") || t.equals("none")) {
            return 0;
        }
        String hex = t.startsWith("#") ? t.substring(1) : t;
        try {
            if (hex.length() == 3) {
                int r = Character.digit(hex.charAt(0), 16);
                int g = Character.digit(hex.charAt(1), 16);
                int b = Character.digit(hex.charAt(2), 16);
                if (r < 0 || g < 0 || b < 0) {
                    throw new NumberFormatException();
                }
                return 0xFF000000 | (r * 17) << 16 | (g * 17) << 8 | (b * 17);
            }
            if (hex.length() == 6) {
                return 0xFF000000 | Integer.parseInt(hex, 16);
            }
            if (hex.length() == 8) {
                long rgba = Long.parseLong(hex, 16);
                return (int) ((rgba & 0xFF) << 24 | rgba >>> 8);
            }
        } catch (NumberFormatException e) {
            // Falls through to the error below.
        }
        throw new ToolException("bad color \"" + text + "\"; use #rrggbb, #rrggbbaa or transparent");
    }

    /** Show an image as a palette and rows. Fails when it has more colors than there are symbols. */
    public static String render(BufferedImage image) {
        Map<Integer, Character> symbols = new LinkedHashMap<>();
        StringBuilder rows = new StringBuilder();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                if ((argb >>> 24) == 0) {
                    rows.append('.');
                    continue;
                }
                Character c = symbols.get(argb);
                if (c == null) {
                    if (symbols.size() >= SYMBOLS.length()) {
                        throw new ToolException("image has more than " + SYMBOLS.length() + " colors; not pixel art this tool can show");
                    }
                    c = SYMBOLS.charAt(symbols.size());
                    symbols.put(argb, c);
                }
                rows.append(c);
            }
            rows.append('\n');
        }
        StringBuilder out = new StringBuilder();
        out.append(image.getWidth()).append('x').append(image.getHeight()).append("\npalette: .=transparent");
        symbols.forEach((argb, c) -> {
            out.append(' ').append(c).append("=#").append(String.format("%06x", argb & 0xFFFFFF));
            if ((argb >>> 24) != 0xFF) {
                out.append(String.format("%02x", argb >>> 24));
            }
        });
        return out.append('\n').append(rows).toString().stripTrailing();
    }
}
