package io.github.imnotstable.msc.renderer;

/**
 * Knobs for one render.
 *
 * @param maxSize  longest image side in pixels, 64 to 1568. Vision cost grows with the pixel count
 *                 (about width x height / 750 tokens), so the default is small
 * @param shadows  cast sun shadows; they show height and overhangs
 * @param outlines draw a thin dark line along block edges so blocks can be counted
 * @param rulers   on top and side views, add a margin with world coordinates along both axes
 * @param smooth   2x2 supersampling
 */
public record RenderOptions(int maxSize, boolean shadows, boolean outlines, boolean rulers, boolean smooth) {
    public static final int MIN_SIZE = 64;
    public static final int MAX_SIZE = 1568;
    public static final int DEFAULT_SIZE = 512;

    public RenderOptions {
        if (maxSize < MIN_SIZE || maxSize > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between " + MIN_SIZE + " and " + MAX_SIZE + " pixels");
        }
    }

    public static RenderOptions defaults() {
        return new RenderOptions(DEFAULT_SIZE, true, true, true, true);
    }

    public RenderOptions withMaxSize(int size) {
        return new RenderOptions(size, shadows, outlines, rulers, smooth);
    }

    public RenderOptions withRulers(boolean on) {
        return new RenderOptions(maxSize, shadows, outlines, on, smooth);
    }

    public RenderOptions withShadows(boolean on) {
        return new RenderOptions(maxSize, on, outlines, rulers, smooth);
    }

    public RenderOptions withOutlines(boolean on) {
        return new RenderOptions(maxSize, shadows, on, rulers, smooth);
    }

    public RenderOptions withSmooth(boolean on) {
        return new RenderOptions(maxSize, shadows, outlines, rulers, on);
    }
}
