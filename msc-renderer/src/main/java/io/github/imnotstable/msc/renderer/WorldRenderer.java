package io.github.imnotstable.msc.renderer;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;

/**
 * Renders a block grid to a PNG so an AI can look at a build. Pure Java, no GPU and no window:
 * safe on headless servers and CI. Thread-safe; keep one instance so resolved blocks and textures
 * stay cached.
 *
 * <pre>{@code
 * WorldRenderer renderer = new WorldRenderer(new PackTextures(List.of(packFolder)));
 * RenderResult image = renderer.render(grid, View.parse("iso"), RenderOptions.defaults());
 * }</pre>
 */
public final class WorldRenderer {
    private static final int MARGIN = 0xffffff;
    private static final int INK = 0x202020;
    private static final double MAX_PIXELS_PER_BLOCK = 32;
    private static final double ISO_PITCH = 30;

    private final Looks looks;

    /** A renderer that draws every block in its table colour. */
    public WorldRenderer() {
        this(TextureSource.none());
    }

    public WorldRenderer(TextureSource textures) {
        this.looks = new Looks(Objects.requireNonNull(textures, "textures"));
    }

    /**
     * Renders one view of a grid. The grid may be larger than one protocol read (the caller stitches
     * reads together); "leave unchanged" cells (-1) are drawn as air.
     */
    public RenderResult render(BlockGrid grid, View view, RenderOptions options) {
        return render(grid, view, options, List.of());
    }

    /**
     * Renders one view with markers drawn over it, numbered in list order. Markers are not hidden by blocks in
     * front of them; the caption says which ones fall outside the image.
     */
    public RenderResult render(BlockGrid grid, View view, RenderOptions options, List<Marker> markers) {
        Region region = grid.region();
        Frame frame = switch (view) {
            case View.Ortho ortho -> orthoFrame(ortho, region, options);
            case View.Isometric iso -> isoFrame(iso, region, options);
            case View.Camera camera -> cameraFrame(camera, region, options);
        };
        Look[] palette = looks.resolve(grid.palette());
        int[] pixels = new int[frame.imageW * frame.imageH];
        java.util.Arrays.fill(pixels, MARGIN);
        long[] seen = new long[palette.length + 1];
        int n = options.smooth() ? 2 : 1;
        boolean heightCue = view instanceof View.Ortho(View.Side from) && from == View.Side.TOP;
        IntStream.range(0, frame.h).parallel().forEach(row -> {
            Tracer tracer = new Tracer(region.sizeX(), region.sizeY(), region.sizeZ(), grid.blocks(), palette,
                    options.shadows(), options.outlines(), heightCue);
            long[] rowSeen = new long[seen.length];
            double[] ray = new double[6];
            for (int col = 0; col < frame.w; col++) {
                int rs = 0;
                int gs = 0;
                int bs = 0;
                for (int s = 0; s < n * n; s++) {
                    frame.ray(col + (s % n + 0.5) / n, row + (s / n + 0.5) / n, ray);
                    int rgb = tracer.trace(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], frame.footprint, frame.footprintPerT);
                    rs += (rgb >> 16) & 0xff;
                    gs += (rgb >> 8) & 0xff;
                    bs += rgb & 0xff;
                    if (s == 0) {
                        rowSeen[tracer.firstHit < 0 ? palette.length : tracer.firstHit]++;
                    }
                }
                int samples = n * n;
                pixels[(frame.offY + row) * frame.imageW + frame.offX + col] =
                        ((rs / samples) << 16) | ((gs / samples) << 8) | (bs / samples);
            }
            synchronized (seen) {
                for (int i = 0; i < seen.length; i++) {
                    seen[i] += rowSeen[i];
                }
            }
        });
        if (frame.ruler != null) {
            frame.ruler.draw(pixels, frame);
        }
        String markerText = markers.isEmpty() ? "" : "\n" + drawMarkers(pixels, frame, region, markers);
        BufferedImage image = new BufferedImage(frame.imageW, frame.imageH, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, frame.imageW, frame.imageH, pixels, 0, frame.imageW);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int tokens = (int) Math.ceil(frame.imageW * frame.imageH / 750.0);
        String caption = frame.caption + " " + frame.imageW + "x" + frame.imageH + " px, about " + tokens
                + " tokens.\n" + visible(palette, seen, (long) frame.w * frame.h) + markerText;
        return new RenderResult(out.toByteArray(), frame.imageW, frame.imageH, caption, tokens);
    }

    private static final int MARKER_FILL = 0xff00ff;
    private static final int MARKER_EDGE = 0x000000;
    private static final int MAX_MARKERS = 40;

    /** Draws a numbered dot per marker one block above its feet, on the body; returns the caption line. */
    private static String drawMarkers(int[] pixels, Frame frame, Region region, List<Marker> markers) {
        int font = frame.imageW >= 384 ? 2 : 1;
        int dot = 3 * font;
        StringBuilder sb = new StringBuilder("Markers (magenta, numbered): ");
        List<Integer> outside = new ArrayList<>();
        int shown = Math.min(markers.size(), MAX_MARKERS);
        for (int i = 0; i < shown; i++) {
            Marker m = markers.get(i);
            int n = i + 1;
            double[] p = {m.x() - region.min().x(), m.y() - region.min().y() + 1.0, m.z() - region.min().z()};
            int[] at = project(frame, p);
            sb.append(i == 0 ? "" : "; ").append(n).append(' ').append(m.label()).append(" at ")
                    .append(coord(m.x())).append(',').append(coord(m.y())).append(',').append(coord(m.z()));
            if (at == null || at[0] < 0 || at[1] < 0 || at[0] >= frame.imageW || at[1] >= frame.imageH) {
                outside.add(n);
                continue;
            }
            PixelFont.fill(pixels, frame.imageW, frame.imageH, at[0] - dot - font, at[1] - dot - font,
                    2 * (dot + font) + 1, 2 * (dot + font) + 1, MARKER_EDGE);
            PixelFont.fill(pixels, frame.imageW, frame.imageH, at[0] - dot, at[1] - dot, 2 * dot + 1, 2 * dot + 1,
                    MARKER_FILL);
            String label = Integer.toString(n);
            int lx = at[0] + dot + 2 * font;
            int ly = at[1] - PixelFont.HEIGHT * font / 2;
            PixelFont.fill(pixels, frame.imageW, frame.imageH, lx - font, ly - font,
                    PixelFont.width(label, font) + 2 * font, PixelFont.HEIGHT * font + 2 * font, MARGIN);
            PixelFont.draw(pixels, frame.imageW, frame.imageH, lx, ly, label, font, INK);
        }
        sb.append('.');
        if (markers.size() > shown) {
            sb.append(' ').append(markers.size() - shown).append(" more not drawn.");
        }
        if (!outside.isEmpty()) {
            sb.append(" Outside the image: ").append(outside.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(", "))).append('.');
        }
        return sb.toString();
    }

    /** Image pixel of a grid-local point, or null when it is behind a perspective camera. Inverse of Frame.ray. */
    private static int[] project(Frame frame, double[] p) {
        double[] d = new double[3];
        for (int i = 0; i < 3; i++) {
            d[i] = p[i] - frame.origin[i];
        }
        double a = dot(d, frame.r);
        double b = dot(d, frame.u);
        if (frame.perspective) {
            double depth = dot(d, frame.f);
            if (depth <= 1e-6) {
                return null;
            }
            a /= depth;
            b /= depth;
        }
        int x = (int) Math.floor(frame.offX + frame.w / 2.0 + a * frame.scale);
        int y = (int) Math.floor(frame.offY + frame.h / 2.0 - b * frame.scale);
        return new int[] {x, y};
    }

    /** One decimal, none for whole numbers: 12.5, 65, -0.5. */
    private static String coord(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? Long.toString((long) r) : String.format(Locale.ROOT, "%.1f", r);
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /** "Visible: stone_bricks 41%, oak_planks 22%, ..." from what the first sample of each pixel hit. */
    private static String visible(Look[] palette, long[] seen, long total) {
        record Share(String name, long pixels) {
        }
        java.util.Map<String, Long> byName = new java.util.HashMap<>();
        TreeSet<String> guessed = new TreeSet<>();
        for (int i = 0; i < palette.length; i++) {
            if (palette[i] != null && seen[i] > 0) {
                byName.merge(palette[i].name, seen[i], Long::sum);
                if (!palette[i].knownColor) {
                    guessed.add(palette[i].name);
                }
            }
        }
        List<Share> shares = new ArrayList<>();
        byName.forEach((name, count) -> shares.add(new Share(name, count)));
        shares.sort((x, y) -> x.pixels() != y.pixels() ? Long.compare(y.pixels(), x.pixels()) : x.name().compareTo(y.name()));
        StringBuilder sb = new StringBuilder("Visible: ");
        if (shares.isEmpty()) {
            sb.append("nothing (the region is empty or the camera looks away from it)");
        }
        int listed = Math.min(8, shares.size());
        for (int i = 0; i < listed; i++) {
            sb.append(i == 0 ? "" : ", ").append(shares.get(i).name()).append(' ').append(percent(shares.get(i).pixels(), total));
        }
        if (shares.size() > listed) {
            sb.append(", ").append(shares.size() - listed).append(" more");
        }
        if (seen[palette.length] > 0 && !shares.isEmpty()) {
            sb.append("; empty ").append(percent(seen[palette.length], total));
        }
        sb.append('.');
        if (!guessed.isEmpty()) {
            sb.append(" No colour known for ").append(String.join(", ", guessed)).append(": drawn in a made-up colour.");
        }
        return sb.toString();
    }

    private static String percent(long part, long total) {
        long p = Math.round(100.0 * part / total);
        return p == 0 ? "<1%" : p + "%";
    }

    // ---- frames: camera plus image layout ----

    private static final class Frame {
        boolean perspective;
        /** Eye (perspective) or the point the image centre looks at (orthographic), grid-local. */
        double[] origin;
        double[] f;
        double[] r;
        double[] u;
        /** Orthographic: pixels per block. Perspective: half the image height divided by tan(fov / 2). */
        double scale;
        double back;
        int w;
        int h;
        int offX;
        int offY;
        int imageW;
        int imageH;
        double footprint;
        double footprintPerT;
        Ruler ruler;
        String caption;

        void ray(double px, double py, double[] out) {
            double a = (px - w / 2.0) / scale;
            double b = (h / 2.0 - py) / scale;
            if (perspective) {
                double[] d = Tracer.unit(f[0] + r[0] * a + u[0] * b, f[1] + r[1] * a + u[1] * b, f[2] + r[2] * a + u[2] * b);
                System.arraycopy(origin, 0, out, 0, 3);
                System.arraycopy(d, 0, out, 3, 3);
            } else {
                for (int i = 0; i < 3; i++) {
                    out[i] = origin[i] + r[i] * a + u[i] * b - f[i] * back;
                    out[3 + i] = f[i];
                }
            }
        }
    }

    private static Frame orthoFrame(View.Ortho view, Region region, RenderOptions options) {
        int sx = region.sizeX();
        int sy = region.sizeY();
        int sz = region.sizeZ();
        Frame frame = new Frame();
        Ruler ruler = new Ruler();
        String what;
        switch (view.from()) {
            case TOP -> {
                frame.f = new double[] {0, -1, 0};
                frame.r = new double[] {1, 0, 0};
                frame.u = new double[] {0, 0, -1};
                ruler.across(region.min().x(), region.max().x(), false);
                ruler.down(region.min().z(), region.max().z(), false);
                what = "Top view, north is up: x " + span(ruler, true) + " left to right, z " + span(ruler, false)
                        + " top to bottom";
            }
            case SOUTH -> {
                frame.f = new double[] {0, 0, -1};
                frame.r = new double[] {1, 0, 0};
                frame.u = new double[] {0, 1, 0};
                ruler.across(region.min().x(), region.max().x(), false);
                ruler.down(region.min().y(), region.max().y(), true);
                what = "South side, seen looking north: x " + span(ruler, true) + " left to right, y " + span(ruler, false)
                        + " top to bottom";
            }
            case NORTH -> {
                frame.f = new double[] {0, 0, 1};
                frame.r = new double[] {-1, 0, 0};
                frame.u = new double[] {0, 1, 0};
                ruler.across(region.min().x(), region.max().x(), true);
                ruler.down(region.min().y(), region.max().y(), true);
                what = "North side, seen looking south: x " + span(ruler, true) + " left to right, y " + span(ruler, false)
                        + " top to bottom";
            }
            case EAST -> {
                frame.f = new double[] {-1, 0, 0};
                frame.r = new double[] {0, 0, -1};
                frame.u = new double[] {0, 1, 0};
                ruler.across(region.min().z(), region.max().z(), true);
                ruler.down(region.min().y(), region.max().y(), true);
                what = "East side, seen looking west: z " + span(ruler, true) + " left to right, y " + span(ruler, false)
                        + " top to bottom";
            }
            default -> {
                frame.f = new double[] {1, 0, 0};
                frame.r = new double[] {0, 0, 1};
                frame.u = new double[] {0, 1, 0};
                ruler.across(region.min().z(), region.max().z(), false);
                ruler.down(region.min().y(), region.max().y(), true);
                what = "West side, seen looking east: z " + span(ruler, true) + " left to right, y " + span(ruler, false)
                        + " top to bottom";
            }
        }
        int cols = ruler.acrossMax - ruler.acrossMin + 1;
        int rows = ruler.downMax - ruler.downMin + 1;
        if (options.rulers()) {
            ruler.layout(options.maxSize());
            frame.offX = ruler.marginLeft;
            frame.offY = ruler.marginTop;
            frame.ruler = ruler;
        }
        double scale = Math.min((options.maxSize() - frame.offX) / (double) cols, (options.maxSize() - frame.offY) / (double) rows);
        // Whole pixels per block keep edges crisp; below 4 px that would waste too much of the budget.
        scale = scale >= 4 ? Math.min(Math.floor(scale), MAX_PIXELS_PER_BLOCK) : scale;
        frame.scale = scale;
        frame.w = Math.max(1, (int) Math.ceil(cols * scale - 1e-9));
        frame.h = Math.max(1, (int) Math.ceil(rows * scale - 1e-9));
        // Centre on the box so block edges land on whole pixels when the scale is whole.
        frame.origin = new double[] {
                sx / 2.0 + frame.r[0] * (frame.w / scale - cols) / 2 - frame.u[0] * (frame.h / scale - rows) / 2,
                sy / 2.0 + frame.r[1] * (frame.w / scale - cols) / 2 - frame.u[1] * (frame.h / scale - rows) / 2,
                sz / 2.0 + frame.r[2] * (frame.w / scale - cols) / 2 - frame.u[2] * (frame.h / scale - rows) / 2};
        frame.back = sx + sy + sz + 1;
        frame.footprint = 1 / scale;
        frame.imageW = frame.offX + frame.w;
        frame.imageH = frame.offY + frame.h;
        frame.caption = what + ", " + number(scale) + " px per block" + (frame.ruler != null ? ", coordinates on the rulers." : ".");
        return frame;
    }

    private static String span(Ruler ruler, boolean across) {
        int min = across ? ruler.acrossMin : ruler.downMin;
        int max = across ? ruler.acrossMax : ruler.downMax;
        boolean flip = across ? ruler.acrossFlip : ruler.downFlip;
        return flip ? max + ".." + min : min + ".." + max;
    }

    private static Frame isoFrame(View.Isometric view, Region region, RenderOptions options) {
        double yaw = switch (view.from()) {
            case SOUTH_EAST -> 135;
            case NORTH_EAST -> 45;
            case NORTH_WEST -> -45;
            case SOUTH_WEST -> -135;
        };
        Frame frame = new Frame();
        aim(frame, yaw, ISO_PITCH);
        int sx = region.sizeX();
        int sy = region.sizeY();
        int sz = region.sizeZ();
        double width = 0;
        double height = 0;
        for (int i = 0; i < 3; i++) {
            int size = i == 0 ? sx : i == 1 ? sy : sz;
            width += Math.abs(frame.r[i]) * size;
            height += Math.abs(frame.u[i]) * size;
        }
        double scale = Math.min(Math.min(options.maxSize() / width, options.maxSize() / height), MAX_PIXELS_PER_BLOCK);
        frame.scale = scale;
        frame.w = Math.max(1, (int) Math.ceil(width * scale));
        frame.h = Math.max(1, (int) Math.ceil(height * scale));
        frame.origin = new double[] {sx / 2.0, sy / 2.0, sz / 2.0};
        frame.back = sx + sy + sz + 1;
        frame.footprint = 1 / scale;
        frame.imageW = frame.w;
        frame.imageH = frame.h;
        String corner = view.from().name().toLowerCase(Locale.ROOT).replace('_', '-');
        String towards = switch (view.from()) {
            case SOUTH_EAST -> "north-west";
            case NORTH_EAST -> "south-west";
            case NORTH_WEST -> "south-east";
            case SOUTH_WEST -> "north-east";
        };
        frame.caption = "Isometric view from the " + corner + " corner, looking " + towards + " and 30 degrees down, of "
                + box(region) + ", " + number(scale) + " px per block.";
        return frame;
    }

    private static Frame cameraFrame(View.Camera view, Region region, RenderOptions options) {
        Frame frame = new Frame();
        frame.perspective = true;
        aim(frame, view.yaw(), Math.clamp(view.pitch(), -89.9, 89.9));
        frame.w = options.maxSize();
        frame.h = Math.max(1, options.maxSize() * 3 / 4);
        double tanHalf = Math.tan(Math.toRadians(view.fov() / 2));
        frame.scale = frame.h / 2.0 / tanHalf;
        frame.origin = new double[] {view.x() - region.min().x(), view.y() - region.min().y(), view.z() - region.min().z()};
        frame.footprintPerT = 1 / frame.scale;
        frame.imageW = frame.w;
        frame.imageH = frame.h;
        frame.caption = "First-person view from " + number(view.x()) + "," + number(view.y()) + "," + number(view.z())
                + ", yaw " + number(view.yaw()) + ", pitch " + number(view.pitch()) + ", fov " + number(view.fov())
                + ", showing blocks inside " + box(region) + " only.";
        return frame;
    }

    /** Sets forward, right and up from Minecraft angles (yaw 0 = south, pitch 90 = down). */
    private static void aim(Frame frame, double yawDegrees, double pitchDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double[] f = {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
        double[] r = Tracer.unit(-f[2], 0, f[0]);
        double[] u = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
        frame.f = f;
        frame.r = r;
        frame.u = u;
    }

    private static String box(Region region) {
        return region.min().x() + "," + region.min().y() + "," + region.min().z() + " "
                + region.max().x() + "," + region.max().y() + "," + region.max().z();
    }

    private static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.2f", value);
    }

    // ---- rulers for top and side views ----

    private static final class Ruler {
        int acrossMin;
        int acrossMax;
        boolean acrossFlip;
        int downMin;
        int downMax;
        boolean downFlip;
        int fontScale;
        int marginLeft;
        int marginTop;

        /** Coordinates along the image's horizontal axis; {@code flip} when they fall from left to right. */
        void across(int min, int max, boolean flip) {
            acrossMin = min;
            acrossMax = max;
            acrossFlip = flip;
        }

        /** Coordinates along the image's vertical axis; {@code flip} when they fall from top to bottom. */
        void down(int min, int max, boolean flip) {
            downMin = min;
            downMax = max;
            downFlip = flip;
        }

        void layout(int maxSize) {
            fontScale = maxSize >= 384 ? 2 : 1;
            marginLeft = labelWidth(downMin, downMax) + 3 * fontScale;
            marginTop = PixelFont.HEIGHT * fontScale + 3 * fontScale;
        }

        private int labelWidth(int min, int max) {
            return Math.max(PixelFont.width(Integer.toString(min), fontScale), PixelFont.width(Integer.toString(max), fontScale));
        }

        void draw(int[] pixels, Frame frame) {
            int acrossStep = step((labelWidth(acrossMin, acrossMax) + 2 * fontScale) / frame.scale);
            int downStep = step((PixelFont.HEIGHT * fontScale + 2) / frame.scale);
            for (int i = 0; i <= acrossMax - acrossMin; i++) {
                int value = acrossFlip ? acrossMax - i : acrossMin + i;
                if (Math.floorMod(value, acrossStep) == 0) {
                    String label = Integer.toString(value);
                    int centre = frame.offX + (int) ((i + 0.5) * frame.scale);
                    PixelFont.draw(pixels, frame.imageW, frame.imageH, centre - PixelFont.width(label, fontScale) / 2,
                            fontScale, label, fontScale, INK);
                    PixelFont.fill(pixels, frame.imageW, frame.imageH, centre, marginTop - fontScale, 1, fontScale, INK);
                }
            }
            for (int i = 0; i <= downMax - downMin; i++) {
                int value = downFlip ? downMax - i : downMin + i;
                if (Math.floorMod(value, downStep) == 0) {
                    String label = Integer.toString(value);
                    int centre = frame.offY + (int) ((i + 0.5) * frame.scale);
                    PixelFont.draw(pixels, frame.imageW, frame.imageH,
                            marginLeft - 2 * fontScale - PixelFont.width(label, fontScale),
                            centre - PixelFont.HEIGHT * fontScale / 2, label, fontScale, INK);
                    PixelFont.fill(pixels, frame.imageW, frame.imageH, marginLeft - fontScale, centre, fontScale, 1, INK);
                }
            }
        }

        /** Smallest step from 1, 2, 5, 10, 20, 50, ... that is at least {@code blocks} wide. */
        private static int step(double blocks) {
            int[] bases = {1, 2, 5};
            for (int magnitude = 1; magnitude <= 100_000_000; magnitude *= 10) {
                for (int base : bases) {
                    if (base * magnitude >= blocks) {
                        return base * magnitude;
                    }
                }
            }
            return 1_000_000_000;
        }
    }
}
