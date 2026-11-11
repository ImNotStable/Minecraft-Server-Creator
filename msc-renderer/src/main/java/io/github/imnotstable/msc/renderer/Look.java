package io.github.imnotstable.msc.renderer;

/**
 * How one block state is drawn: its boxes and, per face kind (top, side, bottom), a colour and an
 * optional texture. Resolved once per palette entry.
 */
final class Look {
    final String name;
    final Box[] boxes;
    final boolean full;
    final int[] argb;
    final Tex[] tex;
    /** Face kind (BlockColors.TOP/SIDE/BOTTOM) for each of the six faces: -x, +x, -y, +y, -z, +z. */
    final int[] faceKind;
    /** True when light passes through: the block is alpha-blended and casts no shadow. */
    final boolean translucent;
    final boolean knownColor;

    Look(String name, Box[] boxes, int[] argb, Tex[] tex, int[] faceKind, boolean translucent, boolean knownColor) {
        this.name = name;
        this.boxes = boxes;
        this.full = boxes.length == 1 && boxes[0].isFull();
        this.argb = argb;
        this.tex = tex;
        this.faceKind = faceKind;
        this.translucent = translucent;
        this.knownColor = knownColor;
    }

    /** A square texture as ARGB pixels, with its average colour for when blocks are only a few pixels wide. */
    record Tex(int size, int[] argb, int average) {
        int sample(double u, double v) {
            int x = Math.clamp((int) (u * size), 0, size - 1);
            int y = Math.clamp((int) (v * size), 0, size - 1);
            return argb[y * size + x];
        }
    }
}
