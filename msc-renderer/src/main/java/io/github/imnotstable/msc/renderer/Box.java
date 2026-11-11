package io.github.imnotstable.msc.renderer;

/** An axis-aligned box inside one block, in block units (0..1). */
record Box(double x0, double y0, double z0, double x1, double y1, double z1) {
    static final Box FULL = new Box(0, 0, 0, 1, 1, 1);

    /** A box given in sixteenths of a block, the unit Minecraft models use. */
    static Box px(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new Box(x0 / 16, y0 / 16, z0 / 16, x1 / 16, y1 / 16, z1 / 16);
    }

    boolean isFull() {
        return x0 <= 0 && y0 <= 0 && z0 <= 0 && x1 >= 1 && y1 >= 1 && z1 >= 1;
    }
}
