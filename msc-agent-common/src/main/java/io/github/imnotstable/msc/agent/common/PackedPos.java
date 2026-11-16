package io.github.imnotstable.msc.agent.common;

/** Packs a block position into one long: 26 bits X, 26 bits Z, 12 bits Y, all signed. */
public final class PackedPos {
    /** Lowest X or Z that packs without wrapping. */
    public static final int MIN_XZ = -(1 << 25);
    /** Highest X or Z that packs without wrapping. */
    public static final int MAX_XZ = (1 << 25) - 1;

    private PackedPos() {
    }

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static int x(long packed) {
        return (int) (packed >> 38);
    }

    public static int z(long packed) {
        return (int) (packed << 26 >> 38);
    }

    public static int y(long packed) {
        return (int) (packed << 52 >> 52);
    }
}
