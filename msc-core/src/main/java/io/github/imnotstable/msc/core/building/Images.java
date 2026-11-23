package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import io.github.imnotstable.msc.renderer.BlockColors;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Pictures as blocks (ADR 0082): an image scaled to a size and matched pixel by pixel to the nearest block colour of a
 * palette, optionally dithered, laid on a wall or on the ground (map art). Transparent pixels leave the world as it is.
 */
final class Images {
    static final Map<String, List<String>> PALETTES = Map.of(
            "wool", dyed("_wool"),
            "concrete", dyed("_concrete"),
            "terracotta", with(dyed("_terracotta"), "terracotta"),
            "glass", dyed("_stained_glass"),
            "all", with(with(with(dyed("_wool"), dyed("_concrete")), dyed("_terracotta")), "terracotta", "stone", "andesite",
                    "diorite", "granite", "deepslate", "blackstone", "sandstone", "red_sandstone", "oak_planks",
                    "spruce_planks", "birch_planks", "dark_oak_planks", "cherry_planks", "mangrove_planks", "quartz_block",
                    "gold_block", "iron_block", "diamond_block", "emerald_block", "lapis_block", "redstone_block",
                    "copper_block", "oxidized_copper", "prismarine", "dark_prismarine", "nether_bricks", "netherrack",
                    "end_stone", "purpur_block", "mud_bricks", "packed_mud", "moss_block", "snow_block", "obsidian",
                    "amethyst_block", "honeycomb_block", "slime_block", "sea_lantern", "bone_block[axis=y]",
                    "clay", "calcite", "tuff", "dripstone_block", "pale_oak_planks", "resin_bricks"));
    static final List<String> PLANES = List.of("x", "z", "floor");

    private Images() {
    }

    private static List<String> dyed(String suffix) {
        List<String> out = new ArrayList<>();
        for (String d : Things.DYES) {
            out.add(d + suffix);
        }
        return out;
    }

    private static List<String> with(List<String> base, String... more) {
        List<String> out = new ArrayList<>(base);
        out.addAll(List.of(more));
        return out;
    }

    private static List<String> with(List<String> base, List<String> more) {
        List<String> out = new ArrayList<>(base);
        out.addAll(more);
        return out;
    }

    /**
     * The image in {@code file} as a structure starting at 0,0,0: {@code width} blocks across (height by the image's
     * aspect unless given), on plane x (a wall along x, facing z), z (a wall along z) or floor (top of the image north).
     */
    static BlockGrid grid(Path file, String world, int width, int height, String plane, String palette, boolean dither)
            throws IOException {
        BufferedImage src = ImageIO.read(file.toFile());
        if (src == null) {
            throw new ToolException(file.getFileName() + " is not an image this tool reads (PNG, JPEG, GIF or BMP)");
        }
        List<String> blocks = PALETTES.get(palette);
        if (blocks == null) {
            throw new ToolException("unknown palette " + palette + "; use one of " + PALETTES.keySet().stream().sorted().toList());
        }
        if (!PLANES.contains(plane)) {
            throw new ToolException("plane is x, z or floor, got " + plane);
        }
        int w = width;
        int h = height > 0 ? height : Math.max(1, (int) Math.round((double) src.getHeight() * w / src.getWidth()));
        if (h > 512) {
            throw new ToolException("the image would be " + w + "x" + h + " blocks; give h= at most 512");
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        int[] rgb = new int[blocks.size()];
        for (int i = 0; i < rgb.length; i++) {
            rgb[i] = BlockColors.side(blocks.get(i).replaceAll("\\[.*", "")); // the colour table goes by bare id
        }
        double[][] err = new double[h + 1][(w + 2) * 3];
        int[] pick = new int[w * h];
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int argb = img.getRGB(col, row);
                if ((argb >>> 24) < 128) {
                    pick[row * w + col] = -1;
                    continue;
                }
                double r = ((argb >> 16) & 0xff) + err[row][(col + 1) * 3];
                double gr = ((argb >> 8) & 0xff) + err[row][(col + 1) * 3 + 1];
                double b = (argb & 0xff) + err[row][(col + 1) * 3 + 2];
                int best = nearest(rgb, r, gr, b);
                pick[row * w + col] = best;
                if (dither) {
                    double er = r - (rgb[best] >> 16 & 0xff);
                    double eg = gr - (rgb[best] >> 8 & 0xff);
                    double eb = b - (rgb[best] & 0xff);
                    spread(err[row], col + 2, er, eg, eb, 7 / 16.0);
                    spread(err[row + 1], col, er, eg, eb, 3 / 16.0);
                    spread(err[row + 1], col + 1, er, eg, eb, 5 / 16.0);
                    spread(err[row + 1], col + 2, er, eg, eb, 1 / 16.0);
                }
            }
        }
        int sx = plane.equals("z") ? 1 : w;
        int sy = plane.equals("floor") ? 1 : h;
        int sz = plane.equals("x") ? 1 : plane.equals("z") ? w : h;
        Region region = Region.of(world, 0, 0, 0, sx - 1, sy - 1, sz - 1);
        int[] cells = new int[sx * sy * sz];
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int x;
                int y;
                int z;
                switch (plane) {
                    case "x" -> {
                        x = col;
                        y = h - 1 - row;
                        z = 0;
                    }
                    case "z" -> {
                        x = 0;
                        y = h - 1 - row;
                        z = col;
                    }
                    default -> {
                        x = col;
                        y = 0;
                        z = row;
                    }
                }
                cells[region.index(x, y, z)] = pick[row * w + col];
            }
        }
        return new BlockGrid(region, blocks.stream().map(s -> "minecraft:" + s).toList(), cells);
    }

    private static void spread(double[] line, int col, double r, double g, double b, double share) {
        if (col * 3 + 2 < line.length) {
            line[col * 3] += r * share;
            line[col * 3 + 1] += g * share;
            line[col * 3 + 2] += b * share;
        }
    }

    /** The palette entry closest to a colour by the "redmean" distance, which tracks the eye better than plain RGB. */
    static int nearest(int[] rgb, double r, double g, double b) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < rgb.length; i++) {
            double pr = rgb[i] >> 16 & 0xff;
            double pg = rgb[i] >> 8 & 0xff;
            double pb = rgb[i] & 0xff;
            double mean = (r + pr) / 2;
            double dr = r - pr;
            double dg = g - pg;
            double db = b - pb;
            double d = (2 + mean / 256) * dr * dr + 4 * dg * dg + (2 + (255 - mean) / 256) * db * db;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }
}
